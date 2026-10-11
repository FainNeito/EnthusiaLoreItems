package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.PreparedVoidLoss;
import net.enthusia.loreitems.application.VoidLossUseCase;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperSharedContainerRestrictionTest {
    private static final String SHARED_CONTAINERS_ALLOWED = "shared-containers-allowed";
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    private ServerMock server;
    private PlayerMock player;
    private PaperTrackedItemProtectionListener listener;
    private PaperItemIdentityCodec identityCodec;
    private org.bukkit.plugin.java.JavaPlugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        plugin = MockBukkit.createMockPlugin();
        listener = new PaperTrackedItemProtectionListener(plugin, NoOpVoidLossUseCase::new, 4);
        identityCodec = new PaperItemIdentityCodec();
    }

    @AfterEach
    void tearDown() {
        listener.close();
        MockBukkit.unmock();
    }

    @Test
    void restrictedModeBlocksTrackedInsertionIntoOpenShulker() {
        plugin.getConfig().set(SHARED_CONTAINERS_ALLOWED, false);
        Inventory shulker = server.createInventory(null, InventoryType.SHULKER_BOX);
        InventoryView view = player.openInventory(shulker);
        player.setItemOnCursor(trackedItem());
        InventoryClickEvent event = new InventoryClickEvent(
                view,
                InventoryType.SlotType.CONTAINER,
                0,
                ClickType.LEFT,
                InventoryAction.PLACE_ALL);

        listener.onSharedContainerClick(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void restrictedModeBlocksNestedTrackedBundleInsertionIntoOpenShulker() {
        plugin.getConfig().set(SHARED_CONTAINERS_ALLOWED, false);
        Inventory shulker = server.createInventory(null, InventoryType.SHULKER_BOX);
        InventoryView view = player.openInventory(shulker);
        player.setItemOnCursor(bundleContaining(trackedItem()));
        InventoryClickEvent event = new InventoryClickEvent(
                view,
                InventoryType.SlotType.CONTAINER,
                0,
                ClickType.LEFT,
                InventoryAction.PLACE_ALL);

        listener.onSharedContainerClick(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void allowedModeLeavesTrackedShulkerInsertionUntouched() {
        plugin.getConfig().set(SHARED_CONTAINERS_ALLOWED, true);
        Inventory shulker = server.createInventory(null, InventoryType.SHULKER_BOX);
        InventoryView view = player.openInventory(shulker);
        player.setItemOnCursor(trackedItem());
        InventoryClickEvent event = new InventoryClickEvent(
                view,
                InventoryType.SlotType.CONTAINER,
                0,
                ClickType.LEFT,
                InventoryAction.PLACE_ALL);

        listener.onSharedContainerClick(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void restrictedModeBlocksTrackedInsertionIntoBundleItem() {
        plugin.getConfig().set(SHARED_CONTAINERS_ALLOWED, false);
        Inventory inventory = server.createInventory(null, 9);
        inventory.setItem(0, ItemStack.of(Material.BUNDLE));
        InventoryView view = player.openInventory(inventory);
        player.setItemOnCursor(trackedItem());
        InventoryClickEvent event = new InventoryClickEvent(
                view,
                InventoryType.SlotType.CONTAINER,
                0,
                ClickType.RIGHT,
                InventoryAction.SWAP_WITH_CURSOR);

        listener.onSharedContainerClick(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void restrictedModeDoesNotBlockTakingTrackedItemOutOfShulker() {
        plugin.getConfig().set(SHARED_CONTAINERS_ALLOWED, false);
        Inventory shulker = server.createInventory(null, InventoryType.SHULKER_BOX);
        shulker.setItem(0, trackedItem());
        InventoryView view = player.openInventory(shulker);
        player.setItemOnCursor(ItemStack.empty());
        InventoryClickEvent event = new InventoryClickEvent(
                view,
                InventoryType.SlotType.CONTAINER,
                0,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL);

        listener.onSharedContainerClick(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void storageLockBlocksInsertionIntoEnderChestEvenWhenSharedContainersAreAllowed() {
        plugin.getConfig().set(SHARED_CONTAINERS_ALLOWED, true);
        Inventory enderChest = server.createInventory(null, InventoryType.ENDER_CHEST);
        InventoryView view = player.openInventory(enderChest);
        player.setItemOnCursor(lockedItem());
        InventoryClickEvent event = new InventoryClickEvent(
                view,
                InventoryType.SlotType.CONTAINER,
                0,
                ClickType.LEFT,
                InventoryAction.PLACE_ALL);

        listener.onSharedContainerClick(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void storageLockBlocksShiftClickIntoShulker() {
        plugin.getConfig().set(SHARED_CONTAINERS_ALLOWED, true);
        Inventory shulker = server.createInventory(null, InventoryType.SHULKER_BOX);
        InventoryView view = player.openInventory(shulker);
        player.getInventory().setItem(0, lockedItem());
        int playerSlot = view.convertSlot(view.getTopInventory().getSize() + 27);
        InventoryClickEvent event = new InventoryClickEvent(
                view,
                InventoryType.SlotType.QUICKBAR,
                view.getTopInventory().getSize() + 27,
                ClickType.SHIFT_LEFT,
                InventoryAction.MOVE_TO_OTHER_INVENTORY);
        event.setCurrentItem(player.getInventory().getItem(playerSlot));

        listener.onSharedContainerClick(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void storageLockBlocksOffhandSwapIntoEnderChest() {
        plugin.getConfig().set(SHARED_CONTAINERS_ALLOWED, true);
        Inventory enderChest = server.createInventory(null, InventoryType.ENDER_CHEST);
        InventoryView view = player.openInventory(enderChest);
        player.getInventory().setItemInOffHand(lockedItem());
        InventoryClickEvent event = new InventoryClickEvent(
                view,
                InventoryType.SlotType.CONTAINER,
                0,
                ClickType.SWAP_OFFHAND,
                InventoryAction.HOTBAR_SWAP);

        listener.onSharedContainerClick(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void storageLockBlocksBundleInsertion() {
        plugin.getConfig().set(SHARED_CONTAINERS_ALLOWED, true);
        Inventory inventory = server.createInventory(null, 9);
        inventory.setItem(0, ItemStack.of(Material.BUNDLE));
        InventoryView view = player.openInventory(inventory);
        player.setItemOnCursor(lockedItem());
        InventoryClickEvent event = new InventoryClickEvent(
                view,
                InventoryType.SlotType.CONTAINER,
                0,
                ClickType.RIGHT,
                InventoryAction.SWAP_WITH_CURSOR);

        listener.onSharedContainerClick(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void storageLockBlocksDragIntoEnderChest() {
        plugin.getConfig().set(SHARED_CONTAINERS_ALLOWED, true);
        Inventory enderChest = server.createInventory(null, InventoryType.ENDER_CHEST);
        InventoryView view = player.openInventory(enderChest);
        InventoryDragEvent event = new InventoryDragEvent(
                view,
                null,
                lockedItem(),
                false,
                Map.of(0, lockedItem()));

        listener.onSharedContainerDrag(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void storageLockBlocksHopperIntoShulker() {
        plugin.getConfig().set(SHARED_CONTAINERS_ALLOWED, true);
        Inventory hopper = server.createInventory(null, InventoryType.HOPPER);
        Inventory shulker = server.createInventory(null, InventoryType.SHULKER_BOX);
        InventoryMoveItemEvent event = new InventoryMoveItemEvent(hopper, lockedItem(), shulker, true);

        listener.onInventoryMove(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void unlockedTrackedItemStillGoesIntoEnderChest() {
        plugin.getConfig().set(SHARED_CONTAINERS_ALLOWED, false);
        Inventory enderChest = server.createInventory(null, InventoryType.ENDER_CHEST);
        InventoryView view = player.openInventory(enderChest);
        player.setItemOnCursor(trackedItem());
        InventoryClickEvent event = new InventoryClickEvent(
                view,
                InventoryType.SlotType.CONTAINER,
                0,
                ClickType.LEFT,
                InventoryAction.PLACE_ALL);

        listener.onSharedContainerClick(event);

        assertFalse(event.isCancelled());
    }

    @Test
    void storageLockStillAllowsTakingTheItemOutOfAnEnderChest() {
        Inventory enderChest = server.createInventory(null, InventoryType.ENDER_CHEST);
        enderChest.setItem(0, lockedItem());
        InventoryView view = player.openInventory(enderChest);
        player.setItemOnCursor(ItemStack.empty());
        InventoryClickEvent event = new InventoryClickEvent(
                view,
                InventoryType.SlotType.CONTAINER,
                0,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL);

        listener.onSharedContainerClick(event);

        assertFalse(event.isCancelled());
    }

    private ItemStack lockedItem() {
        ItemStack item = trackedItem();
        item.editPersistentDataContainer(
                data -> data.set(PaperStorageLock.KEY, PersistentDataType.BOOLEAN, true));
        return item;
    }

    private ItemStack trackedItem() {
        return identityCodec.writeIdentity(ItemStack.of(Material.DIAMOND_SWORD), IDENTITY);
    }

    private static ItemStack bundleContaining(ItemStack nested) {
        ItemStack item = ItemStack.of(Material.BUNDLE);
        BundleMeta meta = assertInstanceOf(BundleMeta.class, item.getItemMeta());
        meta.setItems(List.of(nested));
        assertTrue(item.setItemMeta(meta));
        return item;
    }

    private static final class NoOpVoidLossUseCase implements VoidLossUseCase {
        @Override
        public CompletionStage<PrepareResult> prepare(Request request) {
            return CompletableFuture.completedFuture(
                    PrepareResult.of(PrepareStatus.SERVICE_UNAVAILABLE, "unused"));
        }

        @Override
        public CompletionStage<Boolean> complete(PreparedVoidLoss prepared) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletionStage<Boolean> abort(PreparedVoidLoss prepared, String reason) {
            return CompletableFuture.completedFuture(false);
        }

        @Override
        public CompletionStage<Boolean> requireReview(PreparedVoidLoss prepared, String reason) {
            return CompletableFuture.completedFuture(false);
        }
    }
}
