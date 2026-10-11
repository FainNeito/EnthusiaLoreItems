package net.enthusia.loreitems.plugin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.enthusia.loreitems.application.FoundationConfiguration;
import org.junit.jupiter.api.Test;

class StartupConfigurationGateTest {
    @Test
    void remainsFailClosedUntilCurrentStartupConfigurationIsPublished() {
        StartupConfigurationGate gate = new StartupConfigurationGate();
        FoundationConfiguration allowed = configuration(true);

        assertFalse(gate.sharedContainersAllowed(allowed));

        gate.publish();

        assertTrue(gate.sharedContainersAllowed(allowed));
    }

    @Test
    void resetFencesStalePermissiveSnapshotAcrossSameInstanceReenable() {
        StartupConfigurationGate gate = new StartupConfigurationGate();
        FoundationConfiguration allowed = configuration(true);
        FoundationConfiguration denied = configuration(false);
        gate.publish();
        assertTrue(gate.sharedContainersAllowed(allowed));

        gate.reset();

        assertFalse(gate.sharedContainersAllowed(allowed));
        gate.publish();
        assertFalse(gate.sharedContainersAllowed(denied));
    }

    @Test
    void storagePolicyIsFencedUntilPublishedAndAfterReset() {
        var gate = new StartupConfigurationGate();
        var destination = net.enthusia.loreitems.application.ItemStoragePolicy.Container.ENDER_CHEST;
        var id = java.util.UUID.randomUUID();
        assertFalse(gate.storagePolicy(configuration(true)).allows(id, destination));
        gate.publish();
        assertTrue(gate.storagePolicy(configuration(true)).allows(id, destination));
        gate.reset();
        assertFalse(gate.storagePolicy(configuration(true)).allows(id, destination));
    }

    private static FoundationConfiguration configuration(boolean sharedContainersAllowed) {
        FoundationConfiguration defaults = FoundationConfiguration.defaults();
        return new FoundationConfiguration(
                defaults.databaseBusyTimeoutMillis(),
                defaults.databaseQueueCapacity(),
                defaults.databaseShutdownTimeoutSeconds(),
                defaults.deliveryClaimBatchSize(),
                defaults.deliveryClaimLeaseSeconds(),
                defaults.duplicateWarningIntervalSeconds(),
                defaults.defaultPageSize(),
                defaults.maxPageSize(),
                defaults.mutationBudgetPerTick(),
                sharedContainersAllowed);
    }
}
