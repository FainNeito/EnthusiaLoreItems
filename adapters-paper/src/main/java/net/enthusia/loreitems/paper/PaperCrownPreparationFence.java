package net.enthusia.loreitems.paper;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Main-thread callback fence. It is not durable and cannot prove physical custody. */
final class PaperCrownPreparationFence {
    private static final int MAX_ACTIVE = 256;
    private final PaperItemCodecThreadGuard guard;
    private record Lease(UUID token, boolean valid) {}
    private final Map<UUID, Lease> active = new HashMap<>();

    PaperCrownPreparationFence(PaperItemCodecThreadGuard guard) {
        this.guard = Objects.requireNonNull(guard, "guard");
    }

    UUID begin(UUID player) {
        guard.requirePrimaryThread();
        Objects.requireNonNull(player, "player");
        if (active.containsKey(player) || active.size() >= MAX_ACTIVE) return null;
        UUID token = UUID.randomUUID();
        active.put(player, new Lease(token, true));
        return token;
    }

    boolean valid(UUID player, UUID token) {
        guard.requirePrimaryThread();
        Lease lease = active.get(player);
        return token != null && lease != null && lease.valid() && token.equals(lease.token());
    }

    void invalidate(UUID player) {
        guard.requirePrimaryThread();
        active.computeIfPresent(player, (ignored, lease) -> new Lease(lease.token(), false));
    }

    /** Call on the primary thread before shutdown or a provider-generation change. */
    void invalidateAll() {
        guard.requirePrimaryThread();
        active.replaceAll((ignored, lease) -> new Lease(lease.token(), false));
    }

    void end(UUID player, UUID token) {
        guard.requirePrimaryThread();
        Lease lease = active.get(player);
        if (lease != null && lease.token().equals(token)) active.remove(player);
    }
}
