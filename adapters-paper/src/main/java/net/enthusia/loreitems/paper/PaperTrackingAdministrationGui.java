package net.enthusia.loreitems.paper;

import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.BACK;
import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.NEXT;
import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.PREVIOUS;
import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.selectable;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstance;
import net.enthusia.loreitems.domain.LoreInstanceId;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

/** Paginated staff browser and explicit duplicate-location confirmation flow. */
public final class PaperTrackingAdministrationGui implements Listener {
    private static final String ADMINISTRATION_UNAVAILABLE =
            "Lore-item administration is unavailable.";
    private static final int FIRST_PAGE = 1;
    private static final int CONTENT = 45;
    private static final int CONFIRM = 15;
    private static final int CANCEL = 11;
    private static final int MAX_QUERIES = 32;

    private final Plugin plugin;
    private final IntSupplier pageSizeSupplier;
    private final Semaphore queryCapacity = new Semaphore(MAX_QUERIES);
    private final PaperTrackingAdministrationRenderer renderer;
    private final PaperTemplateEditorManager templateEditor;

    public PaperTrackingAdministrationGui(
            Plugin plugin,
            IntSupplier pageSizeSupplier,
            PaperTemplateEditorManager templateEditor) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.pageSizeSupplier = Objects.requireNonNull(pageSizeSupplier, "pageSizeSupplier");
        this.templateEditor = Objects.requireNonNull(templateEditor, "templateEditor");
        this.renderer = new PaperTrackingAdministrationRenderer(plugin);
        pageRequest(FIRST_PAGE);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void openDefinitions(UUID playerId, int pageNumber) {
        runMain(() -> openDefinitionsMain(playerId, pageNumber));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof PaperTrackingAdministrationView view)) {
            return;
        }
        event.setCancelled(true);
        handleClick(event, view);
    }

    private void handleClick(
            InventoryClickEvent event, PaperTrackingAdministrationView view) {
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        Player player = authorizedClicker(event, view);
        int slot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        if (player == null || slot < 0 || slot >= topSize) {
            return;
        }
        if (event.isShiftClick()) {
            PaperTrackingTechnicalDetails.show(plugin, player, view, slot);
            return;
        }
        if (view.screen == PaperTrackingAdministrationView.Screen.INSTANCES
                && slot < view.instanceIds.size()
                && !event.isLeftClick()) {
            return;
        }
        dispatchClick(player, view, slot);
    }

    private Player authorizedClicker(
            InventoryClickEvent event, PaperTrackingAdministrationView view) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return null;
        }
        return canUseScreen(player, view.screen) ? player : null;
    }

    static boolean canUseScreen(
            CommandSender sender, PaperTrackingAdministrationView.Screen screen) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(screen, "screen");
        return screen == PaperTrackingAdministrationView.Screen.DEFINITIONS
                ? LoreItemsAdministrationCommandExecutor.canBrowse(sender)
                : sender.hasPermission(LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION);
    }

    private void dispatchClick(
            Player player, PaperTrackingAdministrationView view, int slot) {
        switch (view.screen) {
            case DEFINITIONS -> clickDefinitions(player, view, slot);
            case INSTANCES -> clickInstances(player, view, slot);
            case EVIDENCE -> clickEvidence(player, view, slot);
            case CONFIRMATION -> clickConfirmation(player, view, slot);
            default -> throw new IllegalStateException("Unknown tracking administration screen");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof PaperTrackingAdministrationView)) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot < topSize)) {
            event.setCancelled(true);
        }
    }

    private void openDefinitionsMain(UUID playerId, int pageNumber) {
        LoreItemsAdministrationUseCase useCase = resolveUseCase();
        if (useCase == null) {
            messagePlayer(playerId, ADMINISTRATION_UNAVAILABLE);
            return;
        }
        PageRequest request = pageRequest(pageNumber);
        submit(
                playerId,
                "definition browser",
                () -> useCase.listDefinitions(request),
                page -> showDefinitions(playerId, pageNumber, page));
    }

    private void clickDefinitions(
            Player player, PaperTrackingAdministrationView view, int slot) {
        if (slot == PREVIOUS && view.pageNumber > FIRST_PAGE) {
            openDefinitionsMain(player.getUniqueId(), view.pageNumber - FIRST_PAGE);
        } else if (slot == NEXT && view.hasMore) {
            openDefinitionsMain(player.getUniqueId(), view.pageNumber + FIRST_PAGE);
        } else if (slot < view.definitionIds.size()) {
            templateEditor.openManagement(
                    player.getUniqueId(), view.definitionIds.get(slot), view.pageNumber);
        }
    }

    void openInstances(
            UUID playerId, LoreDefinitionId definitionId, int pageNumber) {
        int definitionPageNumber = FIRST_PAGE;
        Player player = plugin.getServer().getPlayer(playerId);
        if (player != null
                && player.getOpenInventory().getTopInventory().getHolder()
                        instanceof PaperTemplateEditorView view
                && view.screen == PaperTemplateEditorView.Screen.MANAGEMENT
                && view.definitionId().equals(definitionId)) {
            definitionPageNumber = view.returnPage;
        }
        openInstances(playerId, definitionId, definitionPageNumber, pageNumber);
    }

    private void openInstances(
            UUID playerId,
            LoreDefinitionId definitionId,
            int definitionPageNumber,
            int pageNumber) {
        LoreItemsAdministrationUseCase useCase = resolveUseCase();
        if (useCase == null) {
            messagePlayer(playerId, ADMINISTRATION_UNAVAILABLE);
            return;
        }
        submit(
                playerId,
                "instance browser",
                () -> useCase.listInstances(definitionId, pageRequest(pageNumber)),
                page -> showInstances(
                        playerId, definitionId, definitionPageNumber, pageNumber, page));
    }

    private void clickInstances(
            Player player, PaperTrackingAdministrationView view, int slot) {
        UUID playerId = player.getUniqueId();
        if (slot == BACK) {
            templateEditor.openManagement(
                    playerId, view.definitionId, view.definitionPageNumber);
        } else if (slot == PREVIOUS && view.pageNumber > FIRST_PAGE) {
            openInstances(
                    playerId,
                    view.definitionId,
                    view.definitionPageNumber,
                    view.pageNumber - FIRST_PAGE);
        } else if (slot == NEXT && view.hasMore) {
            openInstances(
                    playerId,
                    view.definitionId,
                    view.definitionPageNumber,
                    view.pageNumber + FIRST_PAGE);
        } else if (slot < view.instanceIds.size()) {
            openEvidence(
                    playerId,
                    view.definitionId,
                    view.definitionPageNumber,
                    view.pageNumber,
                    view.instanceIds.get(slot),
                    FIRST_PAGE);
        }
    }

    private void openEvidence(
            UUID playerId,
            LoreDefinitionId definitionId,
            int definitionPageNumber,
            int instancePageNumber,
            LoreInstanceId instanceId,
            int pageNumber) {
        LoreItemsAdministrationUseCase useCase = resolveUseCase();
        if (useCase == null) {
            messagePlayer(playerId, ADMINISTRATION_UNAVAILABLE);
            return;
        }
        PageRequest request = pageRequest(pageNumber);
        submit(
                playerId,
                "instance evidence",
                () -> PaperTrackingEvidenceQuery.load(useCase, instanceId, request, CONTENT),
                data -> showEvidence(
                        playerId,
                        definitionId,
                        definitionPageNumber,
                        instancePageNumber,
                        instanceId,
                        pageNumber,
                        data));
    }

    private void clickEvidence(
            Player player, PaperTrackingAdministrationView view, int slot) {
        UUID playerId = player.getUniqueId();
        if (slot == PaperTrackingAdministrationItems.CURRENT_LOCATION) {
            openEvidence(playerId, view.definitionId, view.definitionPageNumber,
                    view.parentPageNumber, view.instanceId, view.pageNumber);
            return;
        }
        if (slot == BACK) {
            openInstances(
                    playerId,
                    view.definitionId,
                    view.definitionPageNumber,
                    view.parentPageNumber);
            return;
        }
        if (slot == PREVIOUS && view.pageNumber > FIRST_PAGE) {
            openEvidence(
                    playerId,
                    view.definitionId,
                    view.definitionPageNumber,
                    view.parentPageNumber,
                    view.instanceId,
                    view.pageNumber - FIRST_PAGE);
            return;
        }
        if (slot == NEXT && view.hasMore) {
            openEvidence(
                    playerId,
                    view.definitionId,
                    view.definitionPageNumber,
                    view.parentPageNumber,
                    view.instanceId,
                    view.pageNumber + FIRST_PAGE);
            return;
        }
        if (slot >= view.observations.size() || view.duplicate == null) {
            return;
        }
        ObservationChoice selected = view.observations.get(slot);
        if (selectable(selected, view.duplicate)) {
            showConfirmation(player, view, selected);
        }
    }

    private void showConfirmation(
            Player player, PaperTrackingAdministrationView view, ObservationChoice observation) {
        Inventory inventory = renderer.confirmationInventory(
                view.definitionId, view.definitionPageNumber, view.parentPageNumber,
                view.instanceId, view.duplicate, observation, view.pageNumber);
        openLater(player.getUniqueId(), inventory);
    }

    private void clickConfirmation(
            Player player, PaperTrackingAdministrationView view, int slot) {
        UUID playerId = player.getUniqueId();
        if (slot == CANCEL) {
            openEvidence(
                    playerId,
                    view.definitionId,
                    view.definitionPageNumber,
                    view.parentPageNumber,
                    view.instanceId,
                    view.pageNumber);
            return;
        }
        if (slot != CONFIRM) {
            return;
        }
        LoreItemsAdministrationUseCase useCase = resolveUseCase();
        if (useCase == null) {
            LoreItemsMessages.send(player, ADMINISTRATION_UNAVAILABLE);
            return;
        }
        LoreItemsAdministrationUseCase.DuplicateResolutionRequest request =
                PaperTrackingAdministrationRequests.duplicateResolution(playerId, view);
        if (request == null) {
            LoreItemsMessages.send(player, "The selected duplicate evidence is no longer valid.");
            return;
        }
        closeLater(playerId);
        submit(
                playerId,
                "duplicate resolution",
                () -> useCase.resolveDuplicate(request),
                result -> {
                    messagePlayer(playerId, result.detail());
                    openEvidence(
                            playerId,
                            view.definitionId,
                            view.definitionPageNumber,
                            view.parentPageNumber,
                            view.instanceId,
                            view.pageNumber);
                });
    }

    private void showDefinitions(
            UUID playerId, int pageNumber, Page<LoreDefinition> page) {
        Player player = authorizedDefinitionPlayer(playerId);
        if (player != null) {
            renderer.showDefinitions(player, pageNumber, page);
        }
    }

    private void showInstances(
            UUID playerId,
            LoreDefinitionId definitionId,
            int definitionPageNumber,
            int pageNumber,
            Page<LoreInstance> page) {
        Player player = authorizedPlayer(playerId);
        if (player != null) {
            renderer.showInstances(
                    player, definitionId, definitionPageNumber, pageNumber, page);
        }
    }

    private void showEvidence(
            UUID playerId,
            LoreDefinitionId definitionId,
            int definitionPageNumber,
            int instancePageNumber,
            LoreInstanceId instanceId,
            int pageNumber,
            EvidenceData data) {
        Player player = authorizedPlayer(playerId);
        if (player != null && data != null) {
            renderer.showEvidence(
                    player,
                    definitionId,
                    definitionPageNumber,
                    instancePageNumber,
                    instanceId,
                    pageNumber,
                    data);
        }
    }

    private void openLater(UUID playerId, Inventory inventory) {
        scheduleNextTick(() -> {
            Player target = authorizedPlayer(playerId);
            if (target != null) {
                target.openInventory(inventory);
            }
        });
    }

    private void closeLater(UUID playerId) {
        scheduleNextTick(() -> {
            Player target = authorizedPlayer(playerId);
            if (target != null) {
                target.closeInventory();
            }
        });
    }

    private LoreItemsAdministrationUseCase resolveUseCase() {
        return plugin.getServer().getServicesManager()
                .load(LoreItemsAdministrationUseCase.class);
    }

    private <T> void submit(
            UUID playerId,
            String operation,
            Supplier<CompletionStage<T>> query,
            Consumer<T> success) {
        if (!queryCapacity.tryAcquire()) {
            messagePlayer(playerId, "Too many lore-item administration queries are active.");
            return;
        }
        CompletionStage<T> stage;
        try {
            stage = Objects.requireNonNull(query.get(), operation + " returned null");
        } catch (RuntimeException exception) {
            queryCapacity.release();
            handleFailure(playerId, operation, exception);
            return;
        }
        stage.whenComplete((value, failure) -> {
            queryCapacity.release();
            finishSubmission(playerId, operation, success, value, failure);
        });
    }

    private <T> void finishSubmission(
            UUID playerId,
            String operation,
            Consumer<T> success,
            T value,
            Throwable failure) {
        if (failure != null) {
            handleFailure(playerId, operation, failure);
        } else if (value == null) {
            handleFailure(
                    playerId,
                    operation,
                    new IllegalStateException(operation + " returned no result"));
        } else {
            scheduleNextTick(() -> success.accept(value));
        }
    }

    private void handleFailure(UUID playerId, String operation, Throwable failure) {
        plugin.getLogger().log(
                Level.SEVERE,
                "Could not complete lore-item " + operation + '.',
                unwrap(failure));
        messagePlayer(playerId, "The lore-item " + operation + " failed.");
    }

    private void messagePlayer(UUID playerId, String message) {
        runMain(() -> {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                LoreItemsMessages.send(player, message);
            }
        });
    }

    private void runMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        scheduleNextTick(action);
    }

    private void scheduleNextTick(Runnable action) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, action);
        } catch (IllegalPluginAccessException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule lore-item GUI work during shutdown.",
                    exception);
        }
    }

    private Player authorizedDefinitionPlayer(UUID playerId) {
        Player player = plugin.getServer().getPlayer(playerId);
        return player != null
                        && canUseScreen(player, PaperTrackingAdministrationView.Screen.DEFINITIONS)
                ? player
                : null;
    }

    private Player authorizedPlayer(UUID playerId) {
        Player player = plugin.getServer().getPlayer(playerId);
        return player != null
                        && player.hasPermission(
                                LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION)
                ? player
                : null;
    }

    private PageRequest pageRequest(int pageNumber) {
        return PaperTrackingAdministrationRequests.page(
                pageNumber, pageSizeSupplier.getAsInt(), CONTENT);
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException exception && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }
}
