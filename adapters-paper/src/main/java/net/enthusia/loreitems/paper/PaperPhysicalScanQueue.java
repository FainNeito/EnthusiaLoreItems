package net.enthusia.loreitems.paper;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Main-thread bounded scan queue. Non-periodic lifecycle observations remain FIFO;
 * routine reconciliation has a guaranteed turn after at most three event scans.
 */
final class PaperPhysicalScanQueue {
    private static final int MAX_CONSECUTIVE_EVENTS = 3;

    private final Deque<PaperTrackingScanRequest> events = new ArrayDeque<>();
    private final Deque<PaperTrackingScanRequest> periodic = new ArrayDeque<>();
    private int consecutiveEvents;

    void add(PaperTrackingScanRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.periodicChunkKey() == null) {
            events.addLast(request);
        } else {
            periodic.addLast(request);
        }
    }

    PaperTrackingScanRequest poll() {
        if (events.isEmpty()) {
            consecutiveEvents = 0;
            return periodic.pollFirst();
        }
        if (consecutiveEvents >= MAX_CONSECUTIVE_EVENTS && !periodic.isEmpty()) {
            consecutiveEvents = 0;
            return periodic.pollFirst();
        }
        consecutiveEvents = Math.min(MAX_CONSECUTIVE_EVENTS, consecutiveEvents + 1);
        return events.pollFirst();
    }

    /** Discard the youngest optional reconciliation, never an event scan. */
    PaperTrackingScanRequest evictNewestPeriodic() {
        return periodic.pollLast();
    }

    int size() {
        return events.size() + periodic.size();
    }

    boolean isEmpty() {
        return events.isEmpty() && periodic.isEmpty();
    }

    void clear() {
        events.clear();
        periodic.clear();
        consecutiveEvents = 0;
    }
}
