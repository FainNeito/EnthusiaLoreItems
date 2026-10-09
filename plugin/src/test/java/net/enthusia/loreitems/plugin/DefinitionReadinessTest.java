package net.enthusia.loreitems.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import net.enthusia.loreitems.api.v1.LoreItemsServiceV1;
import net.enthusia.loreitems.application.FoundationConfiguration;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.StorageState;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreDefinitionRevision;
import net.enthusia.loreitems.domain.TemplateRevision;
import net.enthusia.loreitems.sqlite.BoundedDatabaseExecutor;
import net.enthusia.loreitems.sqlite.MigrationRunner;
import net.enthusia.loreitems.sqlite.SQLiteConnectionFactory;
import net.enthusia.loreitems.sqlite.SQLiteDefinitionRepository;
import net.enthusia.loreitems.sqlite.SQLiteStorageRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefinitionReadinessTest {
    @TempDir Path directory;

    @Test
    void activeDeletedMissingAndInvalidQueriesNeverWriteAndSurviveRestart() {
        Path database = directory.resolve("readiness.db");
        SQLiteStorageRuntime runtime = start(database);
        try {
            SQLiteDefinitionRepository definitions = new SQLiteDefinitionRepository(runtime);
            LoreDefinitionId id = new LoreDefinitionId(UUID.randomUUID());
            definitions.create(new LoreDefinition(id, new DefinitionKey("koth_blade"), "Blade",
                            new TemplateRevision(1), 1L, null),
                    new LoreDefinitionRevision(id, new TemplateRevision(1), 1, new byte[] {1}, 1L))
                    .toCompletableFuture().join();
            LoreItemsServiceV1 service = LoreItemsStorageServices.create(
                    runtime, FoundationConfiguration.defaults()).writable().deliveryService();
            long before = changes(runtime);
            assertTrue(service.isDefinitionActive(" KOTH_BLADE ").toCompletableFuture().join());
            assertFalse(service.isDefinitionActive("missing").toCompletableFuture().join());
            assertFalse(service.isDefinitionActive(null).toCompletableFuture().join());
            assertFalse(service.isDefinitionActive("invalid key").toCompletableFuture().join());
            assertEquals(before, changes(runtime), "A readiness query must write nothing");
            assertTrue(definitions.markDeleted(id, new TemplateRevision(1), Instant.ofEpochMilli(2L))
                    .toCompletableFuture().join());
            before = changes(runtime);
            assertFalse(service.isDefinitionActive("koth_blade").toCompletableFuture().join());
            assertEquals(before, changes(runtime));
        } finally {
            runtime.close(Duration.ofSeconds(5));
        }
        SQLiteStorageRuntime reopened = start(database);
        try {
            LoreItemsServiceV1 service = LoreItemsStorageServices.create(
                    reopened, FoundationConfiguration.defaults()).writable().deliveryService();
            assertFalse(service.isDefinitionActive("koth_blade").toCompletableFuture().join());
        } finally {
            reopened.close(Duration.ofSeconds(5));
        }
    }

    @Test
    void currentLifecycleDelegateAndClosedStorageFailClosed() {
        SQLiteStorageRuntime runtime = start(directory.resolve("lifecycle.db"));
        LoreItemsServiceV1 available = LoreItemsStorageServices.create(
                runtime, FoundationConfiguration.defaults()).writable().deliveryService();
        AtomicReference<LoreItemsServiceV1> delegate = new AtomicReference<>(available);
        LoreItemsServiceV1 registered = LoreItemsServiceDelegates.delegating(delegate);
        assertFalse(registered.isDefinitionActive("missing").toCompletableFuture().join());
        delegate.set(LoreItemsServiceDelegates.unavailable("stopping"));
        assertThrows(CompletionException.class,
                () -> registered.isDefinitionActive("missing").toCompletableFuture().join());
        runtime.close(Duration.ofSeconds(5));
        assertThrows(CompletionException.class,
                () -> available.isDefinitionActive("missing").toCompletableFuture().join());
    }

    private static long changes(SQLiteStorageRuntime runtime) {
        return runtime.execute(connection -> {
            try (var statement = connection.prepareStatement("SELECT total_changes()");
                 var result = statement.executeQuery()) {
                return result.getLong(1);
            }
        }).toCompletableFuture().join();
    }

    private static SQLiteStorageRuntime start(Path database) {
        MetricsPort metrics = MetricsPort.noOp();
        SQLiteStorageRuntime runtime = new SQLiteStorageRuntime(
                new SQLiteConnectionFactory(database, 5_000), new MigrationRunner(),
                new BoundedDatabaseExecutor("readiness-test", 32, metrics), metrics);
        assertEquals(StorageState.READ_WRITE, runtime.start().toCompletableFuture().join().state());
        return runtime;
    }
}
