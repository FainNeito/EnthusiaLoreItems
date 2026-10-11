package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.PreparedVoidLoss;
import net.enthusia.loreitems.application.VoidLossUseCase;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperCreativeStorageLockTest {
    private ServerMock server;
    private PlayerMock player;
    private PaperTrackedItemProtectionListener listener;
    private InventoryView view;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        listener = new PaperTrackedItemProtectionListener(
                MockBukkit.createMockPlugin(), NoOpVoidLossUseCase::new, 4);
        listener.start();
        view = player.openInventory(server.createInventory(null, 9));
    }

    @AfterEach
    void tearDown() {
        listener.close();
        MockBukkit.unmock();
    }

    @Test
    void creativePlayerCannotPickUpALockedItem() {
        player.setGameMode(GameMode.CREATIVE);
        player.getInventory().setItem(0, lockedItem());

        assertTrue(fire(click(0, ClickType.LEFT, InventoryAction.PICKUP_ALL)).isCancelled());
    }

    @Test
    void creativePlayerCannotOffhandSwapALockedItemInTheInventory() {
        player.setGameMode(GameMode.CREATIVE);
        player.getInventory().setItemInOffHand(lockedItem());

        assertTrue(fire(click(9, ClickType.SWAP_OFFHAND, InventoryAction.HOTBAR_SWAP)).isCancelled());
    }

    @Test
    void creativeSetSlotWithALockedItemIsCancelled() {
        player.setGameMode(GameMode.CREATIVE);
        InventoryCreativeEvent event = new InventoryCreativeEvent(
                view, InventoryType.SlotType.CONTAINER, 9 + 18, lockedItem());

        assertTrue(fire(event).isCancelled());
    }

    @Test
    void creativePlayerCannotDragALockedItem() {
        player.setGameMode(GameMode.CREATIVE);
        InventoryDragEvent event = new InventoryDragEvent(
                view, null, lockedItem(), false, Map.of(9 + 18, lockedItem()));

        assertTrue(fire(event).isCancelled());
    }

    @Test
    void creativePlayerCannotSwapHandsWithALockedItem() {
        player.setGameMode(GameMode.CREATIVE);
        PlayerSwapHandItemsEvent event =
                new PlayerSwapHandItemsEvent(player, ItemStack.empty(), lockedItem());

        assertTrue(fire(event).isCancelled());
    }

    @Test
    void creativePlayerCannotDropALockedItem() {
        player.setGameMode(GameMode.CREATIVE);
        Item dropped = player.getWorld().dropItem(player.getLocation(), lockedItem());

        assertTrue(fire(new PlayerDropItemEvent(player, dropped)).isCancelled());
    }

    @Test
    void survivalPlayerCanStillMoveALockedItemWithinTheirInventory() {
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().setItem(0, lockedItem());

        assertFalse(fire(click(0, ClickType.LEFT, InventoryAction.PICKUP_ALL)).isCancelled());
    }

    @Test
    void creativePlayerCanStillMoveUnlockedItems() {
        player.setGameMode(GameMode.CREATIVE);
        player.getInventory().setItem(0, ItemStack.of(Material.DIAMOND_SWORD));

        assertFalse(fire(click(0, ClickType.LEFT, InventoryAction.PICKUP_ALL)).isCancelled());
    }

    private InventoryClickEvent click(int playerSlot, ClickType click, InventoryAction action) {
        int rawSlot = view.getTopInventory().getSize() + 27 + playerSlot;
        if (playerSlot >= 9) {
            rawSlot = view.getTopInventory().getSize() + playerSlot - 9;
        }
        InventoryClickEvent event = new InventoryClickEvent(
                view, InventoryType.SlotType.CONTAINER, rawSlot, click, action);
        event.setCurrentItem(player.getInventory().getItem(playerSlot));
        return event;
    }

    private <T extends org.bukkit.event.Event> T fire(T event) {
        server.getPluginManager().callEvent(event);
        return event;
    }

    private static ItemStack lockedItem() {
        ItemStack item = ItemStack.of(Material.NETHERITE_HELMET);
        item.editPersistentDataContainer(
                data -> data.set(PaperStorageLock.KEY, PersistentDataType.BOOLEAN, true));
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
