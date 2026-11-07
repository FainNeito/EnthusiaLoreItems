package net.enthusia.loreitems.paper;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseArmorEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.*;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Short-lived protection for one online session while its Crown reservation is pending. */
final class PaperCrownExchangeWindow implements Listener {
    private final Plugin plugin;
    private final Player holder;
    private final Runnable abort;
    private BukkitTask deadline;

    PaperCrownExchangeWindow(Plugin plugin, Player holder, Runnable abort) {
        this.plugin = plugin;
        this.holder = holder;
        this.abort = abort;
    }

    void open() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        deadline = plugin.getServer().getScheduler().runTaskLater(plugin, abort, 100L);
    }

    void close() {
        HandlerList.unregisterAll(this);
        if (deadline != null) deadline.cancel();
    }

    private void protect(Object player, Cancellable event) {
        if (player == holder) event.setCancelled(true);
    }

    @EventHandler public void click(InventoryClickEvent event) { protect(event.getWhoClicked(), event); }
    @EventHandler public void drag(InventoryDragEvent event) { protect(event.getWhoClicked(), event); }
    @EventHandler public void interact(PlayerInteractEvent event) { protect(event.getPlayer(), event); }
    @EventHandler public void swap(PlayerSwapHandItemsEvent event) { protect(event.getPlayer(), event); }
    @EventHandler public void drop(PlayerDropItemEvent event) { protect(event.getPlayer(), event); }
    @EventHandler public void dispense(BlockDispenseArmorEvent event) { protect(event.getTargetEntity(), event); }
    @EventHandler public void manipulate(PlayerArmorStandManipulateEvent event) { protect(event.getPlayer(), event); }

    // Gameplay still proceeds. Any later admission callback for this window must be ignored.
    @EventHandler public void death(PlayerDeathEvent event) { if (event.getEntity() == holder) abort.run(); }
    @EventHandler public void quit(PlayerQuitEvent event) { if (event.getPlayer() == holder) abort.run(); }
    @EventHandler public void teleport(PlayerTeleportEvent event) { if (event.getPlayer() == holder) abort.run(); }
    @EventHandler public void damage(PlayerItemDamageEvent event) { if (event.getPlayer() == holder) abort.run(); }
    @EventHandler public void disable(PluginDisableEvent event) { if (event.getPlugin() == plugin) abort.run(); }
}
