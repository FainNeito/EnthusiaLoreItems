package net.enthusia.loreitems.paper;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import net.enthusia.loreitems.application.MetricsPort;

/** Confines next-tick same-player/source deduplication to the Paper main thread. */
final class PaperPlayerScanCoalescer {
    private final Set<PaperDeferredPlayerScanKey> pending = new HashSet<>();
    private final PaperDeferredMainThreadActions deferred;
    private final MetricsPort metrics;
    private final BiConsumer<UUID, String> scan;

    PaperPlayerScanCoalescer(
            PaperDeferredMainThreadActions deferred,
            MetricsPort metrics,
            BiConsumer<UUID, String> scan) {
        this.deferred = Objects.requireNonNull(deferred, "deferred");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.scan = Objects.requireNonNull(scan, "scan");
    }

    void schedule(UUID playerId, String source) {
        PaperDeferredPlayerScanKey key = new PaperDeferredPlayerScanKey(playerId, source);
        if (!pending.add(key)) {
            metrics.increment("tracking.deferred_player_scan_coalesced");
            return;
        }
        if (!deferred.schedule(() -> {
            pending.remove(key);
            scan.accept(key.playerId(), key.source());
        })) {
            pending.remove(key);
            metrics.increment("tracking.deferred_player_scan_schedule_rejected");
        }
    }

    int size() {
        return pending.size();
    }

    void clear() {
        pending.clear();
    }
}
