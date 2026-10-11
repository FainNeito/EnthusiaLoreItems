package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.function.Predicate;
import org.bukkit.GameMode;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;

/**
 * A creative-mode player cannot move a storage-locked item at all. Creative clients may place
 * items in their own inventory directly, so the item simply stays where it is until the player
 * leaves creative. Survival play is unaffected.
 */
final class PaperCreativeStorageLockListener implements Listener {
    private final Predicate<ItemStack> locked;

    PaperCreativeStorageLockListener(Predicate<ItemStack> locked) {
        this.locked = Objects.requireNonNull(locked, "locked");
    }

    /** Also receives InventoryCreativeEvent, which shares this handler list. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        HumanEntity player = event.getWhoClicked();
        if (!creative(player)) {
            return;
        }
        int hotbarButton = event.getHotbarButton();
        if (locked.test(event.getCurrentItem())
                || locked.test(event.getCursor())
                || hotbarButton >= 0 && locked.test(player.getInventory().getItem(hotbarButton))
                || event.getClick() == ClickType.SWAP_OFFHAND
                        && locked.test(player.getInventory().getItemInOffHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (creative(event.getWhoClicked()) && locked.test(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (creative(event.getPlayer())
                && (locked.test(event.getMainHandItem()) || locked.test(event.getOffHandItem()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (creative(event.getPlayer()) && locked.test(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    private static boolean creative(HumanEntity player) {
        return player.getGameMode() == GameMode.CREATIVE;
    }
}
