package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.loreitems.application.TrackingMetrics;
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
    private TrackingMetrics metrics;
    private AtomicInteger scanBudget;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        PluginMock plugin = MockBukkit.createMockPlugin();
        TrackingObservationUseCase recorder = request ->
                CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                        TrackingObservationUseCase.Status.RECORDED, "ok"));
        metrics = new TrackingMetrics();
        scanBudget = new AtomicInteger(4);
        listener = new PaperPhysicalTrackingListener(
                plugin, () -> recorder, scanBudget::get, metrics);
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
    void recordsActualPeakAndDistinguishesEventFromRoutineRejections() {
        World world = server.addSimpleWorld("world");
        for (int i = 0; i < 127; i++) {
            listener.enqueue(PaperTrackingScanRequest.player(
                    new UUID(0L, i + 1L), true, "player-join"));
        }
        listener.enqueue(PaperTrackingScanRequest.chunk(
                world.getChunkAt(0, 0),
                TrackingObservationUseCase.Presence.PRESENT, PERIODIC_SOURCE));
        assertEquals(128, listener.queuedScanCount());
        assertEquals(128L, metrics.snapshot().scanBacklogPeak());
        assertEquals(127L, metrics.snapshot().scanEventBacklog());
        assertEquals(1L, metrics.snapshot().scanPeriodicBacklog());

        listener.enqueue(PaperTrackingScanRequest.player(
                UUID.randomUUID(), true, "player-join"));
        listener.enqueue(PaperTrackingScanRequest.chunk(
                world.getChunkAt(1, 0),
                TrackingObservationUseCase.Presence.PRESENT, PERIODIC_SOURCE));
        assertEquals(0L, metrics.snapshot().scanEventRejected(),
                "New lifecycle work displaces routine reconciliation, never event scans");
        assertEquals(2L, metrics.snapshot().scanPeriodicRejected());
        assertEquals(0, listener.queuedPeriodicChunkCount());
        assertEquals(128, listener.queuedScanCount());
        assertEquals(128L, metrics.snapshot().scanBacklogPeak());
    }

    @Test
    void allEventBacklogStillHasBoundedSafeRejection() {
        for (int i = 0; i < 128; i++) {
            listener.enqueue(PaperTrackingScanRequest.player(
                    new UUID(0L, i + 1L), true, "player-join"));
        }
        listener.enqueue(PaperTrackingScanRequest.player(
                UUID.randomUUID(), true, "player-join"));

        assertEquals(128, listener.queuedScanCount());
        assertEquals(1L, metrics.snapshot().scanEventRejected());
        assertEquals(0L, metrics.snapshot().scanPeriodicRejected());
    }

    @Test
    void runtimeBudgetReductionCannotExpandAnAlreadyOversizedBacklog() {
        World world = server.addSimpleWorld("world");
        for (int i = 0; i < 127; i++) {
            listener.enqueue(PaperTrackingScanRequest.player(
                    new UUID(0L, i + 1L), true, "player-join"));
        }
        listener.enqueue(PaperTrackingScanRequest.chunk(
                world.getChunkAt(0, 0),
                TrackingObservationUseCase.Presence.PRESENT, PERIODIC_SOURCE));

        scanBudget.set(2); // Capacity drops from 128 to 64 without deleting queued evidence.
        listener.enqueue(PaperTrackingScanRequest.player(
                UUID.randomUUID(), true, "chunk-load"));

        assertEquals(128, listener.queuedScanCount());
        assertEquals(1, listener.queuedPeriodicChunkCount());
        assertEquals(1L, metrics.snapshot().scanEventRejected());
        assertEquals(0L, metrics.snapshot().scanPeriodicRejected());
    }

    @Test
    void routineReconciliationMakesProgressDuringEventFlood() {
        World world = server.addSimpleWorld("world");
        listener.enqueue(PaperTrackingScanRequest.chunk(
                world.getChunkAt(0, 0),
                TrackingObservationUseCase.Presence.PRESENT, PERIODIC_SOURCE));
        for (int i = 0; i < 12; i++) {
            listener.enqueue(PaperTrackingScanRequest.player(
                    new UUID(0L, i + 1L), true, "player-join"));
        }

        listener.drain();
        assertEquals(3L, metrics.snapshot().scanEventCompleted());
        assertEquals(1L, metrics.snapshot().scanPeriodicCompleted());
        assertEquals(0, listener.queuedPeriodicChunkCount());
        assertEquals(9, listener.queuedScanCount());
    }

    @Test
    void oneTickDrainCapturesEventAndRoutineCostWithoutChangingAdmissions() {
        World world = server.addSimpleWorld("world");
        listener.enqueue(PaperTrackingScanRequest.player(
                UUID.randomUUID(), true, "player-join"));
        listener.enqueue(PaperTrackingScanRequest.chunk(
                world.getChunkAt(0, 0),
                TrackingObservationUseCase.Presence.PRESENT, PERIODIC_SOURCE));

        listener.drain();

        assertEquals(0, listener.queuedScanCount());
        assertEquals(0L, metrics.snapshot().scanEventBacklog());
        assertEquals(0L, metrics.snapshot().scanPeriodicBacklog());
        assertEquals(2L, metrics.snapshot().scanBacklogPeak());
        assertEquals(1L, metrics.snapshot().scanEventCompleted());
        assertEquals(1L, metrics.snapshot().scanPeriodicCompleted());
        assertEquals(0L, metrics.snapshot().scanEventRejected());
        assertEquals(0L, metrics.snapshot().scanPeriodicRejected());
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
