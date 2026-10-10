package net.enthusia.loreitems.application;

import java.util.concurrent.atomic.AtomicLong;

/** Small process-local metrics snapshot for operator visibility and backpressure diagnostics. */
public final class TrackingMetrics implements MetricsPort {
    private static final String QUEUED = "tracking.queued";
    private static final String IN_FLIGHT = "tracking.in_flight";
    private static final String ADDITIONAL_QUEUED = "tracking.additional.queued";
    private static final String ADDITIONAL_IN_FLIGHT = "tracking.additional.in_flight";
    private static final String SCAN_BACKLOG = "tracking.scan_backlog";
    private static final String ACCEPTED = "tracking.accepted";
    private static final String REJECTED = "tracking.rejected";
    private static final String COMPLETED = "tracking.completed";
    private static final String FAILED = "tracking.failed";
    private static final String CONFLICTS = "tracking.conflicts";
    private static final String SCAN_TRUNCATED = "tracking.scan_truncated";
    private static final String DEFERRED_PLAYER_SCAN_COALESCED = "tracking.deferred_player_scan_coalesced";
    private static final String PERIODIC_CHUNK_SCAN_COALESCED = "tracking.periodic_chunk_scan_coalesced";
    private static final String DEFERRED_PLAYER_SCAN_SCHEDULE_REJECTED =
            "tracking.deferred_player_scan_schedule_rejected";
    private static final String PERSISTENCE_NANOS = "tracking.persistence_nanos";

    private final AtomicLong queued = new AtomicLong();
    private final AtomicLong inFlight = new AtomicLong();
    private final AtomicLong additionalQueued = new AtomicLong();
    private final AtomicLong additionalInFlight = new AtomicLong();
    private final AtomicLong scanBacklog = new AtomicLong();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong conflicts = new AtomicLong();
    private final AtomicLong scanTruncated = new AtomicLong();
    private final AtomicLong deferredPlayerScanCoalesced = new AtomicLong();
    private final AtomicLong periodicChunkScanCoalesced = new AtomicLong();
    private final AtomicLong deferredPlayerScanScheduleRejected = new AtomicLong();
    private final AtomicLong durationNanos = new AtomicLong();

    @Override
    public void setGauge(String name, long value) {
        switch (name) {
            case QUEUED -> queued.set(value);
            case IN_FLIGHT -> inFlight.set(value);
            case ADDITIONAL_QUEUED -> additionalQueued.set(value);
            case ADDITIONAL_IN_FLIGHT -> additionalInFlight.set(value);
            case SCAN_BACKLOG -> scanBacklog.set(value);
            default -> {
                // Metrics outside this phase are intentionally ignored by this focused port.
            }
        }
    }

    @Override
    public void increment(String name) {
        switch (name) {
            case ACCEPTED -> accepted.incrementAndGet();
            case REJECTED -> rejected.incrementAndGet();
            case COMPLETED -> completed.incrementAndGet();
            case FAILED -> failed.incrementAndGet();
            case CONFLICTS -> conflicts.incrementAndGet();
            case SCAN_TRUNCATED -> scanTruncated.incrementAndGet();
            default -> incrementScanDiagnostics(name);
        }
    }

    private void incrementScanDiagnostics(String name) {
        switch (name) {
            case DEFERRED_PLAYER_SCAN_COALESCED -> deferredPlayerScanCoalesced.incrementAndGet();
            case PERIODIC_CHUNK_SCAN_COALESCED -> periodicChunkScanCoalesced.incrementAndGet();
            case DEFERRED_PLAYER_SCAN_SCHEDULE_REJECTED -> deferredPlayerScanScheduleRejected.incrementAndGet();
            default -> {
                // Ignore unrelated metric names, preserving the existing contract.
            }
        }
    }

    @Override
    public void recordDurationNanos(String name, long value) {
        if (PERSISTENCE_NANOS.equals(name) && value >= 0L) {
            durationNanos.addAndGet(value);
        }
    }

    public MetricsPort additionalQueueView() {
        return new AdditionalQueueMetrics(this);
    }

    public Snapshot snapshot() {
        return new Snapshot(
                queued.get() + additionalQueued.get(),
                inFlight.get() + additionalInFlight.get(),
                scanBacklog.get(),
                accepted.get(),
                rejected.get(),
                completed.get(),
                failed.get(),
                conflicts.get(),
                scanTruncated.get(),
                durationNanos.get(),
                deferredPlayerScanCoalesced.get(),
                periodicChunkScanCoalesced.get(),
                deferredPlayerScanScheduleRejected.get());
    }

    public record Snapshot(
            long queued,
            long inFlight,
            long scanBacklog,
            long accepted,
            long rejected,
            long completed,
            long failed,
            long conflicts,
            long scanTruncated,
            long persistenceNanos,
            long deferredPlayerScanCoalesced,
            long periodicChunkScanCoalesced,
            long deferredPlayerScanScheduleRejected) {}

    private record AdditionalQueueMetrics(TrackingMetrics metrics) implements MetricsPort {
        @Override
        public void setGauge(String name, long value) {
            switch (name) {
                case QUEUED -> metrics.setGauge(ADDITIONAL_QUEUED, value);
                case IN_FLIGHT -> metrics.setGauge(ADDITIONAL_IN_FLIGHT, value);
                default -> metrics.setGauge(name, value);
            }
        }

        @Override
        public void increment(String name) {
            metrics.increment(name);
        }

        @Override
        public void recordDurationNanos(String name, long persistenceNanos) {
            metrics.recordDurationNanos(name, persistenceNanos);
        }
    }
}
