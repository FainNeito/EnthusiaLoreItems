package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import org.junit.jupiter.api.Test;

class PaperPhysicalScanQueueTest {
    private static final String CHUNK_LOAD = "chunk-load";
    private static final String PLAYER_JOIN = "player-join";
    private static final String ENTITIES_UNLOAD = "entities-unload";
    private static final String PERIODIC_SOURCE = "periodic-loaded-chunk";

    private final PaperPhysicalScanQueue scans = new PaperPhysicalScanQueue();

    @Test
    void preservesEventOrderAndGivesPeriodicARegularTurn() {
        scans.add(event(CHUNK_LOAD));
        scans.add(periodic(1));
        scans.add(event(PLAYER_JOIN));
        scans.add(event(ENTITIES_UNLOAD));
        scans.add(event("chunk-unload"));
        scans.add(periodic(2));
        scans.add(event("player-death"));
        scans.add(event("chunk-load-again"));

        assertEquals(List.of(
                CHUNK_LOAD, PLAYER_JOIN, ENTITIES_UNLOAD,
                PERIODIC_SOURCE, "chunk-unload", "player-death",
                "chunk-load-again", PERIODIC_SOURCE), drainSources());
        assertNull(scans.poll());
    }

    @Test
    void emptyEventQueueStillProcessesPeriodicWork() {
        scans.add(periodic(1));
        scans.add(periodic(2));
        assertEquals(PERIODIC_SOURCE, scans.poll().source());
        assertEquals(PERIODIC_SOURCE, scans.poll().source());
        assertNull(scans.poll());
    }

    @Test
    void onlyNewestPeriodicWorkCanBeEvicted() {
        scans.add(periodic(1));
        scans.add(event(ENTITIES_UNLOAD));
        scans.add(periodic(2));
        PaperTrackingScanRequest evicted = scans.evictNewestPeriodic();
        assertEquals(2, evicted.chunk().x());
        assertEquals(2, scans.size());
        assertEquals(ENTITIES_UNLOAD, scans.poll().source());
        assertEquals(1, scans.poll().chunk().x());
        assertNull(scans.evictNewestPeriodic());
    }

    @Test
    void clearResetsFairnessAndRemovesAllPendingWork() {
        scans.add(event(PLAYER_JOIN));
        scans.add(periodic(1));
        assertEquals(PLAYER_JOIN, scans.poll().source());
        scans.clear();
        assertEquals(0, scans.size());
        scans.add(event(CHUNK_LOAD));
        scans.add(periodic(2));
        assertEquals(CHUNK_LOAD, scans.poll().source());
        assertEquals(PERIODIC_SOURCE, scans.poll().source());
    }

    private List<String> drainSources() {
        List<String> sources = new ArrayList<>();
        PaperTrackingScanRequest request = scans.poll();
        while (request != null) {
            sources.add(request.source());
            request = scans.poll();
        }
        return sources;
    }

    private static PaperTrackingScanRequest event(String source) {
        return PaperTrackingScanRequest.player(UUID.randomUUID(), true, source);
    }

    private static PaperTrackingScanRequest periodic(int x) {
        return new PaperTrackingScanRequest(
                null, false,
                new PaperTrackingScanRequest.ChunkReference(new UUID(0, 1), x, 0),
                TrackingObservationUseCase.Presence.PRESENT,
                PERIODIC_SOURCE);
    }
}
