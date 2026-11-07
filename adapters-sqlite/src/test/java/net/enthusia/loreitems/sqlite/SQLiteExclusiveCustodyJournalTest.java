package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.StorageState;
import net.enthusia.loreitems.domain.ExclusiveCustodyReservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteExclusiveCustodyJournalTest {
    @TempDir Path temp;

    @Test void reservationSurvivesReopenAndConflictingWorkersCannotReplaceIt() throws Exception {
        SQLiteConnectionFactory factory = new SQLiteConnectionFactory(temp.resolve("custody.db"), 5000);
        try (Connection connection = factory.open()) {
            new MigrationRunner().migrate(connection);
        }
        var journal = new SQLiteExclusiveCustodyJournal();
        var first = new ExclusiveCustodyReservation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                ExclusiveCustodyReservation.State.RESERVED);
        var other = new ExclusiveCustodyReservation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                ExclusiveCustodyReservation.State.RESERVED);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { try (Connection c = factory.open()) { return journal.reserve(c, first); } });
            var b = pool.submit(() -> { try (Connection c = factory.open()) { return journal.reserve(c, other); } });
            var ar = a.get(); var br = b.get();
            assertTrue((ar == SQLiteExclusiveCustodyJournal.Result.RESERVED && br == SQLiteExclusiveCustodyJournal.Result.CONFLICT)
                    || (br == SQLiteExclusiveCustodyJournal.Result.RESERVED && ar == SQLiteExclusiveCustodyJournal.Result.CONFLICT));
            var winner = ar == SQLiteExclusiveCustodyJournal.Result.RESERVED ? first : other;
            var loser = winner == first ? other : first;
            try (Connection c = factory.open()) {
                assertEquals(SQLiteExclusiveCustodyJournal.Result.REPLAY, journal.reserve(c, winner));
                assertFalse(journal.holdForReview(c, loser));
                assertTrue(journal.holdForReview(c, winner));
            }
            try (Connection reopened = factory.open()) {
                assertEquals(SQLiteExclusiveCustodyJournal.Result.REVIEW_REQUIRED, journal.reserve(reopened, winner));
                assertEquals(SQLiteExclusiveCustodyJournal.Result.CONFLICT, journal.reserve(reopened, loser));
                assertFalse(journal.holdForReview(reopened, winner));
            }
        } finally { pool.shutdownNow(); }
    }

    @Test void missingSchemaFailsClosed() throws Exception {
        var request = new ExclusiveCustodyReservation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0,
                ExclusiveCustodyReservation.State.RESERVED);
        try (Connection connection = new SQLiteConnectionFactory(temp.resolve("missing.db"), 5000).open()) {
            assertThrows(java.sql.SQLException.class, () -> new SQLiteExclusiveCustodyJournal().reserve(connection, request));
        }
    }

    @Test void rolledBackReservationAndReviewDoNotPretendToBeDurable() throws Exception {
        SQLiteConnectionFactory factory = new SQLiteConnectionFactory(temp.resolve("rollback.db"), 5000);
        try (Connection connection = factory.open()) {
            new MigrationRunner().migrate(connection);
        }
        var journal = new SQLiteExclusiveCustodyJournal();
        var request = new ExclusiveCustodyReservation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                ExclusiveCustodyReservation.State.RESERVED);
        try (Connection connection = factory.open()) {
            connection.setAutoCommit(false);
            assertEquals(SQLiteExclusiveCustodyJournal.Result.RESERVED, journal.reserve(connection, request));
            connection.rollback();
        }
        try (Connection connection = factory.open()) {
            assertEquals(SQLiteExclusiveCustodyJournal.Result.RESERVED, journal.reserve(connection, request));
            connection.setAutoCommit(false);
            assertTrue(journal.holdForReview(connection, request));
            connection.rollback();
        }
        try (Connection reopened = factory.open()) {
            assertEquals(SQLiteExclusiveCustodyJournal.Result.REPLAY, journal.reserve(reopened, request));
        }
    }

    @Test void asynchronousReservationReturnsAfterCommittedDataCanBeReopened() throws Exception {
        var path = temp.resolve("committed.db");
        var factory = new SQLiteConnectionFactory(path, 5000);
        try (Connection connection = factory.open()) {
            new MigrationRunner().migrate(connection);
        }
        var metrics = MetricsPort.noOp();
        var runtime = new SQLiteStorageRuntime(factory, new MigrationRunner(),
                new BoundedDatabaseExecutor("crown-test", 16, metrics), metrics);
        assertEquals(StorageState.READ_WRITE, runtime.start().toCompletableFuture().join().state());
        var journal = new SQLiteExclusiveCustodyJournal();
        var request = new ExclusiveCustodyReservation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                ExclusiveCustodyReservation.State.RESERVED);
        try {
            assertEquals(SQLiteExclusiveCustodyJournal.Result.RESERVED, journal.reserveCommitted(runtime, request).toCompletableFuture().join());
            assertEquals(SQLiteExclusiveCustodyJournal.Result.REPLAY, journal.reserveCommitted(runtime, request).toCompletableFuture().join());
            try (Connection connection = factory.open(); var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER reject_hold BEFORE INSERT ON crown_custody_audit "
                        + "WHEN NEW.transition='REVIEW_REQUIRED' BEGIN SELECT RAISE(ABORT, 'audit failure'); END");
            }
            assertThrows(java.util.concurrent.CompletionException.class,
                    () -> journal.holdCommitted(runtime, request).toCompletableFuture().join());
            assertEquals(SQLiteExclusiveCustodyJournal.Result.REPLAY, journal.reserveCommitted(runtime, request).toCompletableFuture().join());
            try (Connection connection = factory.open(); var statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER reject_hold");
            }
            assertTrue(journal.holdCommitted(runtime, request).toCompletableFuture().join());
            assertTrue(journal.holdCommitted(runtime, request).toCompletableFuture().join());
            try (Connection reopened = factory.open()) {
                assertEquals(SQLiteExclusiveCustodyJournal.Result.REVIEW_REQUIRED, journal.reserve(reopened, request));
                try (var statement = reopened.createStatement(); var rows = statement.executeQuery("SELECT COUNT(*) FROM crown_custody_audit")) {
                    assertTrue(rows.next()); assertEquals(2, rows.getInt(1));
                }
                try (var statement = reopened.createStatement()) {
                    statement.executeUpdate("DELETE FROM crown_custody_audit WHERE transition='REVIEW_REQUIRED'");
                }
            }
            assertThrows(java.util.concurrent.CompletionException.class,
                    () -> journal.reserveCommitted(runtime, request).toCompletableFuture().join());
        } finally { assertTrue(runtime.close(Duration.ofSeconds(5))); }
    }

    @Test void failedAuditRollsBackReservation() throws Exception {
        var factory = new SQLiteConnectionFactory(temp.resolve("audit-failure.db"), 5000);
        try (Connection connection = factory.open(); var statement = connection.createStatement()) {
            new MigrationRunner().migrate(connection);
            statement.execute("CREATE TRIGGER reject_crown_audit BEFORE INSERT ON crown_custody_audit "
                    + "BEGIN SELECT RAISE(ABORT, 'audit failure'); END");
        }
        var metrics = MetricsPort.noOp();
        var runtime = new SQLiteStorageRuntime(factory, new MigrationRunner(),
                new BoundedDatabaseExecutor("crown-audit-failure", 16, metrics), metrics);
        assertEquals(StorageState.READ_WRITE, runtime.start().toCompletableFuture().join().state());
        var request = new ExclusiveCustodyReservation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                ExclusiveCustodyReservation.State.RESERVED);
        try {
            assertThrows(java.util.concurrent.CompletionException.class,
                    () -> new SQLiteExclusiveCustodyJournal().reserveCommitted(runtime, request).toCompletableFuture().join());
            try (Connection connection = factory.open(); var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT COUNT(*) FROM crown_custody")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
        } finally { assertTrue(runtime.close(Duration.ofSeconds(5))); }
    }

    @Test void restartMovesInterruptedReservationToDurableReviewWithoutReissuing() throws Exception {
        var factory = new SQLiteConnectionFactory(temp.resolve("startup-recovery.db"), 5000);
        try (Connection connection = factory.open()) {
            new MigrationRunner().migrate(connection);
        }
        var metrics = MetricsPort.noOp();
        var journal = new SQLiteExclusiveCustodyJournal();
        var request = new ExclusiveCustodyReservation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                ExclusiveCustodyReservation.State.RESERVED);
        var first = new SQLiteStorageRuntime(factory, new MigrationRunner(),
                new BoundedDatabaseExecutor("crown-before-restart", 16, metrics), metrics);
        assertEquals(StorageState.READ_WRITE, first.start().toCompletableFuture().join().state());
        try {
            assertEquals(SQLiteExclusiveCustodyJournal.RecoveryResult.NO_RESERVATION,
                    journal.recoverInterruptedOnStartup(first).toCompletableFuture().join());
            assertEquals(SQLiteExclusiveCustodyJournal.Result.RESERVED, journal.reserveCommitted(first, request).toCompletableFuture().join());
        } finally { assertTrue(first.close(Duration.ofSeconds(5))); }

        var restarted = new SQLiteStorageRuntime(factory, new MigrationRunner(),
                new BoundedDatabaseExecutor("crown-after-restart", 16, metrics), metrics);
        assertEquals(StorageState.READ_WRITE, restarted.start().toCompletableFuture().join().state());
        try {
            try (Connection c = factory.open(); var statement = c.createStatement()) {
                statement.execute("CREATE TRIGGER reject_recovery BEFORE INSERT ON crown_custody_audit "
                        + "WHEN NEW.transition='REVIEW_REQUIRED' BEGIN SELECT RAISE(ABORT, 'audit failure'); END");
            }
            assertThrows(java.util.concurrent.CompletionException.class,
                    () -> journal.recoverInterruptedOnStartup(restarted).toCompletableFuture().join());
            assertEquals(SQLiteExclusiveCustodyJournal.Result.REPLAY,
                    journal.reserveCommitted(restarted, request).toCompletableFuture().join());
            try (Connection c = factory.open(); var statement = c.createStatement()) {
                statement.execute("DROP TRIGGER reject_recovery");
            }
            assertEquals(SQLiteExclusiveCustodyJournal.RecoveryResult.REVIEW_REQUIRED,
                    journal.recoverInterruptedOnStartup(restarted).toCompletableFuture().join());
            assertEquals(SQLiteExclusiveCustodyJournal.RecoveryResult.ALREADY_IN_REVIEW,
                    journal.recoverInterruptedOnStartup(restarted).toCompletableFuture().join());
            assertEquals(SQLiteExclusiveCustodyJournal.Result.REVIEW_REQUIRED,
                    journal.reserveCommitted(restarted, request).toCompletableFuture().join());
            try (Connection c = factory.open(); var statement = c.createStatement()) {
                statement.executeUpdate("DELETE FROM crown_custody_audit WHERE transition='REVIEW_REQUIRED'");
            }
            assertThrows(java.util.concurrent.CompletionException.class,
                    () -> journal.recoverInterruptedOnStartup(restarted).toCompletableFuture().join());
        } finally { assertTrue(restarted.close(Duration.ofSeconds(5))); }
    }
}
