package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.CrownHelmetEvidenceStore;
import net.enthusia.loreitems.application.StorageState;
import net.enthusia.loreitems.domain.ExclusiveCustodyReservation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteCrownHelmetEvidenceTest {
    @TempDir Path temp;

    @Test void failedFinalAuditKeepsTheOriginalAndReplayCannotReopenAdmission() throws Exception {
        var factory = fixture("equip-finalization.db");
        var runtime = start(factory);
        var journal = new SQLiteExclusiveCustodyJournal();
        var store = new SQLiteCrownHelmetEvidenceStore(runtime);
        var request = request();
        byte[] original = {2, 7};
        try {
            assertTrue(reserveFixture(runtime, request, original));
            try (Connection connection = factory.open(); var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER reject_final_audit BEFORE INSERT ON crown_custody_audit "
                        + "WHEN NEW.transition='EQUIPPED' BEGIN SELECT RAISE(ABORT, 'audit failure'); END");
            }
            assertThrows(CompletionException.class, () -> store.commitEquipment(request, original).toCompletableFuture().join());
            assertTrue(store.matchesSavedHelmet(request, original).toCompletableFuture().join());
            assertTrue(store.readEquipment(request).toCompletableFuture().join().isEmpty());
            try (Connection connection = factory.open(); var statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER reject_final_audit");
            }
            var receipt = store.commitEquipment(request, original).toCompletableFuture().join().orElseThrow();
            assertEquals(receipt, store.commitEquipment(request, original).toCompletableFuture().join().orElseThrow());
            assertTrue(store.commitEquipment(request, new byte[]{1}).toCompletableFuture().join().isEmpty());
            assertFalse(reserveFixture(runtime, request, original));
            assertTrue(store.readEquipment(request()).toCompletableFuture().join().isEmpty());
            assertEquals(SQLiteExclusiveCustodyJournal.RecoveryResult.EQUIPPED,
                    journal.recoverInterruptedOnStartup(runtime).toCompletableFuture().join());
            assertEquals(receipt, store.readEquipment(request).toCompletableFuture().join().orElseThrow());
        } finally { assertTrue(runtime.close(Duration.ofSeconds(5))); }
    }

    @Test void emptyHelmetMayFinalizeButHeldCustodyCannot() throws Exception {
        var factory = fixture("empty-final.db");
        var runtime = start(factory);
        var request = request();
        var store = new SQLiteCrownHelmetEvidenceStore(runtime);
        try {
            assertTrue(reserveFixture(runtime, request, new byte[0]));
            assertTrue(store.commitEquipment(request, new byte[0]).toCompletableFuture().join().isPresent());
        } finally { assertTrue(runtime.close(Duration.ofSeconds(5))); }
        var held = start(fixture("held-final.db"));
        var heldStore = new SQLiteCrownHelmetEvidenceStore(held);
        try {
            assertTrue(reserveFixture(held, request, new byte[0]));
            assertTrue(heldStore.hold(request).toCompletableFuture().join());
            assertTrue(heldStore.commitEquipment(request, new byte[0]).toCompletableFuture().join().isEmpty());
        } finally { assertTrue(held.close(Duration.ofSeconds(5))); }
    }
    @Test void helmetEvidenceIsBoundToOneReservationAndCannotBeReplayedWithDifferentBytes() throws Exception {
        var factory = fixture("helmet.db");
        var runtime = start(factory);
        var journal = new SQLiteExclusiveCustodyJournal();
        var request = request();
        try {
            assertEquals(SQLiteExclusiveCustodyJournal.HelmetEvidenceResult.HOLD,
                    journal.recordHelmetEvidence(runtime, request, new byte[] {1, 2}).toCompletableFuture().join());
            assertEquals(SQLiteExclusiveCustodyJournal.Result.RESERVED, journal.reserveCommitted(runtime, request).toCompletableFuture().join());
            var started = new CountDownLatch(1); var release = new CountDownLatch(1);
            var blocker = runtime.execute(connection -> { started.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout"); return null; });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            byte[] mutable = {1, 2};
            var write = journal.recordHelmetEvidence(runtime, request, mutable);
            mutable[0] = 9;
            release.countDown(); blocker.toCompletableFuture().join();
            assertEquals(SQLiteExclusiveCustodyJournal.HelmetEvidenceResult.RECORDED, write.toCompletableFuture().join());
            assertEquals(SQLiteExclusiveCustodyJournal.HelmetEvidenceResult.REPLAY,
                    journal.recordHelmetEvidence(runtime, request, new byte[] {1, 2}).toCompletableFuture().join());
            assertEquals(CrownHelmetEvidenceStore.Result.REPLAY,
                    new SQLiteCrownHelmetEvidenceStore(runtime).record(request, false, new byte[] {1, 2}).toCompletableFuture().join());
            assertEquals(SQLiteExclusiveCustodyJournal.HelmetEvidenceResult.HOLD,
                    journal.recordHelmetEvidence(runtime, request, new byte[] {1, 3}).toCompletableFuture().join());
            assertEquals(SQLiteExclusiveCustodyJournal.HelmetEvidenceResult.HOLD,
                    journal.recordHelmetEvidence(runtime, request(), new byte[] {1, 2}).toCompletableFuture().join());
            assertThrows(IllegalArgumentException.class, () -> journal.recordHelmetEvidence(runtime, request, new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> journal.recordHelmetEvidence(runtime, request, new byte[1024 * 1024 + 1]));
            assertTrue(new SQLiteCrownHelmetEvidenceStore(runtime).hold(request).toCompletableFuture().join());
            assertTrue(new SQLiteCrownHelmetEvidenceStore(runtime).hold(request).toCompletableFuture().join());
            assertFalse(new SQLiteCrownHelmetEvidenceStore(runtime).hold(request()).toCompletableFuture().join());
            assertEquals(SQLiteExclusiveCustodyJournal.HelmetEvidenceResult.HOLD,
                    journal.recordHelmetEvidence(runtime, request, new byte[] {1, 2}).toCompletableFuture().join());
        } finally { assertTrue(runtime.close(Duration.ofSeconds(5))); }
        try (Connection connection = factory.open(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT COUNT(*) FROM crown_helmet_evidence")) {
            assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
        }
    }

    @Test void repeatedHoldRejectsMissingReviewAuditRatherThanClaimingSuccess() throws Exception {
        var factory = fixture("hold-replay-audit.db"); var runtime = start(factory);
        var journal = new SQLiteExclusiveCustodyJournal(); var request = request();
        try {
            journal.reserveCommitted(runtime, request).toCompletableFuture().join();
            assertTrue(journal.holdCommitted(runtime, request).toCompletableFuture().join());
            try (Connection connection = factory.open(); var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM crown_custody_audit WHERE transition='REVIEW_REQUIRED'");
            }
            assertThrows(CompletionException.class, () -> journal.holdCommitted(runtime, request).toCompletableFuture().join());
        } finally { assertTrue(runtime.close(Duration.ofSeconds(5))); }
    }

    @Test void failedAuditDoesNotCommitHelmetEvidence() throws Exception {
        var factory = fixture("rollback-helmet.db");
        var runtime = start(factory); var journal = new SQLiteExclusiveCustodyJournal(); var request = request();
        try {
            journal.reserveCommitted(runtime, request).toCompletableFuture().join();
            try (Connection connection = factory.open(); var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER reject_helmet_audit BEFORE INSERT ON crown_custody_audit "
                        + "WHEN NEW.transition='HELMET_EVIDENCE' BEGIN SELECT RAISE(ABORT, 'audit failure'); END");
            }
            assertThrows(CompletionException.class,
                    () -> journal.recordHelmetEvidence(runtime, request, new byte[] {7}).toCompletableFuture().join());
            try (Connection connection = factory.open(); var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT COUNT(*) FROM crown_helmet_evidence")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
        } finally { assertTrue(runtime.close(Duration.ofSeconds(5))); }
    }

    @Test void emptyHeadHasDistinctEvidenceAndRemainsHeldAfterRestart() throws Exception {
        var factory = fixture("empty-head.db"); var journal = new SQLiteExclusiveCustodyJournal(); var request = request();
        var first = start(factory);
        try {
            journal.reserveCommitted(first, request).toCompletableFuture().join();
            assertEquals(SQLiteExclusiveCustodyJournal.HelmetEvidenceResult.RECORDED,
                    journal.recordEmptyHelmetEvidence(first, request).toCompletableFuture().join());
            assertEquals(SQLiteExclusiveCustodyJournal.HelmetEvidenceResult.REPLAY,
                    journal.recordEmptyHelmetEvidence(first, request).toCompletableFuture().join());
            assertEquals(CrownHelmetEvidenceStore.Result.REPLAY,
                    new SQLiteCrownHelmetEvidenceStore(first).record(request, true, new byte[0]).toCompletableFuture().join());
            assertThrows(IllegalArgumentException.class,
                    () -> new SQLiteCrownHelmetEvidenceStore(first).record(request, true, new byte[] {1}));
            assertEquals(SQLiteExclusiveCustodyJournal.HelmetEvidenceResult.HOLD,
                    journal.recordHelmetEvidence(first, request, new byte[] {1}).toCompletableFuture().join());
        } finally { assertTrue(first.close(Duration.ofSeconds(5))); }
        var restarted = start(factory);
        try {
            assertEquals(SQLiteExclusiveCustodyJournal.RecoveryResult.REVIEW_REQUIRED,
                    journal.recoverInterruptedOnStartup(restarted).toCompletableFuture().join());
            try (Connection connection = factory.open(); var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE crown_helmet_evidence SET is_empty=0");
            }
            assertThrows(CompletionException.class, () -> journal.recoverInterruptedOnStartup(restarted).toCompletableFuture().join());
        } finally { assertTrue(restarted.close(Duration.ofSeconds(5))); }
    }

    @Test void restartHoldsEvidenceAndRejectsMissingHelmetData() throws Exception {
        var factory = fixture("restart-helmet.db"); var journal = new SQLiteExclusiveCustodyJournal(); var request = request();
        var first = start(factory);
        try {
            journal.reserveCommitted(first, request).toCompletableFuture().join();
            assertEquals(SQLiteExclusiveCustodyJournal.HelmetEvidenceResult.RECORDED,
                    journal.recordHelmetEvidence(first, request, new byte[] {3, 4}).toCompletableFuture().join());
        } finally { assertTrue(first.close(Duration.ofSeconds(5))); }
        var restarted = start(factory);
        try {
            assertEquals(SQLiteExclusiveCustodyJournal.RecoveryResult.REVIEW_REQUIRED,
                    journal.recoverInterruptedOnStartup(restarted).toCompletableFuture().join());
            try (Connection connection = factory.open(); var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE crown_helmet_evidence SET payload=X'0506'");
            }
            assertThrows(CompletionException.class, () -> journal.recoverInterruptedOnStartup(restarted).toCompletableFuture().join());
            try (Connection connection = factory.open(); var statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE crown_helmet_evidence SET payload=X'0304'");
            }
            assertEquals(SQLiteExclusiveCustodyJournal.RecoveryResult.ALREADY_IN_REVIEW,
                    journal.recoverInterruptedOnStartup(restarted).toCompletableFuture().join());
            try (Connection connection = factory.open(); var statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM crown_helmet_evidence");
            }
            assertThrows(CompletionException.class, () -> journal.recoverInterruptedOnStartup(restarted).toCompletableFuture().join());
        } finally { assertTrue(restarted.close(Duration.ofSeconds(5))); }
    }

    @Test void startupRejectsOrphanHelmetEvidenceInsteadOfReportingNoReservation() throws Exception {
        var factory = fixture("orphan-helmet.db");
        try (Connection connection = factory.open(); var statement = connection.createStatement()) {
            statement.execute("INSERT INTO crown_helmet_evidence VALUES ('op','instance','holder',1,X'01','hash',0)");
        }
        var runtime = start(factory);
        try {
            assertThrows(CompletionException.class,
                    () -> new SQLiteExclusiveCustodyJournal().recoverInterruptedOnStartup(runtime).toCompletableFuture().join());
            assertThrows(CompletionException.class,
                    () -> new SQLiteExclusiveCustodyJournal().reserveCommitted(runtime, request()).toCompletableFuture().join());
        } finally { assertTrue(runtime.close(Duration.ofSeconds(5))); }
    }

    @Test void foreignHelmetEvidencePreventsAnAdditionalSnapshotForAnExistingReservation() throws Exception {
        var factory = fixture("foreign-helmet.db"); var runtime = start(factory);
        var journal = new SQLiteExclusiveCustodyJournal(); var request = request();
        try {
            journal.reserveCommitted(runtime, request).toCompletableFuture().join();
            try (Connection connection = factory.open(); var statement = connection.createStatement()) {
                statement.execute("INSERT INTO crown_helmet_evidence VALUES ('other','instance','holder',1,X'01','hash',0)");
            }
            assertThrows(CompletionException.class,
                    () -> journal.recordHelmetEvidence(runtime, request, new byte[] {2}).toCompletableFuture().join());
            assertThrows(CompletionException.class,
                    () -> journal.recoverInterruptedOnStartup(runtime).toCompletableFuture().join());
            try (Connection connection = factory.open(); var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT COUNT(*) FROM crown_helmet_evidence")) {
                assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
            }
        } finally { assertTrue(runtime.close(Duration.ofSeconds(5))); }
    }

    @Test void foreignReservationAuditPreventsEvidenceAndRestartRecovery() throws Exception {
        var factory = fixture("foreign-reservation-audit.db"); var runtime = start(factory);
        var journal = new SQLiteExclusiveCustodyJournal(); var request = request();
        try {
            journal.reserveCommitted(runtime, request).toCompletableFuture().join();
            try (Connection connection = factory.open(); var statement = connection.createStatement()) {
                statement.execute("INSERT INTO crown_custody_audit VALUES ('other','RESERVED','instance','holder',1,1)");
            }
            assertThrows(CompletionException.class,
                    () -> journal.recordHelmetEvidence(runtime, request, new byte[] {2}).toCompletableFuture().join());
            assertThrows(CompletionException.class,
                    () -> journal.recoverInterruptedOnStartup(runtime).toCompletableFuture().join());
        } finally { assertTrue(runtime.close(Duration.ofSeconds(5))); }
    }

    @Test void unexpectedAuditForTheSameOperationBlocksEvidenceAndRestart() throws Exception {
        var factory = fixture("unexpected-same-operation-audit.db"); var runtime = start(factory);
        var journal = new SQLiteExclusiveCustodyJournal(); var request = request();
        try {
            journal.reserveCommitted(runtime, request).toCompletableFuture().join();
            try (Connection connection = factory.open(); var statement = connection.prepareStatement(
                    "INSERT INTO crown_custody_audit VALUES (?,?,?,?,?,?)")) {
                statement.setString(1, request.operationId().toString()); statement.setString(2, "UNEXPECTED");
                statement.setString(3, request.instanceId().toString()); statement.setString(4, request.holderId().toString());
                statement.setLong(5, request.generation()); statement.setLong(6, 1); statement.executeUpdate();
            }
            assertThrows(CompletionException.class,
                    () -> journal.recordHelmetEvidence(runtime, request, new byte[] {2}).toCompletableFuture().join());
            assertThrows(CompletionException.class,
                    () -> journal.recoverInterruptedOnStartup(runtime).toCompletableFuture().join());
        } finally { assertTrue(runtime.close(Duration.ofSeconds(5))); }
    }

    private static boolean reserveFixture(SQLiteStorageRuntime runtime, ExclusiveCustodyReservation request, byte[] original) {
        var journal = new SQLiteExclusiveCustodyJournal();
        if (journal.reserveCommitted(runtime, request).toCompletableFuture().join() != SQLiteExclusiveCustodyJournal.Result.RESERVED) {
            return false;
        }
        return new SQLiteCrownHelmetEvidenceStore(runtime).record(request, original.length == 0, original)
                .toCompletableFuture().join() == CrownHelmetEvidenceStore.Result.RECORDED;
    }
    private SQLiteConnectionFactory fixture(String name) throws Exception {
        var factory = new SQLiteConnectionFactory(temp.resolve(name), 5000);
        try (Connection connection = factory.open()) {
            new MigrationRunner().migrate(connection);
        }
        return factory;
    }

    private static SQLiteStorageRuntime start(SQLiteConnectionFactory factory) {
        var metrics = MetricsPort.noOp();
        var runtime = new SQLiteStorageRuntime(factory, new MigrationRunner(), new BoundedDatabaseExecutor("helmet-evidence", 16, metrics), metrics);
        assertEquals(StorageState.READ_WRITE, runtime.start().toCompletableFuture().join().state());
        return runtime;
    }

    private static ExclusiveCustodyReservation request() {
        return new ExclusiveCustodyReservation(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1,
                ExclusiveCustodyReservation.State.RESERVED);
    }
}
