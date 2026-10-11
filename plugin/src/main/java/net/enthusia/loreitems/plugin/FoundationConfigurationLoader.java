package net.enthusia.loreitems.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.enthusia.loreitems.application.FoundationConfiguration;

final class FoundationConfigurationLoader {
    private static final int MIN_SEPARATOR_INDEX = 1;
    private static final int NO_COMMENT_INDEX = -1;

    private static final Set<String> KNOWN_KEYS = Set.of(
            "database-busy-timeout-millis",
            "database-queue-capacity",
            "database-shutdown-timeout-seconds",
            "delivery-claim-batch-size",
            "delivery-claim-lease-seconds",
            "duplicate-warning-interval-seconds",
            "default-page-size",
            "max-page-size",
            "mutation-budget-per-tick",
            "shared-containers-allowed",
            "storage-defaults.shulkers", "storage-defaults.bundles", "storage-defaults.ender-chests");

    private final Path dataDirectory;

    FoundationConfigurationLoader(Path dataDirectory) {
        this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
    }

    FoundationConfiguration loadOrCreate() throws IOException {
        Files.createDirectories(dataDirectory);
        Path configurationFile = dataDirectory.resolve("config.yml");
        if (Files.notExists(configurationFile)) {
            copyDefault(configurationFile);
        }
        return parse(Files.readAllLines(configurationFile, StandardCharsets.UTF_8));
    }

    // Parsing is single-threaded and the map never escapes this method.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private static FoundationConfiguration parse(Iterable<String> lines) {
        FoundationConfiguration defaults = FoundationConfiguration.defaults();
        Map<String, String> values = new HashMap<>();
        int lineNumber = 0;
        for (String rawLine : lines) {
            lineNumber++;
            String line = stripComment(rawLine).strip();
            if (line.isEmpty()) {
                continue;
            }
            int separator = line.indexOf(':');
            if (separator < MIN_SEPARATOR_INDEX) {
                throw new IllegalArgumentException("Invalid config line " + lineNumber);
            }
            String key = line.substring(0, separator).strip();
            String value = line.substring(separator + 1).strip();
            if (!KNOWN_KEYS.contains(key) && !key.startsWith("item-storage.")) {
                throw new IllegalArgumentException("Unknown config key: " + key);
            }
            if (value.isEmpty()) {
                throw new IllegalArgumentException("Missing value for config key: " + key);
            }
            if (values.putIfAbsent(key, value) != null) {
                throw new IllegalArgumentException("Duplicate config key: " + key);
            }
        }
        return new FoundationConfiguration(
                integer(values, "database-busy-timeout-millis", defaults.databaseBusyTimeoutMillis()),
                integer(values, "database-queue-capacity", defaults.databaseQueueCapacity()),
                integer(values, "database-shutdown-timeout-seconds", defaults.databaseShutdownTimeoutSeconds()),
                integer(values, "delivery-claim-batch-size", defaults.deliveryClaimBatchSize()),
                integer(values, "delivery-claim-lease-seconds", defaults.deliveryClaimLeaseSeconds()),
                integer(values, "duplicate-warning-interval-seconds", defaults.duplicateWarningIntervalSeconds()),
                integer(values, "default-page-size", defaults.defaultPageSize()),
                integer(values, "max-page-size", defaults.maxPageSize()),
                integer(values, "mutation-budget-per-tick", defaults.mutationBudgetPerTick()),
                bool(values, "shared-containers-allowed", defaults.sharedContainersAllowed()),
                ItemStorageConfiguration.parse(values));
    }

    private void copyDefault(Path destination) throws IOException {
        try (InputStream stream = FoundationConfigurationLoader.class
                .getResourceAsStream("/config.yml")) {
            if (stream == null) {
                throw new IOException("Missing packaged config.yml");
            }
            Files.copy(stream, destination);
        }
    }

    private static int integer(Map<String, String> values, String key, int defaultValue) {
        String value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("Config key " + key + " must be an integer", exception);
        }
    }

    private static boolean bool(Map<String, String> values, String key, boolean defaultValue) {
        String value = values.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new IllegalArgumentException("Config key " + key + " must be true or false");
    }

    private static String stripComment(String line) {
        int comment = line.indexOf('#');
        return comment == NO_COMMENT_INDEX ? line : line.substring(0, comment);
    }
}
