package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.StringJoiner;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/** Immutable, reload-safe path to one item inside a naturally accessible inventory. */
record PaperTemplateUpdateItemReference(
        PaperInventoryReference inventoryReference,
        int rootSlot,
        List<NestedStep> nestedPath) implements PaperTemplateUpdateReference {
    private static final int MAX_NESTING_DEPTH = 8;

    PaperTemplateUpdateItemReference {
        Objects.requireNonNull(inventoryReference, "inventoryReference");
        Objects.requireNonNull(nestedPath, "nestedPath");
        if (rootSlot < 0) {
            throw new IllegalArgumentException("rootSlot must not be negative");
        }
        if (nestedPath.size() > MAX_NESTING_DEPTH) {
            throw new IllegalArgumentException("nestedPath exceeds the supported depth");
        }
        nestedPath = List.copyOf(nestedPath);
    }

    static PaperTemplateUpdateItemReference root(
            PaperInventoryReference inventoryReference,
            int rootSlot) {
        return new PaperTemplateUpdateItemReference(
                inventoryReference, rootSlot, List.of());
    }

    PaperTemplateUpdateItemReference nested(NestedStep step) {
        Objects.requireNonNull(step, "step");
        List<NestedStep> path = new ArrayList<>(nestedPath);
        path.add(step);
        return new PaperTemplateUpdateItemReference(inventoryReference, rootSlot, path);
    }

    @Override
    public DestructiveLocation destructiveLocation() {
        return new DestructiveLocation(
                inventoryLocationType(inventoryReference),
                inventoryLocationKey(inventoryReference),
                containerPath());
    }

    @Override
    public Optional<Resolved> resolve(Plugin plugin) {
        Objects.requireNonNull(plugin, "plugin");
        return inventoryReference.resolve(plugin).flatMap(this::resolveIn);
    }

    /**
     * Discovery-only resolution against the inventory already opened for this scan pass.
     * Mutation-time reads must still call resolve(plugin) to revalidate the live location.
     */
    Optional<Resolved> resolveIn(Inventory inventory) {
        Objects.requireNonNull(inventory, "inventory");
        if (rootSlot >= inventory.getSize()) {
            return Optional.empty();
        }
        ItemStack root = inventory.getItem(rootSlot);
        ItemStack target = readAt(root, nestedPath, 0);
        if (target == null || target.getType().isAir()) {
            return Optional.empty();
        }
        return Optional.of(new Resolved(
                inventory,
                rootSlot,
                nestedPath,
                Objects.requireNonNull(root, "root").clone(),
                target.clone()));
    }

    private String containerPath() {
        StringJoiner path = new StringJoiner("/");
        path.add("slot=" + rootSlot);
        for (NestedStep step : nestedPath) {
            path.add(step.kind().name() + '=' + Integer.toString(step.index()));
        }
        return path.toString();
    }

    private static String inventoryLocationType(PaperInventoryReference reference) {
        if (reference instanceof PaperInventoryReference.PlayerMain) {
            return "PLAYER_INVENTORY";
        }
        if (reference instanceof PaperInventoryReference.PlayerEnder) {
            return "ENDER_CHEST";
        }
        if (reference instanceof PaperInventoryReference.Block) {
            return "LOADED_BLOCK_INVENTORY";
        }
        if (reference instanceof PaperInventoryReference.EntityInventory) {
            return "LOADED_ENTITY_INVENTORY";
        }
        throw new IllegalArgumentException("Unsupported inventory reference");
    }

    private static String inventoryLocationKey(PaperInventoryReference reference) {
        if (reference instanceof PaperInventoryReference.PlayerMain player) {
            return player.playerId().toString();
        }
        if (reference instanceof PaperInventoryReference.PlayerEnder player) {
            return player.playerId().toString();
        }
        if (reference instanceof PaperInventoryReference.Block block) {
            return block.worldId() + ":" + block.x() + ':' + block.y() + ':' + block.z();
        }
        if (reference instanceof PaperInventoryReference.EntityInventory entity) {
            return entity.entityId().toString();
        }
        throw new IllegalArgumentException("Unsupported inventory reference");
    }

    private static ItemStack readAt(
            ItemStack item,
            List<NestedStep> path,
            int depth) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        if (depth == path.size()) {
            return item;
        }
        NestedStep step = path.get(depth);
        ItemMeta meta = item.getItemMeta();
        ItemStack child = switch (step.kind()) {
            case SHULKER -> shulkerChild(meta, step.index());
            case BUNDLE -> bundleChild(meta, step.index());
        };
        return readAt(child, path, depth + 1);
    }

    private static ItemStack shulkerChild(ItemMeta meta, int index) {
        if (!(meta instanceof BlockStateMeta blockMeta)) {
            return null;
        }
        BlockState state = blockMeta.getBlockState();
        if (!(state instanceof ShulkerBox shulker)
                || index >= shulker.getInventory().getSize()) {
            return null;
        }
        return shulker.getInventory().getItem(index);
    }

    private static ItemStack bundleChild(ItemMeta meta, int index) {
        if (!(meta instanceof BundleMeta bundle)) {
            return null;
        }
        List<ItemStack> items = bundle.getItems();
        return index < items.size() ? items.get(index) : null;
    }

    private static ItemStack replaceAt(
            ItemStack item,
            List<NestedStep> path,
            int depth,
            ItemStack replacement) {
        if (depth == path.size()) {
            return replacement.clone();
        }
        ItemStack parent = Objects.requireNonNull(item, "nested parent").clone();
        NestedStep step = path.get(depth);
        boolean applied = switch (step.kind()) {
            case SHULKER -> replaceShulkerChild(parent, path, depth, step.index(), replacement);
            case BUNDLE -> replaceBundleChild(parent, path, depth, step.index(), replacement);
        };
        return applied ? parent : null;
    }

    private static ItemStack removeAt(
            ItemStack item,
            List<NestedStep> path,
            int depth) {
        if (item == null || item.getType().isAir() || depth >= path.size()) {
            return null;
        }
        ItemStack parent = item.clone();
        NestedStep step = path.get(depth);
        boolean removed = switch (step.kind()) {
            case SHULKER -> removeShulkerChild(parent, path, depth, step.index());
            case BUNDLE -> removeBundleChild(parent, path, depth, step.index());
        };
        return removed ? parent : null;
    }

    private static boolean replaceShulkerChild(
            ItemStack parent,
            List<NestedStep> path,
            int depth,
            int index,
            ItemStack replacement) {
        ItemMeta meta = parent.getItemMeta();
        if (!(meta instanceof BlockStateMeta blockMeta)) {
            return false;
        }
        BlockState state = blockMeta.getBlockState();
        if (!(state instanceof ShulkerBox shulker)
                || index >= shulker.getInventory().getSize()) {
            return false;
        }
        ItemStack child = shulker.getInventory().getItem(index);
        ItemStack updated = replaceAt(child, path, depth + 1, replacement);
        if (updated == null) {
            return false;
        }
        shulker.getInventory().setItem(index, updated);
        blockMeta.setBlockState(shulker);
        return parent.setItemMeta(blockMeta);
    }

    private static boolean replaceBundleChild(
            ItemStack parent,
            List<NestedStep> path,
            int depth,
            int index,
            ItemStack replacement) {
        ItemMeta meta = parent.getItemMeta();
        if (!(meta instanceof BundleMeta bundle)) {
            return false;
        }
        List<ItemStack> items = new ArrayList<>(bundle.getItems());
        if (index >= items.size()) {
            return false;
        }
        ItemStack updated = replaceAt(items.get(index), path, depth + 1, replacement);
        if (updated == null) {
            return false;
        }
        items.set(index, updated);
        bundle.setItems(items);
        return parent.setItemMeta(bundle);
    }

    private static boolean removeShulkerChild(
            ItemStack parent,
            List<NestedStep> path,
            int depth,
            int index) {
        ItemMeta meta = parent.getItemMeta();
        if (!(meta instanceof BlockStateMeta blockMeta)) {
            return false;
        }
        BlockState state = blockMeta.getBlockState();
        if (!(state instanceof ShulkerBox shulker)
                || index >= shulker.getInventory().getSize()) {
            return false;
        }
        ItemStack child = shulker.getInventory().getItem(index);
        if (child == null || child.getType().isAir()) {
            return false;
        }
        if (depth + 1 == path.size()) {
            shulker.getInventory().setItem(index, null);
        } else {
            ItemStack updated = removeAt(child, path, depth + 1);
            if (updated == null) {
                return false;
            }
            shulker.getInventory().setItem(index, updated);
        }
        blockMeta.setBlockState(shulker);
        return parent.setItemMeta(blockMeta);
    }

    private static boolean removeBundleChild(
            ItemStack parent,
            List<NestedStep> path,
            int depth,
            int index) {
        ItemMeta meta = parent.getItemMeta();
        if (!(meta instanceof BundleMeta bundle)) {
            return false;
        }
        List<ItemStack> items = new ArrayList<>(bundle.getItems());
        if (index >= items.size()) {
            return false;
        }
        ItemStack child = items.get(index);
        if (child == null || child.getType().isAir()) {
            return false;
        }
        if (depth + 1 == path.size()) {
            items.remove(index);
        } else {
            ItemStack updated = removeAt(child, path, depth + 1);
            if (updated == null) {
                return false;
            }
            items.set(index, updated);
        }
        bundle.setItems(items);
        return parent.setItemMeta(bundle);
    }

    enum NestedKind {
        SHULKER,
        BUNDLE
    }

    record NestedStep(NestedKind kind, int index) {
        NestedStep {
            Objects.requireNonNull(kind, "kind");
            if (index < 0) {
                throw new IllegalArgumentException("Nested item index must not be negative");
            }
        }

        static NestedStep shulker(int index) {
            return new NestedStep(NestedKind.SHULKER, index);
        }

        static NestedStep bundle(int index) {
            return new NestedStep(NestedKind.BUNDLE, index);
        }
    }

    static final class Resolved implements PaperTemplateUpdateReference.Resolved {
        private final Inventory inventory;
        private final int rootSlot;
        private final List<NestedStep> nestedPath;
        private final ItemStack originalRoot;
        private final ItemStack capturedOriginalItem;

        private Resolved(
                Inventory inventory,
                int rootSlot,
                List<NestedStep> nestedPath,
                ItemStack originalRoot,
                ItemStack originalItem) {
            this.inventory = inventory;
            this.rootSlot = rootSlot;
            this.nestedPath = nestedPath;
            this.originalRoot = originalRoot;
            this.capturedOriginalItem = originalItem;
        }

        @Override
        public ItemStack originalItem() {
            return capturedOriginalItem.clone();
        }

        @Override
        public boolean replace(ItemStack replacement) {
            ItemStack updatedRoot = replaceAt(originalRoot, nestedPath, 0, replacement);
            if (updatedRoot == null) {
                return false;
            }
            inventory.setItem(rootSlot, updatedRoot);
            return true;
        }

        @Override
        public boolean remove() {
            ItemStack currentRoot = inventory.getItem(rootSlot);
            ItemStack currentItem = readAt(currentRoot, nestedPath, 0);
            if (currentItem == null
                    || !PaperItemFingerprint.of(currentItem)
                            .equals(PaperItemFingerprint.of(capturedOriginalItem))) {
                return false;
            }
            if (nestedPath.isEmpty()) {
                inventory.setItem(rootSlot, null);
                return true;
            }
            ItemStack updatedRoot = removeAt(currentRoot, nestedPath, 0);
            if (updatedRoot == null) {
                return false;
            }
            inventory.setItem(rootSlot, updatedRoot);
            return true;
        }

        @Override
        public ItemStack readStored() {
            ItemStack storedRoot = inventory.getItem(rootSlot);
            ItemStack stored = readAt(storedRoot, nestedPath, 0);
            return stored == null ? null : stored.clone();
        }

        @Override
        public boolean restore() {
            inventory.setItem(rootSlot, originalRoot.clone());
            ItemStack restored = readStored();
            return restored != null
                    && PaperItemFingerprint.of(restored)
                            .equals(PaperItemFingerprint.of(capturedOriginalItem));
        }
    }
}
