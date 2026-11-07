package net.enthusia.loreitems.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Inert identity fence for a proposed custody operation. This record is not physical proof,
 * a storage lock, an item creation permit, or a reason to discard an existing helmet.
 */
public record ExclusiveCustodyReservation(
        UUID operationId, UUID instanceId, UUID holderId, long generation, State state) {
    public enum State { RESERVED, REVIEW_REQUIRED }
    public enum Admission { SAME_REQUEST, CONFLICT }

    public ExclusiveCustodyReservation {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(holderId, "holderId");
        Objects.requireNonNull(state, "state");
        if (generation < 0) {
            throw new IllegalArgumentException("generation must not be negative");
        }
    }

    /** All identity and fencing fields must match for a request to be a replay. */
    public Admission admit(UUID operation, UUID instance, UUID holder, long expectedGeneration) {
        return operationId.equals(operation) && instanceId.equals(instance) && holderId.equals(holder)
                && generation == expectedGeneration ? Admission.SAME_REQUEST : Admission.CONFLICT;
    }

    /** An uncertain outcome remains held; there is deliberately no release or mint transition. */
    public ExclusiveCustodyReservation requireReview() {
        return new ExclusiveCustodyReservation(operationId, instanceId, holderId, generation, State.REVIEW_REQUIRED);
    }
}
