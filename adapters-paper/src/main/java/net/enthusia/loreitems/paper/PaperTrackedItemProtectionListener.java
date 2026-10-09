package net.enthusia.loreitems.paper;

import static net.enthusia.loreitems.paper.PaperTrackedItemInteractionRules.losesIdentityOnInteraction;
import static net.enthusia.loreitems.paper.PaperTrackedItemInteractionRules.losesIdentityOnEntityInteraction;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import com.destroystokyo.paper.event.player.PlayerReadyArrowEvent;
import io.papermc.paper.event.block.BlockPreDispenseEvent;
import io.papermc.paper.event.entity.EntityCompostItemEvent;
import io.papermc.paper.event.entity.EntityDamageItemEvent;
import io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent;
import io.papermc.paper.event.player.PlayerFlowerPotManipulateEvent;
import io.papermc.paper.event.player.PlayerPickBlockEvent;
import io.papermc.paper.event.player.PlayerPickEntityEvent;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.VoidLossUseCase;
import org.bukkit.Material;
import org.bukkit.block.Crafter;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Piglin;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockCookEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.BrewEvent;
import org.bukkit.event.inventory.BrewingStandFuelEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

public final class PaperTrackedItemProtectionListener implements Listener, AutoCloseable {
    private final Plugin plugin;
    private final PaperItemIdentityCodec identityCodec;
    private final PaperTrackedItemCollector itemCollector = new PaperTrackedItemCollector();
    private final PaperCreativeIdentityProtection creativeIdentityProtection;
    private PaperCreativeCopyController creativeCopyController;
    private BiConsumer<UUID, LoreItemIdentity> creativeLossObserver;
    private final PaperVoidLossCoordinator voidLossCoordinator;
    private final BooleanSupplier sharedContainersAllowedSupplier;

    private volatile boolean closed;

    public PaperTrackedItemProtectionListener(
            Plugin plugin,
            Supplier<VoidLossUseCase> useCaseSupplier,
            int maxInFlight) {
        this(
                plugin,
                useCaseSupplier,
                () -> maxInFlight,
                () -> plugin.getConfig().getBoolean("shared-containers-allowed", true));
    }

    public PaperTrackedItemProtectionListener(
            Plugin plugin,
            Supplier<VoidLossUseCase> useCaseSupplier,
            IntSupplier maxInFlightSupplier) {
        this(
                plugin,
                useCaseSupplier,
                maxInFlightSupplier,
                () -> plugin.getConfig().getBoolean("shared-containers-allowed", true));
    }

