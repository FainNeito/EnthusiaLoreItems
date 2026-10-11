package net.enthusia.loreitems.paper;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import com.destroystokyo.paper.event.player.PlayerReadyArrowEvent;
import io.papermc.paper.event.block.BlockPreDispenseEvent;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import io.papermc.paper.event.player.PlayerItemFrameChangeEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.paper.PaperIdentityObservationScanner.ObservedCopy;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.BrewingStandFuelEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public final class PaperIdentityAnomalyListener implements Listener, AutoCloseable {
    private final Plugin plugin;
    private final PaperIdentityObservationScanner scanner;

    // Main-thread only: slot changes and the scheduled checks both run on the server thread.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private final Map<UUID, Set<Integer>> pendingSlotChecks = new HashMap<>();
    private boolean closed;

    public PaperIdentityAnomalyListener(Plugin plugin, int maxInFlight) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.scanner = new PaperIdentityObservationScanner(plugin, maxInFlight);
    }

    public void start() {
        if (closed) {
            throw new IllegalStateException("Identity anomaly listener is closed");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            scanner.scanPlayerInventory(player, "listener-start");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        scanner.scanPlayerInventory(event.getPlayer(), "player-join");
    }

    /**
     * Swaps (offhand, number keys) change two slots one after the other, so for an instant the
     * same item is in both. Comparing then would record a false duplicate, so each player's
     * changed slots are re-read and compared once the tick ends; a real duplicate is still there.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventorySlotChange(PlayerInventorySlotChangeEvent event) {
        ItemStack changed = event.getNewItemStack();
        if (closed || changed == null || changed.getType().isAir()) {
            return;
        }
        UUID playerId = event.getPlayer().getUniqueId();
        Set<Integer> slots = pendingSlotChecks.get(playerId);
        if (slots == null) {
            slots = new HashSet<>();
            pendingSlotChecks.put(playerId, slots);
            plugin.getServer().getScheduler().runTask(plugin, () -> checkChangedSlots(playerId));
        }
        slots.add(event.getSlot());
    }

    private void checkChangedSlots(UUID playerId) {
        Set<Integer> slots = pendingSlotChecks.remove(playerId);
        Player player = plugin.getServer().getPlayer(playerId);
        if (closed || slots == null || player == null) {
            return;
        }
        for (int slot : slots) {
            ItemStack current = player.getInventory().getItem(slot);
            if (current == null || current.getType().isAir()) {
                continue;
            }
            ObservedCopy copy = scanner.observe(
                    current,
                    PaperIdentityObservationScanner.playerLocation(player, "slot:" + slot),
                    "inventory-slot-change");
            if (copy != null) {
                scanner.compareWithInventory(player, copy, slot, "inventory-slot-change");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        scanner.scanPlayerInventory(player, "inventory-close-player");
        Inventory topInventory = event.getView().getTopInventory();
        if (topInventory != player.getInventory()) {
            scanner.scanStorageInventory(topInventory, player, "inventory-close-storage");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        LocationDescriptor sourceLocation = PaperIdentityObservationScanner.inventoryLocation(
                event.getSource(), "moving-item");
        if (sourceLocation == null) {
            return;
        }
        ObservedCopy moving = scanner.observe(
                event.getItem(), sourceLocation, "inventory-move-source");
        scanner.compareWithInventory(
                event.getDestination(), moving, "inventory-move-destination");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        Item item = event.getItem();
        ObservedCopy dropped = scanner.observe(
                item.getItemStack(),
                PaperIdentityObservationScanner.droppedLocation(item),
                "inventory-pickup-item");
        scanner.compareWithInventory(
                event.getInventory(), dropped, "inventory-pickup-destination");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDrop(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        ObservedCopy dropped = scanner.observe(
                item.getItemStack(),
                PaperIdentityObservationScanner.droppedLocation(item),
                "player-drop");
        scanner.compareWithInventory(event.getPlayer(), dropped, "player-drop");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Item item = event.getItem();
        ObservedCopy dropped = scanner.observe(
                item.getItemStack(),
                PaperIdentityObservationScanner.droppedLocation(item),
                "player-pickup");
        scanner.compareWithInventory(player, dropped, "player-pickup");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemFrameChange(PlayerItemFrameChangeEvent event) {
        ItemFrame frame = event.getItemFrame();
        LocationDescriptor frameLocation = PaperIdentityObservationScanner.displayLocation(
                LocationDescriptor.Type.ITEM_FRAME,
                frame,
                "frame-item");
        ObservedCopy displayed = scanner.observe(
                frame.getItem(),
                frameLocation,
                "item-frame-current");
        ItemStack involved = event.getItemStack();
        if (involved != null) {
            scanner.observe(
                    involved,
                    PaperIdentityObservationScanner.playerLocation(
                            event.getPlayer(), "item-frame-event"),
                    "item-frame-event");
        }
        scanner.compareWithInventory(
                event.getPlayer(), displayed, "item-frame-player-conflict");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        ArmorStand armorStand = event.getRightClicked();
        LocationDescriptor standLocation = PaperIdentityObservationScanner.displayLocation(
                LocationDescriptor.Type.ARMOR_STAND,
                armorStand,
                "equipment:" + event.getSlot().name().toLowerCase(java.util.Locale.ROOT));
        ObservedCopy displayed = scanner.observe(
                event.getArmorStandItem(),
                standLocation,
                "armor-stand-current");
        scanner.observe(
                event.getPlayerItem(),
                PaperIdentityObservationScanner.playerLocation(
                        event.getPlayer(), "armor-stand-event"),
                "armor-stand-event");
        scanner.compareWithInventory(
                event.getPlayer(), displayed, "armor-stand-player-conflict");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDespawn(ItemDespawnEvent event) {
        Item item = event.getEntity();
        scanner.observe(
                item.getItemStack(),
                PaperIdentityObservationScanner.droppedLocation(item),
                "item-despawn");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemCombust(EntityCombustEvent event) {
        if (event.getEntity() instanceof Item item) {
            scanner.observe(
                    item.getItemStack(),
                    PaperIdentityObservationScanner.droppedLocation(item),
                    "item-combust");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemMerge(ItemMergeEvent event) {
        scanner.observe(
                event.getEntity().getItemStack(),
                PaperIdentityObservationScanner.droppedLocation(event.getEntity()),
                "item-merge-source");
        scanner.observe(
                event.getTarget().getItemStack(),
                PaperIdentityObservationScanner.droppedLocation(event.getTarget()),
                "item-merge-target");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item) {
            scanner.observe(
                    item.getItemStack(),
                    PaperIdentityObservationScanner.droppedLocation(item),
                    "item-damage");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDurabilityDamage(PlayerItemDamageEvent event) {
        scanner.observe(
                event.getItem(),
                PaperIdentityObservationScanner.playerLocation(
                        event.getPlayer(), "durability-item"),
                "durability-damage");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsume(PlayerItemConsumeEvent event) {
        scanner.observe(
                event.getItem(),
                PaperIdentityObservationScanner.playerLocation(
                        event.getPlayer(), "consumed-item"),
                "item-consume");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryResultClick(InventoryClickEvent event) {
        if (event.getSlotType() != InventoryType.SlotType.RESULT) {
            return;
        }
        Inventory inventory = event.getView().getTopInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null) {
                scanner.observe(
                        item,
                        PaperIdentityObservationScanner.playerLocation(
                                event.getWhoClicked(), "result-input:" + slot),
                        "inventory-result");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCook(BlockCookEvent event) {
        scanner.observe(
                event.getSource(),
                PaperIdentityObservationScanner.blockLocation(
                        event.getBlock(), "cook-source"),
                "block-cook");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFurnaceFuel(FurnaceBurnEvent event) {
        scanner.observe(
                event.getFuel(),
                PaperIdentityObservationScanner.blockLocation(
                        event.getBlock(), "furnace-fuel"),
                "furnace-fuel");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBrewingFuel(BrewingStandFuelEvent event) {
        scanner.observe(
                event.getFuel(),
                PaperIdentityObservationScanner.blockLocation(
                        event.getBlock(), "brewing-fuel"),
                "brewing-fuel");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBrew(BrewEvent event) {
        Inventory inventory = event.getContents();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item != null) {
                scanner.observe(
                        item,
                        PaperIdentityObservationScanner.blockLocation(
                                event.getBlock(), "brewing-slot:" + slot),
                        "brew");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockPlace(BlockPlaceEvent event) {
        scanner.observe(
                event.getItemInHand(),
                PaperIdentityObservationScanner.playerLocation(
                        event.getPlayer(), "placed-item"),
                "block-place");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onHangingPlace(HangingPlaceEvent event) {
        ItemStack item = event.getItemStack();
        if (item != null) {
            scanner.observe(
                    item,
                    PaperIdentityObservationScanner.hangingSourceLocation(event),
                    "hanging-place");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        observeBucket(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBucketFill(PlayerBucketFillEvent event) {
        observeBucket(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileLaunch(PlayerLaunchProjectileEvent event) {
        scanner.observe(
                event.getItemStack(),
                PaperIdentityObservationScanner.playerLocation(
                        event.getPlayer(), "launched-projectile"),
                "projectile-launch");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onReadyArrow(PlayerReadyArrowEvent event) {
        scanner.observe(
                event.getArrow(),
                PaperIdentityObservationScanner.playerLocation(
                        event.getPlayer(), "ready-arrow"),
                "ready-arrow");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onShootBow(EntityShootBowEvent event) {
        ItemStack consumable = event.getConsumable();
        if (consumable != null) {
            scanner.observe(
                    consumable,
                    PaperIdentityObservationScanner.shooterLocation(event),
                    "bow-consumable");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDispense(BlockPreDispenseEvent event) {
        scanner.observe(
                event.getItemStack(),
                PaperIdentityObservationScanner.blockLocation(
                        event.getBlock(), "dispense-source"),
                "block-dispense");
    }

    private void observeBucket(PlayerBucketEvent event) {
        EquipmentSlot hand = event.getHand();
        ItemStack item = hand == EquipmentSlot.OFF_HAND
                ? event.getPlayer().getInventory().getItemInOffHand()
                : event.getPlayer().getInventory().getItemInMainHand();
        scanner.observe(
                item,
                PaperIdentityObservationScanner.playerLocation(
                        event.getPlayer(), "bucket:" + hand.name()),
                "bucket-use");
    }

    public CompletionStage<Void> quiescence() {
        return scanner.quiescence();
    }

    @Override
    public void close() {
        closed = true;
        pendingSlotChecks.clear();
        scanner.close();
        HandlerList.unregisterAll(this);
    }
}
