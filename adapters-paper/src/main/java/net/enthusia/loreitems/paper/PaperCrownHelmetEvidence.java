package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Main-thread evidence capture, with no retained Bukkit object or inventory mutation. */
final class PaperCrownHelmetEvidence {
    private static final int MAX_BYTES = 1024 * 1024;
    private final PaperItemCodecThreadGuard guard;
    private final PaperItemIdentityCodec codec;

    PaperCrownHelmetEvidence(PaperItemCodecThreadGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.codec = new PaperItemIdentityCodec(guard);
    }

    Optional<Snapshot> capture(Player player, UUID expectedPlayer) {
        guard.requirePrimaryThread();
        Objects.requireNonNull(expectedPlayer, "expectedPlayer");
        try {
            if (player == null || !player.isOnline() || !expectedPlayer.equals(player.getUniqueId())) return Optional.empty();
            ItemStack helmet = player.getInventory().getHelmet();
            if (helmet == null || helmet.getType().isAir()) return Optional.of(new Snapshot(expectedPlayer, true, new byte[0]));
            if (!(codec.readIdentity(helmet) instanceof ItemIdentityReadResult.Untracked)) return Optional.empty();
            byte[] bytes = helmet.serializeAsBytes();
            if (bytes.length == 0 || bytes.length > MAX_BYTES) return Optional.empty();
            return Optional.of(new Snapshot(expectedPlayer, false, bytes));
        } catch (RuntimeException failure) {
            return Optional.empty();
        }
    }

    /** Serialized evidence is not a recoverable-item authority or authorization to reconstruct. */
    static final class Snapshot {
        private final UUID playerId;
        private final boolean empty;
        private final byte[] bytes;

        Snapshot(UUID playerId, boolean empty, byte[] bytes) {
            this.playerId = Objects.requireNonNull(playerId, "playerId");
            this.empty = empty;
            this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
            if ((this.bytes.length == 0) != empty) throw new IllegalArgumentException("Snapshot kind and payload disagree");
        }

        UUID playerId() { return playerId; }
        boolean empty() { return empty; }
        byte[] bytes() { return bytes.clone(); }
    }
}
