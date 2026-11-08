package net.enthusia.loreitems.api.v1;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Native custody contract. KOTH must verify the public solo event and its winner before submitting a grant. */
public interface CrownRecoveryEventsV1 {
    record Status(UUID instance, UUID holder, long generation, UUID recoveryOperation, boolean held, boolean transferPending, boolean deliveryEnabled) {}
    enum Result { RESERVED, ALREADY_RESERVED, CONFLICT, UNAVAILABLE }
    CompletionStage<Optional<Status>> current();
    /** Binds one completed match and recipient to the same held original; never mints an item. */
    CompletionStage<Result> deliver(UUID recoveryOperation, UUID instance, UUID formerHolder,
            long generation, UUID completedMatch, UUID winner);
}
