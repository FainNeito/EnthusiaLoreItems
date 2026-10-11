package net.enthusia.loreitems.paper;

import java.util.Objects;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Item-level marker that keeps an item out of shulker boxes, bundles and Ender Chests,
 * whatever {@code shared-containers-allowed} says. It is ordinary item data, so it is
 * captured with the definition template and survives every template rollout.
 */
public final class PaperStorageLock {
    public static final NamespacedKey KEY = Objects.requireNonNull(
            NamespacedKey.fromString("enthusialoreitems:storage_lock"),
            "Static LoreItems namespaced key must be valid");

    private PaperStorageLock() {
    }

    public static boolean isLocked(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(KEY);
    }
}
