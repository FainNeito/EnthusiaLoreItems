package net.enthusia.loreitems.application;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import net.enthusia.loreitems.domain.ExclusiveCustodyReservation;

/** Evidence-only port. A recorded result does not permit any item mutation. */
public interface CrownHelmetEvidenceStore {
    enum Result { RECORDED, REPLAY, HOLD }

    CompletionStage<Result> record(ExclusiveCustodyReservation reservation, boolean empty, byte[] evidence);

    /** Unsupported stores fail closed; false is not proof that a review hold exists. */
    default CompletionStage<Boolean> hold(ExclusiveCustodyReservation reservation) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Review holds unavailable"));
    }
}
