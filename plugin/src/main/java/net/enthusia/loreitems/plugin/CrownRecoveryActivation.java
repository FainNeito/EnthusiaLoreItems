package net.enthusia.loreitems.plugin;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import net.enthusia.loreitems.api.v1.CrownFirstAwardV1;
import net.enthusia.loreitems.api.v1.CrownReclamationV1;
import net.enthusia.loreitems.api.v1.CrownRecoveryEventsV1;
import net.enthusia.loreitems.application.CrownEquipmentRecovery.ReclamationReservation;
import net.enthusia.loreitems.paper.LoreItemsCommandExecutor;
import net.enthusia.loreitems.paper.PaperCrownFirstAward;
import net.enthusia.loreitems.paper.PaperCrownInspectionCommand;
import net.enthusia.loreitems.paper.PaperCrownJoinRecovery;
import net.enthusia.loreitems.paper.PaperCrownOfflineReclaimer;
import net.enthusia.loreitems.sqlite.SQLiteCrownHelmetEvidenceStore;
import net.enthusia.loreitems.sqlite.SQLiteDefinitionRepository;
import net.enthusia.loreitems.sqlite.SQLiteStorageRuntime;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Crown custody services for one plugin enable. The offline reclaimer exists from enable so logins are
 * fenced before storage starts; every service re-checks that its storage runtime is still current.
 */
final class CrownRecoveryActivation implements AutoCloseable {
    private final JavaPlugin plugin;
    private final PaperCrownOfflineReclaimer offline;

    CrownRecoveryActivation(JavaPlugin plugin, BooleanSupplier running) {
        this.plugin = plugin;
        this.offline = plugin.getConfig().getBoolean("crown-reclamation-offline-enabled", false)
                ? new PaperCrownOfflineReclaimer(plugin, running) : null;
    }

    /** {@code current} is true while the plugin is running on the storage runtime being activated. */
    void activate(SQLiteStorageRuntime runtime, BooleanSupplier current) {
        var store = new SQLiteCrownHelmetEvidenceStore(runtime);
        try {
            if (store.holdInterruptedOnStartup().toCompletableFuture().join()) {
                plugin.getLogger().warning("Interrupted Crown custody is held for review.");
            }
            var recovery = new PaperCrownJoinRecovery(plugin, store, current);
            plugin.getServer().getScheduler().runTask(plugin, () -> registerServices(runtime, current, store, recovery));
        } catch (RuntimeException failure) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Crown recovery unavailable; escrow retained.", failure);
        }
    }

    private void registerServices(SQLiteStorageRuntime runtime, BooleanSupplier current, SQLiteCrownHelmetEvidenceStore store,
            PaperCrownJoinRecovery recovery) {
        recovery.register();
        if (offline != null) offline.start(store);
        plugin.getServer().getServicesManager().register(CrownReclamationV1.class,
                reclamation(current, store, recovery), plugin, ServicePriority.Normal);
        plugin.getServer().getServicesManager().register(CrownRecoveryEventsV1.class,
                recoveryEvents(current, store, recovery), plugin, ServicePriority.Normal);
        var command = plugin.getCommand("loreitems");
        if (command != null && command.getExecutor() instanceof LoreItemsCommandExecutor executor) {
            executor.setCrownInspection(new PaperCrownInspectionCommand(plugin, store, current));
        }
        if (!current.getAsBoolean() || !plugin.getConfig().getBoolean("crown-first-award-enabled")) return;
        registerFirstAward(runtime, current, store);
    }

    private static boolean reserved(ReclamationReservation result) {
        return result == ReclamationReservation.RESERVED || result == ReclamationReservation.ALREADY_RESERVED;
    }

    private CrownReclamationV1 reclamation(BooleanSupplier current, SQLiteCrownHelmetEvidenceStore store,
            PaperCrownJoinRecovery recovery) {
        return (operation, instance, holder, generation) -> {
            if (!current.getAsBoolean()) return CompletableFuture.completedFuture(CrownReclamationV1.Result.UNAVAILABLE);
            return store.reserveReclamation(operation, instance, holder, generation).thenApply(result -> {
                if (reserved(result))
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (offline != null && plugin.getServer().getPlayer(holder) == null) offline.reclaim(holder);
                        else recovery.reclaim(holder);
                    });
                return CrownReclamationV1.Result.valueOf(result.name());
            });
        };
    }

    private CrownRecoveryEventsV1 recoveryEvents(BooleanSupplier current, SQLiteCrownHelmetEvidenceStore store,
            PaperCrownJoinRecovery recovery) {
        final boolean recoveryEventsEnabled = plugin.getConfig().getBoolean("crown-recovery-events-enabled", false);
        return new CrownRecoveryEventsV1() {
            public CompletionStage<java.util.Optional<Status>> current() {
                if (!current.getAsBoolean()) return CompletableFuture.completedFuture(java.util.Optional.empty());
                return store.inspectCustody().thenApply(snapshot -> snapshot.filter(s -> s.state().equals("EQUIPPED"))
                        .map(s -> new Status(s.instance(), s.recordedHolder(), s.generation(), s.reclamationOperation().orElse(null),
                                s.reclamationState().equals("HELD"), s.transfer().isPresent(), recoveryEventsEnabled)));
            }
            public CompletionStage<Result> deliver(UUID operation, UUID instance, UUID holder, long generation, UUID match,
                    UUID winner) {
                if (!current.getAsBoolean() || !recoveryEventsEnabled) return CompletableFuture.completedFuture(Result.UNAVAILABLE);
                return store.reserveRecoveryWinner(operation, instance, holder, generation, match, winner).thenApply(result -> {
                    if (reserved(result)) recovery.deliver(winner);
                    return Result.valueOf(result.name());
                });
            }
        };
    }

    private void registerFirstAward(SQLiteStorageRuntime runtime, BooleanSupplier current, SQLiteCrownHelmetEvidenceStore store) {
        try {
            var provider = new PaperCrownFirstAward(plugin, new SQLiteDefinitionRepository(runtime), store, current,
                    UUID.fromString(plugin.getConfig().getString("crown-first-award-definition-id", "")),
                    plugin.getConfig().getLong("crown-first-award-revision"));
            plugin.getServer().getServicesManager().register(CrownFirstAwardV1.class, provider, plugin, ServicePriority.Normal);
        } catch (IllegalArgumentException invalid) {
            plugin.getLogger().warning("First Crown award requires a valid definition UUID and positive revision.");
        }
    }

    @Override
    public void close() {
        if (offline != null) offline.close();
    }
}
