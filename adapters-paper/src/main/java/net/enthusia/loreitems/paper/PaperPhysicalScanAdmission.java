package net.enthusia.loreitems.paper;

import java.util.Objects;

/** Backpressure rule: displace only optional routine work, never lifecycle evidence. */
final class PaperPhysicalScanAdmission {
    private final PaperPhysicalScanQueue scans;
    private final PaperPeriodicChunkScanKeys periodicKeys;
    private final PaperPhysicalScanTelemetry telemetry;

    PaperPhysicalScanAdmission(
            PaperPhysicalScanQueue scans,
            PaperPeriodicChunkScanKeys periodicKeys,
            PaperPhysicalScanTelemetry telemetry) {
        this.scans = Objects.requireNonNull(scans, "scans");
        this.periodicKeys = Objects.requireNonNull(periodicKeys, "periodicKeys");
        this.telemetry = Objects.requireNonNull(telemetry, "telemetry");
    }

    boolean makeRoomFor(PaperTrackingScanRequest incoming) {
        if (incoming.periodicChunkKey() != null) {
            return false;
        }
        PaperTrackingScanRequest evicted = scans.evictNewestPeriodic();
        if (evicted == null) {
            return false;
        }
        periodicKeys.release(evicted);
        telemetry.reject(evicted);
        return true;
    }
}
