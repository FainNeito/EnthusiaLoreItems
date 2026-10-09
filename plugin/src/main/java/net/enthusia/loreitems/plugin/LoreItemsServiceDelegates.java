package net.enthusia.loreitems.plugin;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import net.enthusia.loreitems.api.v1.LoreDeliveryResult;
import net.enthusia.loreitems.api.v1.LoreDeliveryStatus;
import net.enthusia.loreitems.api.v1.LoreItemsServiceV1;

/** Small service adapters used while durable storage availability changes. */
final class LoreItemsServiceDelegates {
    private LoreItemsServiceDelegates() {}

    static LoreItemsServiceV1 delegating(AtomicReference<LoreItemsServiceV1> delegate) {
        return new DelegatingService(delegate);
    }

    static LoreItemsServiceV1 unavailable(String detail) {
        return new UnavailableService(detail);
    }

    private static final class DelegatingService implements LoreItemsServiceV1 {
        private final AtomicReference<LoreItemsServiceV1> delegate;

        private DelegatingService(AtomicReference<LoreItemsServiceV1> delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public CompletionStage<Boolean> isDefinitionActive(String definitionKey) {
            return delegate.get().isDefinitionActive(definitionKey);
        }

        @Override
        public CompletionStage<LoreDeliveryResult> queueDelivery(
                String definitionKey,
                UUID playerId,
                String externalOperationId) {
            return delegate.get().queueDelivery(definitionKey, playerId, externalOperationId);
        }
    }

    private static final class UnavailableService implements LoreItemsServiceV1 {
        private final String detail;

        private UnavailableService(String detail) {
            this.detail = Objects.requireNonNull(detail, "detail");
        }

        @Override
        public CompletionStage<LoreDeliveryResult> queueDelivery(
                String definitionKey,
                UUID playerId,
                String externalOperationId) {
            String safeOperationId = externalOperationId == null ? "" : externalOperationId.strip();
            if (definitionKey == null
                    || definitionKey.isBlank()
                    || playerId == null
                    || safeOperationId.isEmpty()) {
                return CompletableFuture.completedFuture(new LoreDeliveryResult(
                        LoreDeliveryStatus.VALIDATION_FAILURE,
                        safeOperationId,
                        "Definition key, player UUID, and external operation ID are required."));
            }
            return CompletableFuture.completedFuture(new LoreDeliveryResult(
                    LoreDeliveryStatus.SERVICE_UNAVAILABLE,
                    safeOperationId,
                    detail));
        }
    }
}
