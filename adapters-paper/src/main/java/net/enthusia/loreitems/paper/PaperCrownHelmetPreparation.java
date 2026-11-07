package net.enthusia.loreitems.paper;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.function.Function;
import net.enthusia.loreitems.application.CrownHelmetEvidenceStore;
import net.enthusia.loreitems.domain.ExclusiveCustodyReservation;
import org.bukkit.entity.Player;

/** Unregistered evidence preparation only. Even an unchanged result must remain on review. */
final class PaperCrownHelmetPreparation {
    enum Result { HELD, EVIDENCE_RECORDED_FOR_REVIEW }
    private final PaperCrownHelmetEvidence capture;
    private final PaperCrownPreparationFence fence;
    private final CrownHelmetEvidenceStore store;
    private final Consumer<Runnable> scheduleOnMain;
    private final Function<UUID, Player> findPlayer;

    PaperCrownHelmetPreparation(PaperItemCodecThreadGuard guard, PaperCrownPreparationFence fence, CrownHelmetEvidenceStore store,
            Consumer<Runnable> scheduleOnMain, Function<UUID, Player> findPlayer) {
        this.capture = new PaperCrownHelmetEvidence(guard);
        this.fence = Objects.requireNonNull(fence, "fence");
        this.store = Objects.requireNonNull(store, "store");
        this.scheduleOnMain = Objects.requireNonNull(scheduleOnMain, "scheduleOnMain");
        this.findPlayer = Objects.requireNonNull(findPlayer, "findPlayer");
    }

    CompletionStage<Result> prepare(Player player, ExclusiveCustodyReservation request) {
        Objects.requireNonNull(request, "request");
        UUID token = fence.begin(request.holderId());
        if (token == null) {
            // A concurrent retry is an uncertainty for the in-flight observation too.
            fence.invalidate(request.holderId());
            return hold(request);
        }
        var snapshot = capture.capture(player, request.holderId());
        if (snapshot.isEmpty()) return hold(request);
        var before = snapshot.orElseThrow();
        CompletionStage<CrownHelmetEvidenceStore.Result> write;
        try {
            write = Objects.requireNonNull(store.record(request, before.empty(), before.bytes()), "evidence write");
        } catch (RuntimeException failure) {
            return hold(request);
        }
        var completion = new CompletableFuture<Result>();
        write.whenComplete((result, failure) -> {
            try {
                scheduleOnMain.accept(() -> settle(request, token, before,
                        failure == null ? result : null, completion));
            } catch (RuntimeException rejected) {
                holdThenComplete(request, completion);
            }
        });
        return completion;
    }

    /** Runs on the main thread. A failed or missing write arrives as a null {@code result}. */
    private void settle(ExclusiveCustodyReservation request, UUID token, PaperCrownHelmetEvidence.Snapshot before,
            CrownHelmetEvidenceStore.Result result, CompletableFuture<Result> completion) {
        try {
            if (result == null || result == CrownHelmetEvidenceStore.Result.HOLD || !fence.valid(request.holderId(), token)) {
                holdThenComplete(request, completion);
                return;
            }
            if (unchanged(request, before)) {
                fence.end(request.holderId(), token);
                completion.complete(Result.EVIDENCE_RECORDED_FOR_REVIEW);
            }
            else holdThenComplete(request, completion);
        } catch (RuntimeException unavailable) {
            holdThenComplete(request, completion);
        }
    }

    private boolean unchanged(ExclusiveCustodyReservation request, PaperCrownHelmetEvidence.Snapshot before) {
        var current = capture.capture(findPlayer.apply(request.holderId()), request.holderId());
        return current.isPresent() && current.orElseThrow().empty() == before.empty()
                && Arrays.equals(current.orElseThrow().bytes(), before.bytes());
    }

    private void holdThenComplete(ExclusiveCustodyReservation request, CompletableFuture<Result> completion) {
        hold(request).whenComplete((ignored, error) -> completion.complete(Result.HELD));
    }

    /** A HELD result is never evidence that a durable provider hold succeeded. */
    private CompletionStage<Result> hold(ExclusiveCustodyReservation request) {
        try {
            return Objects.requireNonNull(store.hold(request), "review hold").handle((ignored, failure) -> Result.HELD);
        } catch (RuntimeException unavailable) {
            return CompletableFuture.completedFuture(Result.HELD);
        }
    }
}
