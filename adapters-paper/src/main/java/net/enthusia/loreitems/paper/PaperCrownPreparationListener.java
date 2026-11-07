package net.enthusia.loreitems.paper;

import java.util.Objects;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** Conservative invalidation only; listeners do not cancel actions or lock equipment. */
final class PaperCrownPreparationListener implements Listener {
    private final PaperCrownPreparationFence fence;

    PaperCrownPreparationListener(PaperCrownPreparationFence fence) {
        this.fence = Objects.requireNonNull(fence, "fence");
    }

    @EventHandler public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) fence.invalidate(player.getUniqueId());
    }
    @EventHandler public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) fence.invalidate(player.getUniqueId());
    }
    @EventHandler public void onInteract(PlayerInteractEvent event) { fence.invalidate(event.getPlayer().getUniqueId()); }
    @EventHandler public void onDrop(PlayerDropItemEvent event) { fence.invalidate(event.getPlayer().getUniqueId()); }
    @EventHandler public void onBreak(PlayerItemBreakEvent event) { fence.invalidate(event.getPlayer().getUniqueId()); }
    @EventHandler public void onItemDamage(PlayerItemDamageEvent event) { fence.invalidate(event.getPlayer().getUniqueId()); }
    @EventHandler public void onSwap(PlayerSwapHandItemsEvent event) { fence.invalidate(event.getPlayer().getUniqueId()); }
    @EventHandler public void onTeleport(PlayerTeleportEvent event) { fence.invalidate(event.getPlayer().getUniqueId()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { fence.invalidate(event.getPlayer().getUniqueId()); }
    @EventHandler public void onDeath(PlayerDeathEvent event) { fence.invalidate(event.getEntity().getUniqueId()); }
}
