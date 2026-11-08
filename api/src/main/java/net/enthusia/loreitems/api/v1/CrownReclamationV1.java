package net.enthusia.loreitems.api.v1;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Reserves an exact custody generation after caller-verified inactivity eligibility.
 * May begin guarded removal for an online holder. A reservation result alone does not prove physical custody.
 */
public interface CrownReclamationV1 {
    enum Result { RESERVED, ALREADY_RESERVED, CONFLICT, UNAVAILABLE }
    CompletionStage<Result> reserve(UUID recoveryOperation, UUID instance, UUID holder, long generation);
}
