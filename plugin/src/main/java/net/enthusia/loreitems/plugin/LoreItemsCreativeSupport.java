package net.enthusia.loreitems.plugin;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.enthusia.loreitems.api.v1.LoreItemsServiceV1;
import net.enthusia.loreitems.application.DefinitionRepository;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.paper.LoreItemsCommandExecutor;
import net.enthusia.loreitems.paper.PaperCreativeCopyController;
import net.enthusia.loreitems.paper.PaperTrackedItemProtectionListener;
import net.enthusia.loreitems.sqlite.SQLiteCreativeInventoryLossStore;
import net.enthusia.loreitems.sqlite.SQLiteDefinitionRepository;
import net.enthusia.loreitems.sqlite.SQLiteStorageRuntime;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns bounded Creative copy requests and tentative disappearance evidence. */
final class LoreItemsCreativeSupport {
    private final JavaPlugin plugin;
    private final LoreItemsServiceV1 delivery;
    private final Consumer<UUID> wakeup;
    private final AtomicReference<DefinitionRepository> definitions = new AtomicReference<>();
    private final AtomicReference<SQLiteCreativeInventoryLossStore> missing = new AtomicReference<>();
    private PaperCreativeCopyController controller;

    LoreItemsCreativeSupport(
            JavaPlugin plugin, LoreItemsServiceV1 delivery, Consumer<UUID> wakeup) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.delivery = Objects.requireNonNull(delivery, "delivery");
        this.wakeup = Objects.requireNonNull(wakeup, "wakeup");
    }

    void install(LoreItemsCommandExecutor commands) {
        controller = new PaperCreativeCopyController(plugin, definitions::get, delivery, wakeup);
        commands.setCreativeCopyController(controller);
    }

    void install(PaperTrackedItemProtectionListener listener) {
        listener.setCreativeCopyController(
                Objects.requireNonNull(controller, "creative copy controller"));
        listener.setCreativeLossObserver(this::recordPossibleLoss);
    }

    private void recordPossibleLoss(UUID playerId, LoreItemIdentity identity) {
        SQLiteCreativeInventoryLossStore store = missing.get();
        if (store == null) {
            return;
        }
        store.record(playerId, identity).whenComplete((recorded, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(Level.WARNING,
                        "Could not persist creative inventory loss evidence.", failure);
            }
        });
    }

    void activate(SQLiteStorageRuntime runtime) {
        definitions.set(new SQLiteDefinitionRepository(runtime));
        missing.set(new SQLiteCreativeInventoryLossStore(runtime));
    }

    void clear() {
        if (controller != null) {
            controller.clear();
        }
        definitions.set(null);
        missing.set(null);
    }
}
