package net.enthusia.loreitems.paper;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;

/** Main-thread discovery snapshots, owned only by a single bounded scan pass. */
final class PaperTemplateUpdateDiscoveryPass {
    private final Inventory inventory;
    private final Map<PaperTemplateUpdateItemReference, Optional<ItemStack>> items =
            new ConcurrentHashMap<>();
    private final Map<PaperTemplateUpdateItemReference, NestedContents> containers =
            new ConcurrentHashMap<>();

    PaperTemplateUpdateDiscoveryPass(Inventory inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    ItemStack read(PaperTemplateUpdateItemReference reference) {
        Optional<ItemStack> cached = items.get(reference);
        if (cached != null) {
            return cached.orElse(null);
        }
        ItemStack item = resolve(reference);
        items.put(reference, Optional.ofNullable(item));
        return item;
    }

    private ItemStack resolve(PaperTemplateUpdateItemReference reference) {
        List<PaperTemplateUpdateItemReference.NestedStep> path = reference.nestedPath();
        if (path.isEmpty()) {
            return reference.rootSlot() < inventory.getSize()
                    ? inventory.getItem(reference.rootSlot()) : null;
        }
        PaperTemplateUpdateItemReference parent = new PaperTemplateUpdateItemReference(
                reference.inventoryReference(), reference.rootSlot(),
                path.subList(0, path.size() - 1));
        NestedContents contents = children(parent);
        PaperTemplateUpdateItemReference.NestedStep step = path.getLast();
        return contents.kind() == step.kind() && step.index() < contents.items().size()
                ? contents.items().get(step.index()) : null;
    }

    NestedContents children(PaperTemplateUpdateItemReference reference) {
        NestedContents cached = containers.get(reference);
        if (cached != null) {
            return cached;
        }
        NestedContents decoded = decode(read(reference));
        containers.put(reference, decoded);
        return decoded;
    }

    private static NestedContents decode(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return NestedContents.empty();
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta blockMeta
                && blockMeta.getBlockState() instanceof ShulkerBox shulker) {
            return new NestedContents(PaperTemplateUpdateItemReference.NestedKind.SHULKER,
                    Arrays.asList(shulker.getInventory().getContents()));
        }
        if (meta instanceof BundleMeta bundle) {
            return new NestedContents(PaperTemplateUpdateItemReference.NestedKind.BUNDLE,
                    bundle.getItems());
        }
        return NestedContents.empty();
    }

    record NestedContents(
            PaperTemplateUpdateItemReference.NestedKind kind, List<ItemStack> items) {
        static NestedContents empty() {
            return new NestedContents(null, List.of());
        }
    }
}
