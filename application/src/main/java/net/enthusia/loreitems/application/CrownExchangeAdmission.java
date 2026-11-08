package net.enthusia.loreitems.application;

import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.ExclusiveCustodyReservation;

/** Only a newly committed reservation and original-helmet record may admit the inventory step. */
public interface CrownExchangeAdmission {
    default CompletionStage<Boolean> reserveFirstIssue(ExclusiveCustodyReservation request, java.util.UUID definition,
            long revision, EncodedItemTemplate template, byte[] original) {
        return java.util.concurrent.CompletableFuture.completedFuture(false);
    }
    CompletionStage<Boolean> hold(ExclusiveCustodyReservation request);
    CompletionStage<Boolean> admit(ExclusiveCustodyReservation request, java.util.UUID definition, boolean empty, byte[] original);
}
