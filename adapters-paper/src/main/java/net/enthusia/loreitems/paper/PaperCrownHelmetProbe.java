package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Point-in-time observation only; no result authorizes equipping, deleting, or recovery. */
final class PaperCrownHelmetProbe {
    enum Observation { OFFLINE, EMPTY, EXPECTED_INSTANCE, OTHER_TRACKED_ITEM, UNTRACKED_ITEM, INVALID_IDENTITY }

    private final PaperItemCodecThreadGuard threadGuard;
    private final PaperItemIdentityCodec identityCodec;

    PaperCrownHelmetProbe(PaperItemCodecThreadGuard threadGuard) {
        this.threadGuard = Objects.requireNonNull(threadGuard, "threadGuard");
        this.identityCodec = new PaperItemIdentityCodec(threadGuard);
    }

    Observation observe(Player player, UUID expectedDefinition, UUID expectedInstance) {
        threadGuard.requirePrimaryThread();
        Objects.requireNonNull(expectedDefinition, "expectedDefinition");
        Objects.requireNonNull(expectedInstance, "expectedInstance");
        if (player == null || !player.isOnline()) return Observation.OFFLINE;
        ItemStack helmet = player.getInventory().getHelmet();
        if (helmet == null || helmet.getType().isAir()) return Observation.EMPTY;
        ItemIdentityReadResult result = identityCodec.readIdentity(helmet);
        if (result instanceof ItemIdentityReadResult.Tracked tracked) {
            return classifyTracked(tracked, helmet.getAmount(), expectedDefinition, expectedInstance);
        }
        if (result instanceof ItemIdentityReadResult.Invalid) return Observation.INVALID_IDENTITY;
        return Observation.UNTRACKED_ITEM;
    }

    private static Observation classifyTracked(ItemIdentityReadResult.Tracked tracked, int amount, UUID expectedDefinition,
            UUID expectedInstance) {
        boolean sameInstance = tracked.identity().instanceId().value().equals(expectedInstance);
        boolean sameDefinition = tracked.identity().definitionId().value().equals(expectedDefinition);
        if (amount != 1 && (sameInstance || sameDefinition)) return Observation.INVALID_IDENTITY;
        return sameInstance && sameDefinition ? Observation.EXPECTED_INSTANCE : Observation.OTHER_TRACKED_ITEM;
    }
}
