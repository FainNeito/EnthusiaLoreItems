package net.enthusia.loreitems.paper;

import io.papermc.paper.event.player.PlayerPickBlockEvent;
import io.papermc.paper.event.player.PlayerPickEntityEvent;
import java.util.Objects;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.entity.Player;

/** Detects creative-mode copy operations that could duplicate tracked identity evidence. */
final class PaperCreativeIdentityProtection {
    private static final EquipmentSlot[] ARMOR_STAND_SLOTS = {
        EquipmentSlot.HAND,
        EquipmentSlot.OFF_HAND,
        EquipmentSlot.FEET,
        EquipmentSlot.LEGS,
        EquipmentSlot.CHEST,
        EquipmentSlot.HEAD
    };

    private static final long MOVE_NANOS = TimeUnit.SECONDS.toNanos(90);
    private static final int MAX_CREDITS = 256;
    private final Map<UUID, MoveCredit> movementCredits = new ConcurrentHashMap<>();
    private final PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();
    private final PaperTrackedItemCollector itemCollector;

    PaperCreativeIdentityProtection(PaperTrackedItemCollector itemCollector) {
        this.itemCollector = Objects.requireNonNull(itemCollector, "itemCollector");
    }

    boolean shouldCancelClone(InventoryClickEvent event) {
        return event.getAction() == InventoryAction.CLONE_STACK
                && hasIdentityEvidenceInTree(event.getCurrentItem());
    }

    boolean shouldCancelInventoryMutation(InventoryCreativeEvent event) {
        // A creative set-slot packet is not a safe clone operation. A tracked
        // placement requires the preceding removal of that exact source stack.
        if (!(event.getWhoClicked() instanceof Player player)) {
            return hasIdentityEvidenceInTree(event.getCursor());
        }
        ItemStack old = event.getCurrentItem();
        ItemStack incoming = event.getCursor();
        if (!hasIdentityEvidenceInTree(incoming)) {
            permitRelocation(player.getUniqueId(), old, incoming);
            return false;
        }
        if (samePhysicalIdentity(old, incoming)) {
            return false;
        }
        return !consumeRelocation(player.getUniqueId(), incoming);
    }

    private void permitRelocation(UUID playerId, ItemStack old, ItemStack replacement) {
        if (!hasIdentityEvidenceInTree(old) || !replacement.getType().isAir()) {
            return;
        }
        movementCredits.entrySet().removeIf(entry ->
                System.nanoTime() - entry.getValue().createdNanos() > MOVE_NANOS);
        if (movementCredits.size() >= MAX_CREDITS && !movementCredits.containsKey(playerId)) {
            return;
        }
        movementCredits.put(playerId, new MoveCredit(old.clone(), System.nanoTime()));
    }

    private boolean consumeRelocation(UUID playerId, ItemStack incoming) {
        MoveCredit credit = movementCredits.remove(playerId);
        return credit != null
                && System.nanoTime() - credit.createdNanos() <= MOVE_NANOS
                && samePhysicalIdentity(credit.stack(), incoming);
    }

    private boolean samePhysicalIdentity(ItemStack first, ItemStack second) {
        if (!hasIdentityEvidenceInTree(first) || !hasIdentityEvidenceInTree(second)) {
            return false;
        }
        var before = identityCodec.readIdentity(first);
        var after = identityCodec.readIdentity(second);
        if (before instanceof net.enthusia.loreitems.application.ItemIdentityReadResult.Tracked a
                && after instanceof net.enthusia.loreitems.application.ItemIdentityReadResult.Tracked b) {
            return first.getType() == second.getType()
                    && first.getAmount() == second.getAmount()
                    && a.identity().equals(b.identity());
        }
        return first.isSimilar(second);
    }

    void clear() {
        movementCredits.clear();
    }

    private record MoveCredit(ItemStack stack, long createdNanos) {}

    boolean shouldCancelPickBlock(PlayerPickBlockEvent event) {
        return event.isIncludeData()
                && event.getBlock().getState() instanceof InventoryHolder holder
                && containsIdentityEvidenceInTree(holder.getInventory());
    }

    boolean shouldCancelPickEntity(PlayerPickEntityEvent event) {
        return entityContainsIdentityEvidence(event.getEntity());
    }

    private boolean containsIdentityEvidenceInTree(Inventory inventory) {
        for (ItemStack item : inventory.getContents()) {
            if (hasIdentityEvidenceInTree(item)) {
                return true;
            }
        }
        return false;
    }

    private boolean entityContainsIdentityEvidence(Entity entity) {
        if (entity instanceof Item item) {
            return hasIdentityEvidenceInTree(item.getItemStack());
        }
        if (entity instanceof ItemFrame frame) {
            return hasIdentityEvidenceInTree(frame.getItem());
        }
        if (entity instanceof ItemDisplay display) {
            return hasIdentityEvidenceInTree(display.getItemStack());
        }
        if (entity instanceof ArmorStand stand) {
            for (EquipmentSlot slot : ARMOR_STAND_SLOTS) {
                if (hasIdentityEvidenceInTree(stand.getEquipment().getItem(slot))) {
                    return true;
                }
            }
            return false;
        }
        return entity instanceof InventoryHolder holder
                && containsIdentityEvidenceInTree(holder.getInventory());
    }

    private boolean hasIdentityEvidenceInTree(ItemStack item) {
        return itemCollector.hasIdentityEvidence(item);
    }
}
