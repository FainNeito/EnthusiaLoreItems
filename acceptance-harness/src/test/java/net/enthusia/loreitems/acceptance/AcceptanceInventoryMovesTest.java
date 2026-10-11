package net.enthusia.loreitems.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class AcceptanceInventoryMovesTest {
    private PlayerMock player;

    @BeforeEach
    void start() {
        player = MockBukkit.mock().addPlayer();
    }

    @AfterEach
    void stop() {
        MockBukkit.unmock();
    }

    @Test
    void removesSourceAndReturnsAnIndependentDestinationStack() {
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND_SWORD));
        ItemStack source = player.getInventory().getItem(0);
        ItemStack moved = AcceptanceInventoryMoves.takeTrackedStorageItem(player,
                item -> item.getType() == Material.DIAMOND_SWORD);
        assertNotSame(source, moved);
        assertEquals(source, moved);
        ItemStack remaining = player.getInventory().getItem(0);
        assertTrue(remaining == null || remaining.getType().isAir());
        source.setAmount(0);
        assertEquals(1, moved.getAmount());
    }

    @Test
    void leavesUntrackedItemsAndFailsIfTheSourceIsAbsent() {
        player.getInventory().setItem(0, new ItemStack(Material.STONE));
        assertThrows(IllegalStateException.class, () -> AcceptanceInventoryMoves.takeTrackedStorageItem(
                player, item -> item.getType() == Material.DIAMOND_SWORD));
        assertEquals(Material.STONE, player.getInventory().getItem(0).getType());
    }
}
