package net.enthusia.loreitems.paper;

import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Decides whether a click or drag would put a restricted item into a container or bundle. */
final class PaperContainerInsertion {
    private PaperContainerInsertion() {
    }

    static boolean insertsRestricted(
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

    static boolean dragsRestricted(
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

    private static boolean isBundle(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        String material = item.getType().name();
        return material.equals("BUNDLE") || material.endsWith("_BUNDLE");
    }
}
