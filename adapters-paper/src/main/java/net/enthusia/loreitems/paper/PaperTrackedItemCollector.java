package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

/** Shared bounded collector for tracked identities, including nested shulkers and bundles. */
final class PaperTrackedItemCollector {
    private static final int MAX_NESTING_DEPTH = 8;
    private static final String ROOT_KEY_PREFIX = "root:";

    private final PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();

    void collectArray(
            ItemStack[] contents,
            LocationDescriptor.Type type,
            String key,
            String prefix,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            PaperScanLimit limit) {
        if (contents == null) {
            return;
        }
        for (int index = 0; index < contents.length; index++) {
            collectItem(contents[index], type, key, prefix + index, observations, 0, limit);
        }
    }

    void collectItem(
            ItemStack item,
            LocationDescriptor.Type type,
            String key,
            String path,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            int depth,
            PaperScanLimit limit) {
        if (!scannable(item)) {
            return;
        }
        collectIdentity(item, type, key, path, observations);
        if (depth < MAX_NESTING_DEPTH && hasNestedIdentityEvidence(item)) {
            collectNested(
                    item.getItemMeta(),
                    nestedLocationKey(type, key),
                    path,
                    observations,
                    depth,
                    limit);
        }
    }

    LoreItemIdentity trackedIdentity(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        ItemIdentityReadResult result = identityCodec.readIdentity(item);
        return result instanceof ItemIdentityReadResult.Tracked tracked
                ? tracked.identity()
                : null;
    }

    boolean hasIdentityEvidence(ItemStack item) {
        return matchesInTree(item, 0, identityCodec::hasIdentityEvidence);
    }

    /** Whether the item, or anything nested in it, carries the storage-lock marker. */
    boolean hasStorageLockInTree(ItemStack item) {
        return matchesInTree(item, 0, PaperStorageLock::isLocked);
    }

    boolean hasNestedIdentityEvidence(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta blockMeta
                && matchesInTree(blockMeta.getBlockState(), 0, identityCodec::hasIdentityEvidence)) {
            return true;
        }
        if (meta instanceof BundleMeta bundle) {
            for (ItemStack nested : bundle.getItems()) {
                if (matchesInTree(nested, 1, identityCodec::hasIdentityEvidence)) {
                    return true;
                }
            }
        }
        return false;
    }

    static String nestedLocationKey(LocationDescriptor.Type parentType, String locationKey) {
        Objects.requireNonNull(parentType, "parentType");
        Objects.requireNonNull(locationKey, "locationKey");
        if (parentType == LocationDescriptor.Type.NESTED_CONTAINER) {
            return locationKey;
        }
        return ROOT_KEY_PREFIX + parentType.name() + ':' + locationKey;
    }

    private boolean matchesInTree(ItemStack item, int depth, Predicate<ItemStack> matcher) {
        if (!scannable(item)) {
            return false;
        }
        if (matcher.test(item)) {
            return true;
        }
        return depth < MAX_NESTING_DEPTH
                && nestedMatches(item.getItemMeta(), depth, matcher);
    }

    private boolean nestedMatches(ItemMeta meta, int depth, Predicate<ItemStack> matcher) {
        if (meta instanceof BlockStateMeta blockMeta
                && matchesInTree(blockMeta.getBlockState(), depth, matcher)) {
            return true;
        }
        return meta instanceof BundleMeta bundle
                && bundleMatches(bundle, depth, matcher);
    }

    private boolean bundleMatches(BundleMeta bundle, int depth, Predicate<ItemStack> matcher) {
        for (ItemStack nested : bundle.getItems()) {
            if (matchesInTree(nested, depth + 1, matcher)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesInTree(BlockState blockState, int depth, Predicate<ItemStack> matcher) {
        if (!(blockState instanceof ShulkerBox shulker)) {
            return false;
        }
        for (ItemStack nested : shulker.getInventory().getContents()) {
            if (matchesInTree(nested, depth + 1, matcher)) {
                return true;
            }
        }
        return false;
    }

    private void collectIdentity(
            ItemStack item,
            LocationDescriptor.Type type,
            String key,
            String path,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations) {
        LoreItemIdentity identity = trackedIdentity(item);
        if (identity != null) {
            observations.computeIfAbsent(identity, ignored -> new ArrayList<>())
                    .add(new LocationDescriptor(type, key, path));
        }
    }

    private void collectNested(
            ItemMeta meta,
            String key,
            String path,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            int depth,
            PaperScanLimit limit) {
        if (meta instanceof BlockStateMeta blockMeta) {
            collectShulker(blockMeta.getBlockState(), key, path, observations, depth, limit);
        }
        if (meta instanceof BundleMeta bundle && limit.tryConsume()) {
            collectNestedArray(
                    bundle.getItems().toArray(ItemStack[]::new),
                    key,
                    path + "/bundle:",
                    observations,
                    depth,
                    limit);
        }
    }

    private void collectShulker(
            BlockState blockState,
            String key,
            String path,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            int depth,
            PaperScanLimit limit) {
        if (blockState instanceof ShulkerBox shulker && limit.tryConsume()) {
            collectNestedArray(
                    shulker.getInventory().getContents(),
                    key,
                    path + "/shulker:",
                    observations,
                    depth,
                    limit);
        }
    }

    private void collectNestedArray(
            ItemStack[] contents,
            String key,
            String prefix,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations,
            int depth,
            PaperScanLimit limit) {
        if (contents == null) {
            return;
        }
        for (int index = 0; index < contents.length; index++) {
            collectItem(
                    contents[index],
                    LocationDescriptor.Type.NESTED_CONTAINER,
                    key,
                    prefix + index,
                    observations,
                    depth + 1,
                    limit);
        }
    }

    private static boolean scannable(ItemStack item) {
        return item != null && !item.getType().isAir();
    }
}
