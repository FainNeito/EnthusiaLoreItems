package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        if (depth < MAX_NESTING_DEPTH) {
            ItemMeta nestedMeta = nestedMetaWithIdentityEvidence(item);
            if (nestedMeta != null) {
                collectNested(
                        nestedMeta,
                        nestedLocationKey(type, key),
                        path,
                        observations,
                        depth,
                        limit);
            }
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
        return hasIdentityEvidence(item, 0);
    }

    boolean hasNestedIdentityEvidence(ItemStack item) {
        return nestedMetaWithIdentityEvidence(item) != null;
    }

    /** Returns the metadata snapshot used to prove nested identity evidence. */
    ItemMeta nestedMetaWithIdentityEvidence(ItemStack item) {
        // Preserve the metadata-free shortcut while avoiding a second snapshot
        // for nested shulker/bundle traversal after identity discovery.
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta blockMeta
                && hasIdentityEvidence(blockMeta.getBlockState(), 0)) {
            return meta;
        }
        if (meta instanceof BundleMeta bundle && bundleHasIdentityEvidence(bundle, 0)) {
            return meta;
        }
        return null;
    }

    static String nestedLocationKey(LocationDescriptor.Type parentType, String locationKey) {
        Objects.requireNonNull(parentType, "parentType");
        Objects.requireNonNull(locationKey, "locationKey");
        if (parentType == LocationDescriptor.Type.NESTED_CONTAINER) {
            return locationKey;
        }
        return ROOT_KEY_PREFIX + parentType.name() + ':' + locationKey;
    }

    private boolean hasIdentityEvidence(ItemStack item, int depth) {
        if (!scannable(item)) {
            return false;
        }
        if (identityCodec.hasIdentityEvidence(item)) {
            return true;
        }
        return depth < MAX_NESTING_DEPTH
                && item.hasItemMeta()
                && hasNestedIdentityEvidence(item.getItemMeta(), depth);
    }

    private boolean hasNestedIdentityEvidence(ItemMeta meta, int depth) {
        if (meta instanceof BlockStateMeta blockMeta
                && hasIdentityEvidence(blockMeta.getBlockState(), depth)) {
            return true;
        }
        return meta instanceof BundleMeta bundle
                && bundleHasIdentityEvidence(bundle, depth);
    }

    private boolean bundleHasIdentityEvidence(BundleMeta bundle, int depth) {
        for (ItemStack nested : bundle.getItems()) {
            if (hasIdentityEvidence(nested, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasIdentityEvidence(BlockState blockState, int depth) {
        if (!(blockState instanceof ShulkerBox shulker)) {
            return false;
        }
        for (ItemStack nested : shulker.getInventory().getContents()) {
            if (hasIdentityEvidence(nested, depth + 1)) {
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
