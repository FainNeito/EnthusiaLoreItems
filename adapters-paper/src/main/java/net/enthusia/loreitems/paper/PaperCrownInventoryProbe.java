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
            int count = countInventoryAndCursor(player, definition, instance);
            if (count < 0) return Observation.DUPLICATE_OR_INVALID;
            return verdict(count, classify(player.getInventory().getHelmet(), definition, instance));
        } catch (RuntimeException failure) {
            return Observation.UNAVAILABLE;
        }
    }

    /** Matches across the full contents (which include the helmet slot) plus the cursor; -1 on any invalid match. */
    private int countInventoryAndCursor(Player player, UUID definition, UUID instance) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            int match = classify(item, definition, instance);
            if (match < 0) return -1;
            count += match;
        }
        int cursor = classify(player.getItemOnCursor(), definition, instance);
        return cursor < 0 ? -1 : count + cursor;
    }

    private static Observation verdict(int count, int head) {
        if (head < 0 || count > 1 || head > count) return Observation.DUPLICATE_OR_INVALID;
        if (count == 0) return Observation.MISSING;
        return head == 1 ? Observation.HEAD_ONLY : Observation.ELSEWHERE;
    }

    private int classify(ItemStack item, UUID definition, UUID instance) {
        if (item == null || item.getType().isAir()) return 0;
        ItemIdentityReadResult result = codec.readIdentity(item);
        if (result instanceof ItemIdentityReadResult.Invalid) return -1;
        return result instanceof ItemIdentityReadResult.Tracked tracked ? matchTracked(tracked, item, definition, instance) : 0;
    }

    private static int matchTracked(ItemIdentityReadResult.Tracked tracked, ItemStack item, UUID definition, UUID instance) {
        boolean sameInstance = tracked.identity().instanceId().value().equals(instance);
        boolean sameDefinition = tracked.identity().definitionId().value().equals(definition);
        if (!sameInstance && !sameDefinition) return 0;
        return sameInstance && sameDefinition && item.getType() == Material.NETHERITE_HELMET && item.getAmount() == 1 ? 1 : -1;
    }
}
