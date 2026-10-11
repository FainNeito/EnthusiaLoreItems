package net.enthusia.loreitems.paper;

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
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
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
import org.bukkit.event.inventory.ClickType;
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
    private static final Set<String> CONSUMPTIVE_INTERACTION_MATERIALS = Set.of(
            "BONE_MEAL",
            "BOWL",
            "ENDER_EYE",
            "FIRE_CHARGE",
            "GLASS_BOTTLE",
            "GLOW_INK_SAC",
            "HONEYCOMB",
            "INK_SAC",
            "MAP",
            "OMINOUS_TRIAL_KEY",
            "RESIN_CLUMP",
            "TRIAL_KEY");
    private static final Set<String> CONSUMPTIVE_ENTITY_MATERIALS = Set.of(
            "AMETHYST_SHARD",
            "BAMBOO",
            "BROWN_MUSHROOM",
            "CHEST",
            "DANDELION",
            "HAY_BLOCK",
            "LEAD",
            "NAME_TAG",
            "POPPY",
            "RED_MUSHROOM",
            "SADDLE",
            "SEAGRASS",
            "SLIME_BALL",
            "WHEAT",
            "WOLF_ARMOR");

    private static final Set<InventoryType> SHARED_CONTAINER_TYPES = Set.of(InventoryType.SHULKER_BOX);
    private static final Set<InventoryType> STORAGE_LOCK_CONTAINER_TYPES =
            Set.of(InventoryType.SHULKER_BOX, InventoryType.ENDER_CHEST);

    private final Plugin plugin;
    private final PaperItemIdentityCodec identityCodec;
    private final PaperTrackedItemCollector itemCollector = new PaperTrackedItemCollector();
    private final PaperCreativeIdentityProtection creativeIdentityProtection;
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
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreativeInventoryMutation(InventoryCreativeEvent event) {
        if (creativeIdentityProtection.shouldCancelInventoryMutation(event)) {
            event.setCancelled(true);
        }
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
        if (!sharedContainersAllowed()
                && insertsRestricted(event, SHARED_CONTAINER_TYPES, this::hasLoreIdentityEvidenceInTree)) {
            event.setCancelled(true);
            return;
        }
        if (insertsRestricted(event, STORAGE_LOCK_CONTAINER_TYPES, itemCollector::hasStorageLockInTree)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSharedContainerDrag(InventoryDragEvent event) {
        if (!sharedContainersAllowed()
                && dragsRestricted(event, SHARED_CONTAINER_TYPES, this::hasLoreIdentityEvidenceInTree)) {
            event.setCancelled(true);
            return;
        }
        if (dragsRestricted(event, STORAGE_LOCK_CONTAINER_TYPES, itemCollector::hasStorageLockInTree)) {
            event.setCancelled(true);
        }
    }

    private static boolean insertsRestricted(
            InventoryClickEvent event, Set<InventoryType> containerTypes, Predicate<ItemStack> restricted) {
        Inventory top = event.getView().getTopInventory();
        if (containerTypes.contains(top.getType())
                && wouldInsertIntoTopInventory(event, top.getSize(), restricted)) {
            return true;
        }
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        return (isBundle(current) && restricted.test(cursor))
                || (isBundle(cursor) && restricted.test(current));
    }

    private static boolean dragsRestricted(
            InventoryDragEvent event, Set<InventoryType> containerTypes, Predicate<ItemStack> restricted) {
        if (!containerTypes.contains(event.getView().getTopInventory().getType())
                || !restricted.test(event.getOldCursor())) {
            return false;
        }
        int topSize = event.getView().getTopInventory().getSize();
        return event.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot < topSize);
    }

    private static boolean wouldInsertIntoTopInventory(
            InventoryClickEvent event, int topSize, Predicate<ItemStack> restricted) {
        int rawSlot = event.getRawSlot();
        if (rawSlot >= 0 && rawSlot < topSize) {
            if (restricted.test(event.getCursor())) {
                return true;
            }
            if (event.getClick() == ClickType.SWAP_OFFHAND) {
                return restricted.test(event.getWhoClicked().getInventory().getItemInOffHand());
            }
            int hotbarButton = event.getHotbarButton();
            return hotbarButton >= 0
                    && restricted.test(event.getWhoClicked().getInventory().getItem(hotbarButton));
        }
        return event.isShiftClick() && restricted.test(event.getCurrentItem());
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
        InventoryType destination = event.getDestination().getType();
        if (destination == InventoryType.COMPOSTER
                && hasLoreIdentityEvidence(event.getItem())) {
            event.setCancelled(true);
            return;
        }
        if (destination == InventoryType.SHULKER_BOX
                && (itemCollector.hasStorageLockInTree(event.getItem())
                        || !sharedContainersAllowed() && hasLoreIdentityEvidenceInTree(event.getItem()))) {
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

    private static boolean losesIdentityOnInteraction(Material material) {
        String name = material.name();
        return material.isEdible()
                || CONSUMPTIVE_INTERACTION_MATERIALS.contains(name)
                || name.endsWith("_DYE")
                || name.endsWith("_SPAWN_EGG");
    }

    private static boolean losesIdentityOnEntityInteraction(Material material) {
        String name = material.name();
        return losesIdentityOnInteraction(material)
                || material.isEdible()
                || CONSUMPTIVE_ENTITY_MATERIALS.contains(name)
                || name.endsWith("_CARPET")
                || name.endsWith("_HORSE_ARMOR")
                || name.endsWith("_SEEDS");
    }

    @Override
    public void close() {
        closed = true;
        voidLossCoordinator.close();
        HandlerList.unregisterAll(this);
    }
}
