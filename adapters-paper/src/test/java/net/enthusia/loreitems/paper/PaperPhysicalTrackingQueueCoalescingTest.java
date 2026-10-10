package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.CompletableFuture;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/** Prevents routine queue optimizations from silently removing lifecycle evidence. */
class PaperPhysicalTrackingQueueCoalescingTest {
    private static final String PERIODIC_SOURCE = "periodic-loaded-chunk";
    private ServerMock server;
    private PaperPhysicalTrackingListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        PluginMock plugin = MockBukkit.createMockPlugin();
        TrackingObservationUseCase recorder = request ->
                CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                        TrackingObservationUseCase.Status.RECORDED, "ok"));
        listener = new PaperPhysicalTrackingListener(
                plugin, () -> recorder, () -> 4, MetricsPort.noOp());
    }

    @AfterEach
    void tearDown() {
        listener.close();
        MockBukkit.unmock();
    }

    @Test
    void repeatedPeriodicRequestsShareOneQueueSlotButChunkLoadDoesNot() {
        World world = server.addSimpleWorld("world");
        Chunk chunk = world.getChunkAt(0, 0);
        PaperTrackingScanRequest periodic = PaperTrackingScanRequest.chunk(
                chunk, TrackingObservationUseCase.Presence.PRESENT,
                PERIODIC_SOURCE);

        for (int n = 0; n < 50; n++) {
            listener.enqueue(periodic);
        }

        assertEquals(1, listener.queuedScanCount());
        assertEquals(1, listener.queuedPeriodicChunkCount());
        listener.enqueue(PaperTrackingScanRequest.chunk(
                chunk, TrackingObservationUseCase.Presence.PRESENT,
                PERIODIC_SOURCE));
        assertEquals(1, listener.queuedScanCount());

        // A real chunk-load observation must not be discarded merely because a
        // background periodic reconciliation for the same chunk is pending.
        listener.enqueue(PaperTrackingScanRequest.chunk(
                chunk, TrackingObservationUseCase.Presence.PRESENT, "chunk-load"));
        listener.enqueue(PaperTrackingScanRequest.chunk(
                chunk, TrackingObservationUseCase.Presence.PRESENT, "chunk-load"));
        assertEquals(3, listener.queuedScanCount());
        assertEquals(1, listener.queuedPeriodicChunkCount());

        // Even if a caller accidentally uses the periodic label for a
        // LAST_CONFIRMED observation, it must never be coalesced into PRESENT.
        listener.enqueue(PaperTrackingScanRequest.chunk(
                chunk, TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                PERIODIC_SOURCE));
        assertEquals(4, listener.queuedScanCount());
        assertEquals(1, listener.queuedPeriodicChunkCount());
    }

    @Test
    void sameCoordinatesInDifferentWorldsNeverCoalesce() {
        World first = server.addSimpleWorld("one");
        World second = server.addSimpleWorld("two");
        listener.enqueue(PaperTrackingScanRequest.chunk(
                first.getChunkAt(0, 0), TrackingObservationUseCase.Presence.PRESENT,
                PERIODIC_SOURCE));
        listener.enqueue(PaperTrackingScanRequest.chunk(
                second.getChunkAt(0, 0), TrackingObservationUseCase.Presence.PRESENT,
                PERIODIC_SOURCE));

        assertEquals(2, listener.queuedScanCount());
        assertEquals(2, listener.queuedPeriodicChunkCount());
    }

    @Test
    void closeReleasesAllPendingPeriodicKeys() {
        World world = server.addSimpleWorld("world");
        listener.enqueue(PaperTrackingScanRequest.chunk(
                world.getChunkAt(0, 0), TrackingObservationUseCase.Presence.PRESENT,
                PERIODIC_SOURCE));
        assertEquals(1, listener.queuedPeriodicChunkCount());

        listener.close();

        assertEquals(0, listener.queuedScanCount());
        assertEquals(0, listener.queuedPeriodicChunkCount());
    }
}
