package net.enthusia.loreitems.acceptance;

import java.util.Objects;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Test setup owns the destination stack independently of the removed inventory entry. */
final class AcceptanceInventoryMoves {
    private AcceptanceInventoryMoves() {}

    static ItemStack takeTrackedStorageItem(Player player, Predicate<ItemStack> tracked) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = Objects.requireNonNull(inventory.getStorageContents(), "player storage contents");
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item != null && tracked.test(item)) {
                ItemStack moved = item.clone();
                inventory.setItem(slot, null);
                return moved;
            }
        }
        throw new IllegalStateException("no tracked item in player storage");
    }
}
