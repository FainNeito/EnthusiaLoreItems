package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.logging.Logger;
import net.enthusia.loreitems.application.MetricsPort;
import org.bukkit.plugin.Plugin;

/** Fixed-category main-thread scan accounting; never influences queue decisions. */
final class PaperPhysicalScanTelemetry {
    private final MetricsPort metrics;
    private boolean saturated;

    PaperPhysicalScanTelemetry(MetricsPort metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    void updateDepth(int queued, int periodic) {
        metrics.setGauge("tracking.scan_backlog", queued);
        metrics.setGauge("tracking.scan_backlog_peak", queued);
        metrics.setGauge("tracking.scan_periodic_backlog", periodic);
        metrics.setGauge("tracking.scan_event_backlog", queued - periodic);
    }

    void saturated(Logger logger) {
        if (!saturated) {
            saturated = true;
            logger.warning(
                    "Lore-item scan backlog is full; previous durable evidence was preserved.");
        }
    }

    void drained(boolean empty, Logger logger) {
        if (empty && saturated) {
            saturated = false;
            logger.fine("Lore-item scan backlog has drained.");
        }
    }

    void reset() {
        saturated = false;
    }

    void reject(PaperTrackingScanRequest request) {
        metrics.increment("tracking.rejected");
        metrics.increment(request.periodicChunkKey() == null
                ? "tracking.scan_event_rejected" : "tracking.scan_periodic_rejected");
    }

    void execute(
            PaperTrackingScanRequest request,
            Plugin plugin,
            PaperPhysicalTrackingListener listener) {
        boolean periodic = request.periodicChunkKey() != null;
        long started = System.nanoTime();
        try {
            request.run(plugin, listener);
        } finally {
            metrics.recordDurationNanos(
                    periodic ? "tracking.scan_periodic_nanos" : "tracking.scan_event_nanos",
                    System.nanoTime() - started);
            metrics.increment(periodic
                    ? "tracking.scan_periodic_completed" : "tracking.scan_event_completed");
        }
    }
}
