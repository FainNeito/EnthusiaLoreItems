package net.enthusia.loreitems.application;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.ExclusiveCustodyReservation;

/** Reconciles a freshly loaded player save or reads an existing receipt. */
@FunctionalInterface
public interface CrownEquipmentRecovery {
    enum ReclamationReservation { RESERVED, ALREADY_RESERVED, CONFLICT, UNAVAILABLE }
    default CompletionStage<ReclamationReservation> reserveRecoveryWinner(UUID recoveryOperation, UUID instance,
            UUID formerHolder, long generation, UUID match, UUID winner) {
        return java.util.concurrent.CompletableFuture.completedFuture(ReclamationReservation.UNAVAILABLE);
    }
    default CompletionStage<ReclamationReservation> reserveReclamation(UUID operation, UUID instance, UUID holder, long generation) {
        return java.util.concurrent.CompletableFuture.completedFuture(ReclamationReservation.UNAVAILABLE);
    }
    record Reclamation(UUID operation, Receipt source, boolean held) {}
    record ReleasedReclamation(ExclusiveCustodyReservation source, UUID definition) {}
    default CompletionStage<Optional<ReleasedReclamation>> releasedReclamation(UUID holder, UUID operation) {
        return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
    }
    default CompletionStage<Optional<Reclamation>> findReclamation(UUID holder) {
        return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
    }
    default CompletionStage<Boolean> stageReclamation(Reclamation request, byte[] crown) {
        return java.util.concurrent.CompletableFuture.completedFuture(false);
    }
    default CompletionStage<Boolean> confirmReclamation(Reclamation request) {
        return java.util.concurrent.CompletableFuture.completedFuture(false);
    }
    record Receipt(ExclusiveCustodyReservation reservation, UUID definition, String originalDigest) {}
    default CompletionStage<Optional<Receipt>> confirmLoaded(ExclusiveCustodyReservation reservation, UUID definition, byte[] original) {
        return findEquipped(reservation.holderId());
    }
    default CompletionStage<Boolean> recordPlayerKill(ExclusiveCustodyReservation request, UUID killer) {
        return java.util.concurrent.CompletableFuture.completedFuture(false);
    }
    default CompletionStage<Boolean> escrowForHandoff(Receipt source, byte[] crown) {
        return java.util.concurrent.CompletableFuture.completedFuture(false);
    }
    default CompletionStage<Optional<UUID>> confirmSourceReleased(UUID holder, UUID operation, UUID instance, UUID definition) {
        return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
    }
    record Delivery(ExclusiveCustodyReservation destination, UUID definition, byte[] crown) {}
    default CompletionStage<Optional<Delivery>> takeReleased(UUID killer) {
        return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
    }
    /** An existing delivery grant with no committed destination receipt. Never authorizes reissue. */
    default CompletionStage<Boolean> hasUnconfirmedDelivery(UUID holder) {
        return java.util.concurrent.CompletableFuture.completedFuture(false);
    }
    default CompletionStage<Boolean> commitSuccessor(ExclusiveCustodyReservation destination, UUID definition) {
        return java.util.concurrent.CompletableFuture.completedFuture(false);
    }
    record PendingTransfer(UUID recipient, Optional<UUID> deliveryOperation, boolean sourceReleased) {}
    record Inspection(UUID operation, UUID instance, UUID recordedHolder, long generation,
                      String state, Optional<PendingTransfer> transfer, Optional<UUID> reclamationOperation, String reclamationState) {}
    /** Read-only journal evidence. It does not establish the physical location of the Crown. */
    default CompletionStage<Optional<Inspection>> inspectCustody() {
        return java.util.concurrent.CompletableFuture.completedFuture(Optional.empty());
    }
    CompletionStage<Optional<Receipt>> findEquipped(UUID holder);
}
