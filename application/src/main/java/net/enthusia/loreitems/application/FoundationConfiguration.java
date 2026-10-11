package net.enthusia.loreitems.application;

public record FoundationConfiguration(
        int databaseBusyTimeoutMillis,
        int databaseQueueCapacity,
        int databaseShutdownTimeoutSeconds,
        int deliveryClaimBatchSize,
        int deliveryClaimLeaseSeconds,
        int duplicateWarningIntervalSeconds,
        int defaultPageSize,
        int maxPageSize,
        int mutationBudgetPerTick,
        boolean sharedContainersAllowed,
        ItemStoragePolicy storagePolicy) {

    public FoundationConfiguration(int busy, int queue, int shutdown, int batch, int lease,
            int warning, int page, int maxPage, int budget, boolean shared) {
        this(busy, queue, shutdown, batch, lease, warning, page, maxPage, budget, shared,
                ItemStoragePolicy.legacy(shared));
    }

    public FoundationConfiguration {
        java.util.Objects.requireNonNull(storagePolicy, "storagePolicy");
        requireRange(databaseBusyTimeoutMillis, 100, 60_000, "databaseBusyTimeoutMillis");
        requireRange(databaseQueueCapacity, 16, 4_096, "databaseQueueCapacity");
        requireRange(databaseShutdownTimeoutSeconds, 1, 60, "databaseShutdownTimeoutSeconds");
        requireRange(deliveryClaimBatchSize, 1, 200, "deliveryClaimBatchSize");
        requireRange(deliveryClaimLeaseSeconds, 5, 300, "deliveryClaimLeaseSeconds");
        requireRange(duplicateWarningIntervalSeconds, 60, 3_600, "duplicateWarningIntervalSeconds");
        requireRange(defaultPageSize, 1, PageRequest.MAX_LIMIT, "defaultPageSize");
        requireRange(maxPageSize, 1, PageRequest.MAX_LIMIT, "maxPageSize");
        if (defaultPageSize > maxPageSize) {
            throw new IllegalArgumentException("defaultPageSize must not exceed maxPageSize");
        }
        requireRange(mutationBudgetPerTick, 1, 100, "mutationBudgetPerTick");
    }

    public static FoundationConfiguration defaults() {
        return new FoundationConfiguration(5_000, 256, 10, 32, 30, 300, 45, 100, 16, true);
    }

    /**
     * Returns whether every setting captured by startup-created workers is unchanged.
     * Shared-container policy is intentionally excluded because its listeners read the
     * current atomic configuration for every decision.
     */
    public boolean hasSameRestartRequiredSettings(FoundationConfiguration other) {
        return other != null
                && databaseBusyTimeoutMillis == other.databaseBusyTimeoutMillis
                && databaseQueueCapacity == other.databaseQueueCapacity
                && databaseShutdownTimeoutSeconds == other.databaseShutdownTimeoutSeconds
                && deliveryClaimBatchSize == other.deliveryClaimBatchSize
                && deliveryClaimLeaseSeconds == other.deliveryClaimLeaseSeconds
                && duplicateWarningIntervalSeconds == other.duplicateWarningIntervalSeconds
                && defaultPageSize == other.defaultPageSize
                && maxPageSize == other.maxPageSize
                && mutationBudgetPerTick == other.mutationBudgetPerTick;
    }

    private static void requireRange(int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
