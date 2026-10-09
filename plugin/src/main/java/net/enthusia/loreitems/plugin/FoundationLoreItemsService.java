package net.enthusia.loreitems.plugin;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.api.v1.LoreDeliveryResult;
import net.enthusia.loreitems.api.v1.LoreDeliveryStatus;
import net.enthusia.loreitems.api.v1.LoreItemsServiceV1;
import net.enthusia.loreitems.application.ExternalDeliveryCommand;
import net.enthusia.loreitems.application.ExternalDeliveryOutcome;
import net.enthusia.loreitems.application.ExternalDeliveryUseCase;
import net.enthusia.loreitems.application.DefinitionRepository;
import net.enthusia.loreitems.domain.DefinitionKey;

final class FoundationLoreItemsService implements LoreItemsServiceV1 {
    private final ExternalDeliveryUseCase useCase;
    private final Optional<DefinitionRepository> definitions;

    FoundationLoreItemsService(ExternalDeliveryUseCase useCase) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.definitions = Optional.empty();
    }

    FoundationLoreItemsService(ExternalDeliveryUseCase useCase, DefinitionRepository definitions) {
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.definitions = Optional.of(Objects.requireNonNull(definitions, "definitions"));
    }

    @Override
    public CompletionStage<Boolean> isDefinitionActive(String definitionKey) {
        if (definitions.isEmpty()) {
            return LoreItemsServiceV1.super.isDefinitionActive(definitionKey);
        }
        final DefinitionKey key;
        try {
            key = new DefinitionKey(definitionKey);
        } catch (RuntimeException invalid) {
            return CompletableFuture.completedFuture(false);
        }
        try {
            return definitions.orElseThrow().findActiveByKey(key)
                    .thenApply(found -> found.filter(definition -> definition.active()).isPresent());
        } catch (RuntimeException unavailable) {
            return CompletableFuture.failedFuture(unavailable);
        }
    }

    @Override
    public CompletionStage<LoreDeliveryResult> queueDelivery(
            String definitionKey, UUID playerId, String externalOperationId) {
        String safeOperationId = externalOperationId == null ? "" : externalOperationId.strip();
        final ExternalDeliveryCommand command;
        try {
            command = new ExternalDeliveryCommand(
                    new DefinitionKey(definitionKey), playerId, safeOperationId);
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(new LoreDeliveryResult(
                    LoreDeliveryStatus.VALIDATION_FAILURE,
                    safeOperationId,
                    exception.getMessage() == null ? "Invalid delivery request." : exception.getMessage()));
        }
        return useCase.queue(command)
                .handle((outcome, throwable) -> {
                    if (throwable != null) {
                        return new LoreDeliveryResult(
                                LoreDeliveryStatus.SERVICE_UNAVAILABLE,
                                safeOperationId,
                                "Durable storage rejected the request.");
                    }
                    return new LoreDeliveryResult(
                            status(outcome), safeOperationId, detail(outcome));
                });
    }

    private static LoreDeliveryStatus status(ExternalDeliveryOutcome outcome) {
        return LoreDeliveryStatus.valueOf(outcome.name());
    }

    private static String detail(ExternalDeliveryOutcome outcome) {
        return switch (outcome) {
            case ACCEPTED_QUEUED ->
                    "Durable intent accepted; inventory delivery will run when the player is online with space.";
            case ALREADY_ACCEPTED -> "This external operation was already accepted.";
            case UNKNOWN_DEFINITION -> "No active lore definition has that key.";
            case SERVICE_UNAVAILABLE -> "Durable storage is unavailable.";
            case VALIDATION_FAILURE -> "The delivery request is invalid.";
        };
    }
}
