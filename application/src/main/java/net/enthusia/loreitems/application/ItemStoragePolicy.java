package net.enthusia.loreitems.application;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable, server-owned policy. Missing definition/container entries inherit defaults. */
public record ItemStoragePolicy(
        boolean shulkersAllowed,
        boolean bundlesAllowed,
        boolean enderChestsAllowed,
        Map<UUID, Map<Container, Boolean>> overrides) {
    public enum Container { SHULKER, BUNDLE, ENDER_CHEST }
    public static final int MAX_OVERRIDES = 1_024;

    public ItemStoragePolicy {
        Objects.requireNonNull(overrides, "overrides");
        if (overrides.size() > MAX_OVERRIDES) {
            throw new IllegalArgumentException("At most 1024 item storage overrides are supported");
        }
        overrides = copyOverrides(overrides);
    }

    // The mutable copy is method-local; only deeply immutable maps are published.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private static Map<UUID, Map<Container, Boolean>> copyOverrides(
            Map<UUID, Map<Container, Boolean>> overrides) {
        Map<UUID, Map<Container, Boolean>> copied = new HashMap<>();
        overrides.forEach((id, values) -> copied.put(Objects.requireNonNull(id), Map.copyOf(values)));
        return Map.copyOf(copied);
    }

    public static ItemStoragePolicy legacy(boolean sharedAllowed) {
        return new ItemStoragePolicy(sharedAllowed, sharedAllowed, true, Map.of());
    }

    public static ItemStoragePolicy denyAll() {
        return new ItemStoragePolicy(false, false, false, Map.of());
    }

    public boolean allows(UUID definition, Container destination) {
        Objects.requireNonNull(destination, "destination");
        Boolean override = overrides.getOrDefault(definition, Map.of()).get(destination);
        if (override != null) {
            return override;
        }
        return switch (destination) {
            case SHULKER -> shulkersAllowed;
            case BUNDLE -> bundlesAllowed;
            case ENDER_CHEST -> enderChestsAllowed;
        };
    }
}