    public PaperTrackedItemProtectionListener(
            Plugin plugin,
            Supplier<VoidLossUseCase> useCaseSupplier,
            IntSupplier maxInFlightSupplier,
            BooleanSupplier sharedContainersAllowedSupplier) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.identityCodec = new PaperItemIdentityCodec();
        this.sharedContainersAllowedSupplier = Objects.requireNonNull(
                sharedContainersAllowedSupplier, "sharedContainersAllowedSupplier");
        this.creativeIdentityProtection = new PaperCreativeIdentityProtection(itemCollector);
        this.voidLossCoordinator = new PaperVoidLossCoordinator(
                plugin,
                Objects.requireNonNull(useCaseSupplier, "useCaseSupplier"),
                Objects.requireNonNull(maxInFlightSupplier, "maxInFlightSupplier"),
                identityCodec);
    }

    public void start() {
        if (closed) {
            throw new IllegalStateException("Protection listener is closed");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public void setCreativeCopyController(PaperCreativeCopyController controller) {
        this.creativeCopyController = Objects.requireNonNull(controller, "controller");
    }

    public void setCreativeLossObserver(BiConsumer<UUID, LoreItemIdentity> observer) {
        this.creativeLossObserver = Objects.requireNonNull(observer, "observer");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent event) {
        if (hasLoreIdentityEvidenceInTree(event.getEntity().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemCombust(EntityCombustEvent event) {
        if (event.getEntity() instanceof Item item
                && hasLoreIdentityEvidenceInTree(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemMerge(ItemMergeEvent event) {
        if (hasLoreIdentityEvidenceInTree(event.getEntity().getItemStack())
                || hasLoreIdentityEvidenceInTree(event.getTarget().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)) {
            return;
        }
        ItemStack stack = item.getItemStack();
        if (!hasLoreIdentityEvidenceInTree(stack)) {
            return;
        }
        ItemIdentityReadResult identity = identityCodec.readIdentity(stack);
        event.setCancelled(true);
        if (event.getCause() == EntityDamageEvent.DamageCause.VOID
                && identity instanceof ItemIdentityReadResult.Tracked tracked
                && !itemCollector.hasNestedIdentityEvidence(stack)) {
            voidLossCoordinator.begin(item, tracked.identity());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDurabilityDamage(PlayerItemDamageEvent event) {
        if (hasLoreIdentityEvidence(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityDurabilityDamage(EntityDamageItemEvent event) {
        if (hasLoreIdentityEvidence(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (hasLoreIdentityEvidence(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBeaconEffectChange(PlayerChangeBeaconEffectEvent event) {
        if (event.willConsumeItem()
                && hasLoreIdentityEvidence(
                        event.getPlayer().getOpenInventory().getTopInventory().getItem(0))) {
            event.setConsumeItem(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)
                && hasLoreIdentityEvidenceInTree(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryResultClick(InventoryClickEvent event) {
        if (event.getSlotType() == InventoryType.SlotType.RESULT
                && containsLoreIdentityEvidence(event.getView().getTopInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativeClone(InventoryClickEvent event) {
        if (creativeIdentityProtection.shouldCancelClone(event)) {
            event.setCancelled(true);
            if (creativeCopyController != null && event.getWhoClicked() instanceof Player player) {
                creativeCopyController.request(player, event.getCurrentItem());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativeInventoryMutation(InventoryCreativeEvent event) {
        PaperCreativeInventoryMutationSupport.handle(
                plugin, event, creativeIdentityProtection, identityCodec,
                creativeCopyController, creativeLossObserver);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativePickBlock(PlayerPickBlockEvent event) {
        if (creativeIdentityProtection.shouldCancelPickBlock(event)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativePickEntity(PlayerPickEntityEvent event) {
        if (creativeIdentityProtection.shouldCancelPickEntity(event)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSharedContainerClick(InventoryClickEvent event) {
        if (sharedContainersAllowed()) {
            return;
        }
        Inventory top = event.getView().getTopInventory();
        if (top.getType() == InventoryType.SHULKER_BOX
                && wouldInsertIntoTopInventory(event, top.getSize())) {
            event.setCancelled(true);
            return;
        }
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if ((isBundle(current) && hasLoreIdentityEvidenceInTree(cursor))
                || (isBundle(cursor) && hasLoreIdentityEvidenceInTree(current))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSharedContainerDrag(InventoryDragEvent event) {
        if (sharedContainersAllowed()
                || event.getView().getTopInventory().getType() != InventoryType.SHULKER_BOX
                || !hasLoreIdentityEvidenceInTree(event.getOldCursor())) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        if (event.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot < topSize)) {
            event.setCancelled(true);
        }
    }

    private boolean wouldInsertIntoTopInventory(InventoryClickEvent event, int topSize) {
        int rawSlot = event.getRawSlot();
        if (rawSlot >= 0 && rawSlot < topSize) {
            if (hasLoreIdentityEvidenceInTree(event.getCursor())) {
                return true;
            }
            int hotbarButton = event.getHotbarButton();
            return hotbarButton >= 0
                    && hasLoreIdentityEvidenceInTree(
                            event.getWhoClicked().getInventory().getItem(hotbarButton));
        }
        return event.isShiftClick() && hasLoreIdentityEvidenceInTree(event.getCurrentItem());
    }

    private boolean sharedContainersAllowed() {
        return sharedContainersAllowedSupplier.getAsBoolean();
    }

    private static boolean isBundle(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        String material = item.getType().name();
        return material.equals("BUNDLE") || material.endsWith("_BUNDLE");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafterCraft(CrafterCraftEvent event) {
        if (hasLoreIdentityEvidence(event.getResult())) {
            event.setCancelled(true);
            return;
        }
        if (event.getBlock().getState() instanceof Crafter crafter
                && containsLoreIdentityEvidence(crafter.getInventory())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCook(BlockCookEvent event) {
        if (hasLoreIdentityEvidence(event.getSource())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFurnaceFuel(FurnaceBurnEvent event) {
        if (hasLoreIdentityEvidence(event.getFuel())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBrewingFuel(BrewingStandFuelEvent event) {
        if (hasLoreIdentityEvidence(event.getFuel())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBrew(BrewEvent event) {
        if (containsLoreIdentityEvidence(event.getContents())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (event.getDestination().getType() == InventoryType.COMPOSTER
                && hasLoreIdentityEvidence(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityCompost(EntityCompostItemEvent event) {
        if (hasLoreIdentityEvidence(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (hasLoreIdentityEvidence(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        ItemStack source = event.getItemStack();
        if (source != null && hasLoreIdentityEvidence(source)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null
                && hasLoreIdentityEvidence(itemInHand(player, event.getHand()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFlowerPotManipulate(PlayerFlowerPotManipulateEvent event) {
        if (event.isPlacing() && hasLoreIdentityEvidence(event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        protectBucketUse(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        protectBucketUse(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        if (hasLoreIdentityEvidence(event.getOriginalBucket())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsumptiveInteraction(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item != null
                && losesIdentityOnInteraction(item.getType())
                && hasLoreIdentityEvidence(item)) {
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteraction(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ArmorStand
                || event.getRightClicked() instanceof ItemFrame) {
            return;
        }
        ItemStack item = itemInHand(event.getPlayer(), event.getHand());
        boolean piglinBarter = event.getRightClicked() instanceof Piglin piglin
                && (item.getType() == Material.GOLD_INGOT
                        || piglin.getBarterList().contains(item.getType()));
        if ((piglinBarter || losesIdentityOnEntityInteraction(item.getType()))
                && hasLoreIdentityEvidence(item)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProjectileLaunch(PlayerLaunchProjectileEvent event) {
        if (event.shouldConsume() && hasLoreIdentityEvidence(event.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onElytraBoost(PlayerElytraBoostEvent event) {
        if (event.shouldConsume() && hasLoreIdentityEvidence(event.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onReadyArrow(PlayerReadyArrowEvent event) {
        if (hasLoreIdentityEvidence(event.getArrow())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        ItemStack consumable = event.getConsumable();
        if (event.shouldConsumeItem()
                && consumable != null
                && hasLoreIdentityEvidence(consumable)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDispense(BlockPreDispenseEvent event) {
        if (hasLoreIdentityEvidence(event.getItemStack())) {
            event.setCancelled(true);
        }
    }

    private void protectBucketUse(PlayerBucketEvent event) {
        if (hasLoreIdentityEvidence(itemInHand(event.getPlayer(), event.getHand()))) {
            event.setCancelled(true);
        }
    }

    private static ItemStack itemInHand(Player player, EquipmentSlot hand) {
        return hand == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
    }

    private boolean containsLoreIdentityEvidence(Inventory inventory) {
        for (ItemStack item : inventory.getContents()) {
            if (hasLoreIdentityEvidence(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasLoreIdentityEvidence(ItemStack item) {
        return identityCodec.hasIdentityEvidence(item);
    }

    private boolean hasLoreIdentityEvidenceInTree(ItemStack item) {
        return itemCollector.hasIdentityEvidence(item);
    }

    @Override
    public void close() {
        closed = true;
        creativeIdentityProtection.clear();
        voidLossCoordinator.close();
        HandlerList.unregisterAll(this);
    }
}
