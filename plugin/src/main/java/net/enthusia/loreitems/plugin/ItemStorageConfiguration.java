package net.enthusia.loreitems.plugin;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;
import net.enthusia.loreitems.application.ItemStoragePolicy;
import net.enthusia.loreitems.application.ItemStoragePolicy.Container;

/** Parses the existing flat configuration format off the server thread. */
final class ItemStorageConfiguration {
    private static final int OVERRIDE_KEY_PARTS = 3;
    private ItemStorageConfiguration() {}

    // Candidate maps are confined to this parse and copied before atomic publication.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    static ItemStoragePolicy parse(Map<String, String> values) {
        boolean shared = booleanValue(values.getOrDefault("shared-containers-allowed", "true"));
        Map<UUID, Map<Container, Boolean>> overrides = new HashMap<>();
        Set<String> seen = new HashSet<>();
        values.forEach((key, value) -> {
            if (key.startsWith("item-storage.")) {
                addOverride(overrides, seen, key, value);
            }
        });
        return new ItemStoragePolicy(
                defaultValue(values, "shulkers", shared),
                defaultValue(values, "bundles", shared),
                defaultValue(values, "ender-chests", true), overrides);
    }

    // EnumMap is local to the unpublished candidate, never concurrently mutated.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private static void addOverride(Map<UUID, Map<Container, Boolean>> overrides, Set<String> seen,
            String key, String value) {
        String[] parts = key.split("\\.", -1);
        if (parts.length != OVERRIDE_KEY_PARTS) {
            throw new IllegalArgumentException("Expected item-storage.<definition UUID>.<container>: " + key);
        }
        UUID id = UUID.fromString(parts[1]);
        if (!id.toString().equalsIgnoreCase(parts[1])) {
            throw new IllegalArgumentException("Storage override needs a complete definition UUID: " + key);
        }
        Container container = container(parts[2]);
        if (!seen.add(id + "." + container)) {
            throw new IllegalArgumentException("Duplicate storage override: " + key);
        }
        if (!overrides.containsKey(id) && overrides.size() >= ItemStoragePolicy.MAX_OVERRIDES) {
            throw new IllegalArgumentException("At most 1024 item storage overrides are supported");
        }
        Map<Container, Boolean> rules = overrides.computeIfAbsent(id, ignored -> new EnumMap<>(Container.class));
        if (value.equalsIgnoreCase("inherit")) {
            return;
        }
        if (rules.putIfAbsent(container, booleanValue(value)) != null) {
            throw new IllegalArgumentException("Duplicate storage override: " + key);
        }
    }

    private static Container container(String value) {
        return switch (value) {
            case "shulkers" -> Container.SHULKER;
            case "bundles" -> Container.BUNDLE;
            case "ender-chests" -> Container.ENDER_CHEST;
            default -> throw new IllegalArgumentException("Unknown storage destination: " + value);
        };
    }

    private static boolean defaultValue(Map<String, String> values, String container, boolean fallback) {
        String value = values.get("storage-defaults." + container);
        return value == null || value.equalsIgnoreCase("inherit") ? fallback : booleanValue(value);
    }

    private static boolean booleanValue(String value) {
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("Storage policy must be true, false, or inherit");
    }
}
