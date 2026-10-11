package net.enthusia.loreitems.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.enthusia.loreitems.application.FoundationConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FoundationConfigurationLoaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsAndLoadsPackagedDefaults() throws Exception {
        FoundationConfiguration loaded =
                new FoundationConfigurationLoader(temporaryDirectory).loadOrCreate();

        assertEquals(FoundationConfiguration.defaults(), loaded);
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("config.yml")));
    }

    @Test
    void rejectsUnknownKeysInsteadOfIgnoringTypos() throws Exception {
        Files.writeString(
                temporaryDirectory.resolve("config.yml"),
                "database-queue-capcity: 500\n",
                StandardCharsets.UTF_8);

        assertThrows(
                IllegalArgumentException.class,
                () -> new FoundationConfigurationLoader(temporaryDirectory).loadOrCreate());
    }
    @Test
    void parsesIndependentDefaultsOverridesAndInheritance() throws Exception {
        String id = "11111111-1111-1111-1111-111111111111";
        Files.writeString(temporaryDirectory.resolve("config.yml"),
                "shared-containers-allowed: false\n"
                + "storage-defaults.bundles: true\n"
                + "item-storage." + id + ".shulkers: true\n"
                + "item-storage." + id + ".bundles: inherit\n"
                + "item-storage." + id + ".ender-chests: false\n");
        var policy = new FoundationConfigurationLoader(temporaryDirectory).loadOrCreate().storagePolicy();
        var definition = java.util.UUID.fromString(id);
        assertTrue(policy.allows(definition, net.enthusia.loreitems.application.ItemStoragePolicy.Container.SHULKER));
        assertTrue(policy.allows(definition, net.enthusia.loreitems.application.ItemStoragePolicy.Container.BUNDLE));
        org.junit.jupiter.api.Assertions.assertFalse(policy.allows(definition,
                net.enthusia.loreitems.application.ItemStoragePolicy.Container.ENDER_CHEST));
    }

    @Test
    void rejectsTypoDestinationInvalidValueAndMalformedUuid() throws Exception {
        for (String entry : java.util.List.of(
                "item-storage.11111111-1111-1111-1111-111111111111.shulker: false",
                "item-storage.11111111-1111-1111-1111-111111111111.shulkers: maybe",
                "item-storage.1-1-1-1-1.shulkers: true",
                "item-storage.11111111-1111-1111-1111-111111111111.shulkers.extra: true")) {
            Files.writeString(temporaryDirectory.resolve("config.yml"), entry);
            assertThrows(IllegalArgumentException.class,
                    () -> new FoundationConfigurationLoader(temporaryDirectory).loadOrCreate());
        }
    }

}
