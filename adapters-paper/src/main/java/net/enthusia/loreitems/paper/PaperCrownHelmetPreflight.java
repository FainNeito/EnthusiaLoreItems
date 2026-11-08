package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Point-in-time comparison evidence only. It does not reserve a slot or authorize a swap. */
final class PaperCrownHelmetPreflight {
    enum Status { OFFLINE, EMPTY, UNTRACKED, TRACKED_OR_INVALID, UNAVAILABLE }
    enum Comparison { UNCHANGED, CHANGED, HOLD }
    record Snapshot(UUID playerId, Status status, String fingerprint) {
        Snapshot {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(status, "status");
            if ((status == Status.UNTRACKED) != (fingerprint != null)) {
                throw new IllegalArgumentException("Only an untracked item may carry a fingerprint");
            }
        }
    }

    private final PaperItemCodecThreadGuard guard;
    private final PaperItemIdentityCodec codec;

    PaperCrownHelmetPreflight(PaperItemCodecThreadGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.codec = new PaperItemIdentityCodec(guard);
    }

    Snapshot observe(Player player, UUID expectedPlayer) {
        guard.requirePrimaryThread();
        Objects.requireNonNull(expectedPlayer, "expectedPlayer");
        if (player == null || !player.isOnline()) return new Snapshot(expectedPlayer, Status.OFFLINE, null);
        if (!expectedPlayer.equals(player.getUniqueId())) return new Snapshot(expectedPlayer, Status.UNAVAILABLE, null);
        try {
            ItemStack helmet = player.getInventory().getHelmet();
            if (helmet == null || helmet.getType().isAir()) return new Snapshot(expectedPlayer, Status.EMPTY, null);
            if (!(codec.readIdentity(helmet) instanceof ItemIdentityReadResult.Untracked)) {
                return new Snapshot(expectedPlayer, Status.TRACKED_OR_INVALID, null);
            }
            return new Snapshot(expectedPlayer, Status.UNTRACKED, PaperItemFingerprint.of(helmet));
        } catch (RuntimeException failure) {
            return new Snapshot(expectedPlayer, Status.UNAVAILABLE, null);
        }
    }

    Comparison compare(Snapshot before, Snapshot current) {
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(current, "current");
        if (!before.playerId().equals(current.playerId())) return Comparison.HOLD;
        if ((before.status() != Status.EMPTY && before.status() != Status.UNTRACKED)
                || (current.status() != Status.EMPTY && current.status() != Status.UNTRACKED)) return Comparison.HOLD;
        return before.equals(current) ? Comparison.UNCHANGED : Comparison.CHANGED;
    }
}
