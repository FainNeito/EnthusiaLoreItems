package net.enthusia.loreitems.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ExclusiveCustodyReservationTest {
    @Test
    void replayRequiresEveryIdentityAndGenerationToMatch() {
        UUID operation = UUID.randomUUID();
        UUID instance = UUID.randomUUID();
        UUID holder = UUID.randomUUID();
        ExclusiveCustodyReservation reservation = new ExclusiveCustodyReservation(
                operation, instance, holder, 7, ExclusiveCustodyReservation.State.RESERVED);
        assertEquals(ExclusiveCustodyReservation.Admission.SAME_REQUEST, reservation.admit(operation, instance, holder, 7));
        assertEquals(ExclusiveCustodyReservation.Admission.CONFLICT, reservation.admit(UUID.randomUUID(), instance, holder, 7));
        assertEquals(ExclusiveCustodyReservation.Admission.CONFLICT, reservation.admit(operation, UUID.randomUUID(), holder, 7));
        assertEquals(ExclusiveCustodyReservation.Admission.CONFLICT, reservation.admit(operation, instance, UUID.randomUUID(), 7));
        assertEquals(ExclusiveCustodyReservation.Admission.CONFLICT, reservation.admit(operation, instance, holder, 8));
    }

    @Test
    void ambiguityStaysHeldAndKeepsOriginalIdentity() {
        ExclusiveCustodyReservation reservation = new ExclusiveCustodyReservation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, ExclusiveCustodyReservation.State.RESERVED);
        ExclusiveCustodyReservation held = reservation.requireReview();
        assertEquals(ExclusiveCustodyReservation.State.REVIEW_REQUIRED, held.state());
        assertEquals(reservation.instanceId(), held.instanceId());
        assertEquals(held, held.requireReview());
        assertThrows(IllegalArgumentException.class, () -> new ExclusiveCustodyReservation(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), -1, ExclusiveCustodyReservation.State.RESERVED));
    }
}
