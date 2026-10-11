package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.function.Supplier;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.ItemStoragePolicy;
import net.enthusia.loreitems.application.ItemStoragePolicy.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;

/** Cancels only insertion; never rewrites or confiscates stored items. */
final class PaperItemStorageProtection {
    private static final int MAX_DEPTH = 8;
    private static final int MAX_ITEMS = 512;
    private final Supplier<ItemStoragePolicy> policies;
    private final PaperItemIdentityCodec identities = new PaperItemIdentityCodec();

    PaperItemStorageProtection(Supplier<ItemStoragePolicy> policies) {
        this.policies = Objects.requireNonNull(policies);
    }

    boolean cancelClick(InventoryClickEvent event) {
        ItemStoragePolicy policy = policies.get();
        Container destination = container(event.getView().getTopInventory().getType());
        int topSize = event.getView().getTopInventory().getSize();
        if (destination != null && prohibited(insertedItem(event, topSize), destination, policy)) {
            return true;
        }
        // Bundle extraction is legal. SWAP_WITH_CURSOR represents inserting into a bundle.
        if (event.getAction() != InventoryAction.SWAP_WITH_CURSOR) {
            return false;
        }
        return (bundle(event.getCurrentItem()) && prohibited(event.getCursor(), Container.BUNDLE, policy))
                || (bundle(event.getCursor()) && prohibited(event.getCurrentItem(), Container.BUNDLE, policy));
    }

    boolean cancelDrag(InventoryDragEvent event) {
        Container destination = container(event.getView().getTopInventory().getType());
        int topSize = event.getView().getTopInventory().getSize();
        return destination != null
                && event.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot < topSize)
                && prohibited(event.getOldCursor(), destination, policies.get());
    }

    boolean cancelMove(InventoryMoveItemEvent event) {
        Container destination = container(event.getDestination().getType());
        return destination != null && prohibited(event.getItem(), destination, policies.get());
    }

    private static ItemStack insertedItem(InventoryClickEvent event, int topSize) {
        if (event.getRawSlot() < 0) {
            return null;
        }
        if (event.getRawSlot() >= topSize) {
            return event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY
                    ? event.getCurrentItem() : null;
        }
        return switch (event.getAction()) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> event.getCursor();
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> event.getHotbarButton() >= 0
                    ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton())
                    : event.getWhoClicked().getInventory().getItemInOffHand();
            default -> null;
        };
    }

    private boolean prohibited(ItemStack item, Container destination, ItemStoragePolicy policy) {
        return prohibited(item, destination, policy, 0, new int[] { MAX_ITEMS });
    }

    private boolean prohibited(ItemStack item, Container destination, ItemStoragePolicy policy,
            int depth, int[] budget) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        int remaining = budget[0];
        budget[0] = remaining - 1;
        if (remaining <= 0 || depth > MAX_DEPTH) {
            return true;
        }
        ItemIdentityReadResult result = identities.readIdentity(item);
        if (prohibitedIdentity(result, destination, policy)) {
            return true;
        }
        if (!item.hasItemMeta()) {
            return false;
        }
        return prohibitedNested(item, destination, policy, depth, budget);
    }

    private static boolean prohibitedIdentity(ItemIdentityReadResult result,
            Container destination, ItemStoragePolicy policy) {
        return result instanceof ItemIdentityReadResult.Invalid
                || (result instanceof ItemIdentityReadResult.Tracked tracked
                    && !policy.allows(tracked.identity().definitionId().value(), destination));
    }

    private boolean prohibitedNested(ItemStack item, Container destination, ItemStoragePolicy policy,
            int depth, int[] budget) {
        var meta = item.getItemMeta();
        if (meta instanceof BundleMeta bundle) {
            for (ItemStack nested : bundle.getItems()) {
                if (prohibited(nested, destination, policy, depth + 1, budget)) {
                    return true;
                }
            }
        }
        if (meta instanceof BlockStateMeta block && block.getBlockState() instanceof ShulkerBox shulker) {
            for (ItemStack nested : shulker.getInventory().getContents()) {
                if (prohibited(nested, destination, policy, depth + 1, budget)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean bundle(ItemStack item) {
        return item != null && (item.getType().name().equals("BUNDLE")
                || item.getType().name().endsWith("_BUNDLE"));
    }

    private static Container container(InventoryType type) {
        return switch (type) {
            case SHULKER_BOX -> Container.SHULKER;
            case ENDER_CHEST -> Container.ENDER_CHEST;
            default -> null;
        };
    }
}
