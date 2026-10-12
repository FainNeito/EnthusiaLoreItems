package net.enthusia.loreitems.paper;

import java.util.Optional;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

/** Immutable reference and durable location identity for a physical inventory. */
record PaperPhysicalInventorySnapshot(
        PaperInventoryReference reference,
        net.enthusia.loreitems.domain.LocationDescriptor.Type type,
        String key) {
    static Optional<PaperPhysicalInventorySnapshot> capture(Inventory inventory) {
        Optional<PaperInventoryReference> reference = PaperInventoryReference.capture(inventory);
        if (reference.isEmpty()) {
            return Optional.empty();
        }
        InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof Player player) {
            boolean main = inventory instanceof PlayerInventory;
            return Optional.of(new PaperPhysicalInventorySnapshot(
                    reference.orElseThrow(),
                    main
                            ? net.enthusia.loreitems.domain.LocationDescriptor.Type.PLAYER_INVENTORY
                            : net.enthusia.loreitems.domain.LocationDescriptor.Type.PLAYER_ENDER_CHEST,
                    "player:" + player.getUniqueId()));
        }
        if (holder instanceof Entity entity) {
            return Optional.of(new PaperPhysicalInventorySnapshot(
                    reference.orElseThrow(),
                    net.enthusia.loreitems.domain.LocationDescriptor.Type.BLOCK_CONTAINER,
                    entity.getWorld().getKey() + ":entity:" + entity.getUniqueId()));
        }
        Location location = inventory.getLocation();
        if (location != null && location.getWorld() != null) {
            return Optional.of(new PaperPhysicalInventorySnapshot(
                    reference.orElseThrow(),
                    net.enthusia.loreitems.domain.LocationDescriptor.Type.BLOCK_CONTAINER,
                    PaperInventoryReference.blockKey(location)));
        }
        return Optional.empty();
    }

    Optional<Inventory> resolve(Plugin plugin) {
        return reference.resolve(plugin);
    }
}
