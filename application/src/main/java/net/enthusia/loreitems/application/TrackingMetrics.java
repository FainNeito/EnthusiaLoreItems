package net.enthusia.loreitems.application;

import java.util.concurrent.atomic.AtomicLong;

/** Small process-local metrics snapshot for operator visibility and backpressure diagnostics. */
public final class TrackingMetrics implements MetricsPort {
    private static final String QUEUED = "tracking.queued";
    private static final String IN_FLIGHT = "tracking.in_flight";
    private static final String ADDITIONAL_QUEUED = "tracking.additional.queued";
    private static final String ADDITIONAL_IN_FLIGHT = "tracking.additional.in_flight";
    private static final String SCAN_BACKLOG = "tracking.scan_backlog";
    private static final String SCAN_BACKLOG_PEAK = "tracking.scan_backlog_peak";
    private static final String SCAN_EVENT_BACKLOG = "tracking.scan_event_backlog";
    private static final String SCAN_PERIODIC_BACKLOG = "tracking.scan_periodic_backlog";
    private static final String SCAN_EVENT_REJECTED = "tracking.scan_event_rejected";
    private static final String SCAN_PERIODIC_REJECTED = "tracking.scan_periodic_rejected";
    private static final String SCAN_EVENT_COMPLETED = "tracking.scan_event_completed";
    private static final String SCAN_PERIODIC_COMPLETED = "tracking.scan_periodic_completed";
    private static final String SCAN_EVENT_NANOS = "tracking.scan_event_nanos";
    private static final String SCAN_PERIODIC_NANOS = "tracking.scan_periodic_nanos";
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
    private final AtomicLong scanBacklogPeak = new AtomicLong();
    private final AtomicLong scanEventBacklog = new AtomicLong();
    private final AtomicLong scanPeriodicBacklog = new AtomicLong();
    private final AtomicLong scanEventRejected = new AtomicLong();
    private final AtomicLong scanPeriodicRejected = new AtomicLong();
    private final AtomicLong scanEventCompleted = new AtomicLong();
    private final AtomicLong scanPeriodicCompleted = new AtomicLong();
    private final AtomicLong scanEventNanos = new AtomicLong();
    private final AtomicLong scanPeriodicNanos = new AtomicLong();
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
            default -> setScanQueueGauge(name, value);
        }
    }

    private void setScanQueueGauge(String name, long value) {
        switch (name) {
            case SCAN_BACKLOG_PEAK -> scanBacklogPeak.accumulateAndGet(
                    Math.max(0L, value), Math::max);
            case SCAN_EVENT_BACKLOG -> scanEventBacklog.set(value);
            case SCAN_PERIODIC_BACKLOG -> scanPeriodicBacklog.set(value);
            default -> {
                // Unknown metrics are intentionally ignored.
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
            default -> incrementScanQueueCounter(name);
        }
    }

    private void incrementScanQueueCounter(String name) {
        switch (name) {
            case SCAN_EVENT_REJECTED -> scanEventRejected.incrementAndGet();
            case SCAN_PERIODIC_REJECTED -> scanPeriodicRejected.incrementAndGet();
            case SCAN_EVENT_COMPLETED -> scanEventCompleted.incrementAndGet();
            case SCAN_PERIODIC_COMPLETED -> scanPeriodicCompleted.incrementAndGet();
            default -> {
                // Ignore unrelated metric names, preserving the existing contract.
            }
        }
    }

    @Override
    public void recordDurationNanos(String name, long value) {
        if (value < 0L) {
            return;
        }
        switch (name) {
            case PERSISTENCE_NANOS -> durationNanos.addAndGet(value);
            case SCAN_EVENT_NANOS -> scanEventNanos.addAndGet(value);
            case SCAN_PERIODIC_NANOS -> scanPeriodicNanos.addAndGet(value);
            default -> {
                // Unknown timing samples are intentionally ignored.
            }
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
                deferredPlayerScanScheduleRejected.get(),
                scanBacklogPeak.get(), scanEventBacklog.get(), scanPeriodicBacklog.get(),
                scanEventRejected.get(), scanPeriodicRejected.get(),
                scanEventCompleted.get(), scanPeriodicCompleted.get(),
                scanEventNanos.get(), scanPeriodicNanos.get());
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
            long deferredPlayerScanScheduleRejected,
            long scanBacklogPeak,
            long scanEventBacklog,
            long scanPeriodicBacklog,
            long scanEventRejected,
            long scanPeriodicRejected,
            long scanEventCompleted,
            long scanPeriodicCompleted,
            long scanEventNanos,
            long scanPeriodicNanos) {}

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
