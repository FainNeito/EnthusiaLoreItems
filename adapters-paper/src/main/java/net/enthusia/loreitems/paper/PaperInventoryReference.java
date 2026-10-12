package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

/** Reload-safe reference to an inventory without retaining live Bukkit objects across ticks. */
sealed interface PaperInventoryReference
        permits PaperInventoryReference.PlayerMain,
                PaperInventoryReference.PlayerEnder,
                PaperInventoryReference.Block,
                PaperInventoryReference.EntityInventory {
    Optional<Inventory> resolve(Plugin plugin);

    static Optional<PaperInventoryReference> capture(Inventory inventory) {
        if (inventory == null) {
            return Optional.empty();
        }
        // Only identity is needed here; snapshots serialize the entire block inventory.
        InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof Player player) {
            return Optional.of(inventory instanceof PlayerInventory
                    ? new PlayerMain(player.getUniqueId())
                    : new PlayerEnder(player.getUniqueId()));
        }
        if (holder instanceof Entity entity) {
            return Optional.of(new EntityInventory(entity.getUniqueId()));
        }
        Location location = inventory.getLocation();
        if (location != null && location.getWorld() != null) {
            return Optional.of(new Block(
                    location.getWorld().getUID(),
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()));
        }
        return Optional.empty();
    }

    static String blockKey(Location location) {
        return Objects.requireNonNull(location.getWorld(), "location world")
                        .getKey().toString()
                + ':' + location.getBlockX()
                + ':' + location.getBlockY()
                + ':' + location.getBlockZ();
    }

    record PlayerMain(UUID playerId) implements PaperInventoryReference {
        @Override
        public Optional<Inventory> resolve(Plugin plugin) {
            Player player = plugin.getServer().getPlayer(playerId);
            return Optional.ofNullable(player).map(Player::getInventory);
        }
    }

    record PlayerEnder(UUID playerId) implements PaperInventoryReference {
        @Override
        public Optional<Inventory> resolve(Plugin plugin) {
            Player player = plugin.getServer().getPlayer(playerId);
            return Optional.ofNullable(player).map(Player::getEnderChest);
        }
    }

    record Block(UUID worldId, int x, int y, int z) implements PaperInventoryReference {
        @Override
        public Optional<Inventory> resolve(Plugin plugin) {
            World world = plugin.getServer().getWorld(worldId);
            if (world == null || !world.isChunkLoaded(x >> 4, z >> 4)) {
                return Optional.empty();
            }
            // Resolve fresh live contents, without copying/serializing the block entity.
            BlockState state = world.getBlockAt(x, y, z).getState(false);
            return state instanceof InventoryHolder holder
                    ? Optional.of(holder.getInventory())
                    : Optional.empty();
        }
    }

    record EntityInventory(UUID entityId) implements PaperInventoryReference {
        @Override
        public Optional<Inventory> resolve(Plugin plugin) {
            Entity entity = plugin.getServer().getEntity(entityId);
            return entity instanceof InventoryHolder holder
                    ? Optional.of(holder.getInventory())
                    : Optional.empty();
        }
    }
}
