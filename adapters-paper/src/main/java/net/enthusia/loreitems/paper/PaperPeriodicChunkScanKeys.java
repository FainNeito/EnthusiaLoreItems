package net.enthusia.loreitems.paper;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import net.enthusia.loreitems.application.MetricsPort;

/** Main-thread-confined keys for queued, routine chunk reconciliation only. */
final class PaperPeriodicChunkScanKeys {
    private final Set<PaperTrackingScanRequest.ChunkReference> pending = new HashSet<>();

    boolean tryRegister(PaperTrackingScanRequest request, MetricsPort metrics) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(metrics, "metrics");
        PaperTrackingScanRequest.ChunkReference key = request.periodicChunkKey();
        if (key != null && !pending.add(key)) {
            metrics.increment("tracking.periodic_chunk_scan_coalesced");
            return false;
        }
        return true;
    }

    void release(PaperTrackingScanRequest request) {
        PaperTrackingScanRequest.ChunkReference key = request.periodicChunkKey();
        if (key != null) {
            pending.remove(key);
        }
    }

    int size() {
        return pending.size();
    }

    void clear() {
        pending.clear();
    }
}
