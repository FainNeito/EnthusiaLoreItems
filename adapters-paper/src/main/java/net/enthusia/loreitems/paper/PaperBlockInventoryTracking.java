package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

/** Handles block-inventory transitions captured by the physical tracking listener. */
final class PaperBlockInventoryTracking {
    private final Plugin plugin;
    private final PaperPhysicalInventoryScanner scanner;
    private final PaperDeferredMainThreadActions deferredActions;
    private final int maxItemsPerScan;

    PaperBlockInventoryTracking(
            Plugin plugin,
            PaperPhysicalInventoryScanner scanner,
            PaperDeferredMainThreadActions deferredActions,
            int maxItemsPerScan) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scanner = Objects.requireNonNull(scanner, "scanner");
        this.deferredActions = Objects.requireNonNull(deferredActions, "deferredActions");
        this.maxItemsPerScan = maxItemsPerScan;
    }

    void onBlockBreak(BlockBreakEvent event) {
        BlockState state = event.getBlock().getState();
        if (state instanceof InventoryHolder holder) {
            scanBlockInventory(
                    holder.getInventory(),
                    TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                    "container-break",
                    new PaperScanLimit(maxItemsPerScan));
        }
    }

    void onBlockPlace(BlockPlaceEvent event) {
        if (!(event.getBlockPlaced().getState() instanceof InventoryHolder holder)) {
            return;
        }
        Set<LoreItemIdentity> identities = scanner.trackedIdentities(event.getItemInHand());
        if (identities.isEmpty()) {
            return;
        }
        Optional<PaperPhysicalInventorySnapshot> destination =
                PaperPhysicalInventorySnapshot.capture(holder.getInventory());
        deferredActions.schedule(() -> {
            submitMatchingIdentities(destination, identities, "container-place-destination");
            scanReference(destination, "container-place-destination");
        });
    }

    void onSpecialBlockInventoryInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null) {
            return;
        }
        BlockState state = event.getClickedBlock().getState();
        if (!(state instanceof InventoryHolder holder) || state instanceof Container) {
            return;
        }
        Inventory inventory = holder.getInventory();
        Optional<PaperPhysicalInventorySnapshot> snapshot =
                PaperPhysicalInventorySnapshot.capture(inventory);
        snapshot.ifPresent(reference -> scanner.scanInventory(
                inventory,
                reference.type(),
                reference.key(),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "special-container-interact-source"));
        Set<LoreItemIdentity> heldIdentities = scanner.trackedIdentities(event.getItem());
        deferredActions.schedule(() -> {
            submitMatchingIdentities(
                    snapshot, heldIdentities, "special-container-interact-destination");
            scanReference(snapshot, "special-container-interact-destination");
        });
    }

    private void scanReference(
            Optional<PaperPhysicalInventorySnapshot> snapshot, String source) {
        snapshot.ifPresent(reference -> reference.resolve(plugin).ifPresent(inventory ->
                scanner.scanInventory(
                        inventory,
                        reference.type(),
                        reference.key(),
                        TrackingObservationUseCase.Presence.PRESENT,
                        TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                        source)));
    }

    private void submitMatchingIdentities(
            Optional<PaperPhysicalInventorySnapshot> snapshot,
            Set<LoreItemIdentity> identities,
            String source) {
        if (identities.isEmpty()) {
            return;
        }
        snapshot.ifPresent(reference -> reference.resolve(plugin).ifPresent(inventory ->
                scanner.submitMatchingIdentities(
                        inventory,
                        reference.type(),
                        reference.key(),
                        identities,
                        source)));
    }

    private void scanBlockInventory(
            Inventory inventory,
            TrackingObservationUseCase.Presence presence,
            String source,
            PaperScanLimit limit) {
        Optional<PaperPhysicalInventorySnapshot> snapshot =
                PaperPhysicalInventorySnapshot.capture(inventory);
        if (snapshot.isEmpty() || !limit.tryConsume()) {
            return;
        }
        PaperPhysicalInventorySnapshot reference = snapshot.orElseThrow();
        scanner.scanInventory(
                inventory,
                reference.type(),
                reference.key(),
                presence,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                source,
                limit);
    }
}
