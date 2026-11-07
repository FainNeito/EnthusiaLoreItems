package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Bounded, read-only observation of the online player's own inventory and cursor. */
final class PaperCrownInventoryProbe {
    enum Observation { OFFLINE, HEAD_ONLY, ELSEWHERE, MISSING, DUPLICATE_OR_INVALID, UNAVAILABLE }
    private final PaperItemCodecThreadGuard guard;
    private final PaperItemIdentityCodec codec;

    PaperCrownInventoryProbe(PaperItemCodecThreadGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.codec = new PaperItemIdentityCodec(guard);
    }

    Observation observe(Player player, UUID expectedPlayer, UUID definition, UUID instance) {
        guard.requirePrimaryThread();
        Objects.requireNonNull(expectedPlayer, "expectedPlayer");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(instance, "instance");
        if (player == null || !player.isOnline()) return Observation.OFFLINE;
        if (!expectedPlayer.equals(player.getUniqueId())) return Observation.UNAVAILABLE;
        try {
            ItemStack[] contents = player.getInventory().getContents();
            int count = 0;
            for (ItemStack item : contents) {
                int match = classify(item, definition, instance);
                if (match < 0) return Observation.DUPLICATE_OR_INVALID;
                count += match;
            }
            int cursor = classify(player.getItemOnCursor(), definition, instance);
            if (cursor < 0) return Observation.DUPLICATE_OR_INVALID;
            count += cursor;
            int head = classify(player.getInventory().getHelmet(), definition, instance);
            if (head < 0 || count > 1 || head > count) return Observation.DUPLICATE_OR_INVALID;
            if (count == 0) return Observation.MISSING;
            return head == 1 ? Observation.HEAD_ONLY : Observation.ELSEWHERE;
        } catch (RuntimeException failure) {
            return Observation.UNAVAILABLE;
        }
    }

    private int classify(ItemStack item, UUID definition, UUID instance) {
        if (item == null || item.getType().isAir()) return 0;
        ItemIdentityReadResult result = codec.readIdentity(item);
        if (result instanceof ItemIdentityReadResult.Invalid) return -1;
        if (result instanceof ItemIdentityReadResult.Tracked tracked) {
            boolean sameInstance = tracked.identity().instanceId().value().equals(instance);
            boolean sameDefinition = tracked.identity().definitionId().value().equals(definition);
            if (sameInstance || sameDefinition) {
                return sameInstance && sameDefinition && item.getType() == Material.NETHERITE_HELMET && item.getAmount() == 1 ? 1 : -1;
            }
        }
        return 0;
    }
}
