package net.enthusia.loreitems.api.v1;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Explicit first-award capability. True means the reserved Crown was staged and player save requested. */
public interface CrownFirstAwardV1 {
    CompletionStage<Boolean> stageVerifiedWinner(UUID winningMatch, UUID winner);
}
