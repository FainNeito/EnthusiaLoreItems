package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.StorageState;
import net.enthusia.loreitems.domain.ExclusiveCustodyReservation;
import net.enthusia.loreitems.sqlite.BoundedDatabaseExecutor;
import net.enthusia.loreitems.sqlite.MigrationRunner;
import net.enthusia.loreitems.sqlite.SQLiteConnectionFactory;
import net.enthusia.loreitems.sqlite.SQLiteCrownHelmetEvidenceStore;
import net.enthusia.loreitems.sqlite.SQLiteStorageRuntime;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

class PaperCrownHelmetSqliteIntegrationTest {
    @TempDir Path temp;
    @BeforeEach void start() { MockBukkit.mock(); }
    @AfterEach void stop() { MockBukkit.unmock(); }

    @Test void changedHelmetAfterWriteIsPlacedInDurableReview() throws Exception {
        var player = MockBukkit.getMock().addPlayer();
        player.getInventory().setHelmet(ItemStack.of(Material.NETHERITE_HELMET));
        var request = new ExclusiveCustodyReservation(UUID.randomUUID(), UUID.randomUUID(), player.getUniqueId(), 1,
                ExclusiveCustodyReservation.State.RESERVED);
        var factory = fixture(request); var metrics = MetricsPort.noOp();
        var runtime = new SQLiteStorageRuntime(factory, new MigrationRunner(),
                new BoundedDatabaseExecutor("paper-crown-evidence", 16, metrics), metrics);
        assertEquals(StorageState.READ_WRITE, runtime.start().toCompletableFuture().join().state());
        var scheduled = new LinkedBlockingQueue<Runnable>();
        try {
            var coordinator = new PaperCrownHelmetPreparation(new PaperItemCodecThreadGuard(() -> true),
                    new PaperCrownPreparationFence(new PaperItemCodecThreadGuard(() -> true)),
                    new SQLiteCrownHelmetEvidenceStore(runtime), scheduled::add, id -> player);
            var outcome = coordinator.prepare(player, request).toCompletableFuture();
            var callback = scheduled.poll(10, TimeUnit.SECONDS);
            assertNotNull(callback);
            player.getInventory().setHelmet(ItemStack.of(Material.DIAMOND_HELMET));
            callback.run();
            assertEquals(PaperCrownHelmetPreparation.Result.HELD, outcome.get(10, TimeUnit.SECONDS));
            assertEquals(Material.DIAMOND_HELMET, player.getInventory().getHelmet().getType());
        } finally { assertTrue(runtime.close(Duration.ofSeconds(5))); }
        try (Connection connection = factory.open(); var statement = connection.createStatement();
                var rows = statement.executeQuery("SELECT state, (SELECT COUNT(*) FROM crown_helmet_evidence), "
                        + "(SELECT COUNT(*) FROM crown_custody_audit WHERE transition='REVIEW_REQUIRED') "
                        + "FROM crown_custody WHERE singleton=1")) {
            assertTrue(rows.next()); assertEquals("REVIEW_REQUIRED", rows.getString(1));
            assertEquals(1, rows.getInt(2)); assertEquals(1, rows.getInt(3));
        }
    }

    @Test void shutdownInvalidationAfterDatabaseWriteCommitsReviewBeforeCompleting() throws Exception {
        var player = MockBukkit.getMock().addPlayer();
        player.getInventory().setHelmet(ItemStack.of(Material.NETHERITE_HELMET));
        var request = new ExclusiveCustodyReservation(UUID.randomUUID(), UUID.randomUUID(), player.getUniqueId(), 1,
                ExclusiveCustodyReservation.State.RESERVED);
        var factory = fixture(request); var metrics = MetricsPort.noOp();
        var runtime = new SQLiteStorageRuntime(factory, new MigrationRunner(),
                new BoundedDatabaseExecutor("paper-crown-shutdown", 16, metrics), metrics);
        assertEquals(StorageState.READ_WRITE, runtime.start().toCompletableFuture().join().state());
        var scheduled = new LinkedBlockingQueue<Runnable>();
        try {
            var guard = new PaperItemCodecThreadGuard(() -> true);
            var fence = new PaperCrownPreparationFence(guard);
            var coordinator = new PaperCrownHelmetPreparation(guard, fence,
                    new SQLiteCrownHelmetEvidenceStore(runtime), scheduled::add, id -> player);
            var outcome = coordinator.prepare(player, request).toCompletableFuture();
            var callback = scheduled.poll(10, TimeUnit.SECONDS);
            assertNotNull(callback);
            fence.invalidateAll();
            callback.run();
            assertEquals(PaperCrownHelmetPreparation.Result.HELD, outcome.get(10, TimeUnit.SECONDS));
            assertEquals(Material.NETHERITE_HELMET, player.getInventory().getHelmet().getType());
            try (Connection connection = factory.open(); var statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT state FROM crown_custody WHERE singleton=1")) {
                assertTrue(rows.next()); assertEquals("REVIEW_REQUIRED", rows.getString(1));
            }
        } finally { assertTrue(runtime.close(Duration.ofSeconds(5))); }
    }

    private SQLiteConnectionFactory fixture(ExclusiveCustodyReservation request) throws Exception {
        return fixture(temp, request);
    }

    static SQLiteConnectionFactory fixture(Path directory, ExclusiveCustodyReservation request) throws Exception {
        var factory = new SQLiteConnectionFactory(directory.resolve("paper-integration.db"), 5000);
        try (Connection connection = factory.open()) {
            new MigrationRunner().migrate(connection);
            if (request == null) return factory;
            try (var insert = connection.prepareStatement("INSERT INTO crown_custody VALUES (1,?,?,?,?,'RESERVED')")) {
                insert.setString(1, request.operationId().toString()); insert.setString(2, request.instanceId().toString());
                insert.setString(3, request.holderId().toString()); insert.setLong(4, request.generation()); insert.executeUpdate();
            }
            try (var insert = connection.prepareStatement("INSERT INTO crown_custody_audit VALUES (?,'RESERVED',?,?,?,0)")) {
                insert.setString(1, request.operationId().toString()); insert.setString(2, request.instanceId().toString());
                insert.setString(3, request.holderId().toString()); insert.setLong(4, request.generation()); insert.executeUpdate();
            }
        }
        return factory;
    }
}
