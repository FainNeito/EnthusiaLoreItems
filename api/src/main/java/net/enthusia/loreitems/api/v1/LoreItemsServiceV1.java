package net.enthusia.loreitems.api.v1;

import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;

public interface LoreItemsServiceV1 {
    int API_VERSION = 1;

    CompletionStage<LoreDeliveryResult> queueDelivery(
            String definitionKey,
            UUID playerId,
            String externalOperationId);

    /** Read-only active-definition existence snapshot; never queues or reserves an item.
     * Unsupported providers complete exceptionally, preserving V1 delivery implementations.
     * Consumers must fail closed on exceptions and must not block the server thread.
     */
    default CompletionStage<Boolean> isDefinitionActive(String definitionKey) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "This provider does not support read-only definition queries."));
    }
}
