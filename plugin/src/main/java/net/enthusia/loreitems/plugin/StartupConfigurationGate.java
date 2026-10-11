package net.enthusia.loreitems.plugin;

import java.util.Objects;
import net.enthusia.loreitems.application.FoundationConfiguration;

/** Keeps live protection policy fail-closed until the current startup config is published. */
final class StartupConfigurationGate {
    private volatile boolean published;

    void reset() {
        published = false;
    }

    void publish() {
        published = true;
    }

    net.enthusia.loreitems.application.ItemStoragePolicy storagePolicy(FoundationConfiguration configuration) {
        return published ? Objects.requireNonNull(configuration).storagePolicy()
                : net.enthusia.loreitems.application.ItemStoragePolicy.denyAll();
    }

    boolean sharedContainersAllowed(FoundationConfiguration configuration) {
        return published
                && Objects.requireNonNull(configuration, "configuration").sharedContainersAllowed();
    }
}
