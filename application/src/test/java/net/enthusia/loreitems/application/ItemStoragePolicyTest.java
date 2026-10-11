package net.enthusia.loreitems.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ItemStoragePolicyTest {
    @Test
    void legacyDefaultsAndIndependentOverrides() {
        UUID definition = UUID.randomUUID();
        ItemStoragePolicy policy = new ItemStoragePolicy(
                false, false, true,
                Map.of(definition, Map.of(ItemStoragePolicy.Container.SHULKER, true,
                        ItemStoragePolicy.Container.ENDER_CHEST, false)));
        assertTrue(policy.allows(definition, ItemStoragePolicy.Container.SHULKER));
        assertFalse(policy.allows(definition, ItemStoragePolicy.Container.BUNDLE));
        assertFalse(policy.allows(definition, ItemStoragePolicy.Container.ENDER_CHEST));
        assertFalse(policy.allows(UUID.randomUUID(), ItemStoragePolicy.Container.SHULKER));
        assertTrue(policy.allows(UUID.randomUUID(), ItemStoragePolicy.Container.ENDER_CHEST));
    }
    @Test
    void reloadAtomicallyChangesPolicyAndRejectsWorkerChanges() {
        var initial = FoundationConfiguration.defaults();
        var atomic = new AtomicConfiguration(initial);
        var candidate = configured(initial.databaseQueueCapacity());
        assertTrue(atomic.replace(candidate).applied());
        assertFalse(atomic.current().storagePolicy().allows(UUID.randomUUID(), ItemStoragePolicy.Container.ENDER_CHEST));
        assertFalse(atomic.replace(configured(initial.databaseQueueCapacity() + 1)).applied());
        org.junit.jupiter.api.Assertions.assertEquals(candidate, atomic.current());
    }

    private static FoundationConfiguration configured(int queue) {
        var defaults = FoundationConfiguration.defaults();
        return new FoundationConfiguration(defaults.databaseBusyTimeoutMillis(), queue,
                defaults.databaseShutdownTimeoutSeconds(), defaults.deliveryClaimBatchSize(),
                defaults.deliveryClaimLeaseSeconds(), defaults.duplicateWarningIntervalSeconds(),
                defaults.defaultPageSize(), defaults.maxPageSize(), defaults.mutationBudgetPerTick(), true,
                ItemStoragePolicy.denyAll());
    }

    @Test
    // Intentionally mutate caller-owned, thread-confined maps to prove snapshot isolation.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    void callerMutationCannotChangeActiveOverrides() {
        UUID id = UUID.randomUUID();
        Map<ItemStoragePolicy.Container, Boolean> rules = new java.util.HashMap<>();
        rules.put(ItemStoragePolicy.Container.SHULKER, false);
        Map<UUID, Map<ItemStoragePolicy.Container, Boolean>> overrides = new java.util.HashMap<>();
        overrides.put(id, rules);
        var policy = new ItemStoragePolicy(true, true, true, overrides);
        rules.clear();
        overrides.clear();
        assertFalse(policy.allows(id, ItemStoragePolicy.Container.SHULKER));
    }

}
