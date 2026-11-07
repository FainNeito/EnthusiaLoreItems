package net.enthusia.loreitems.api.v1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class LoreItemsServiceV1AbiTest {
    @Test
    void v1ServiceBinaryShapeIsPinned() throws Exception {
        assertEquals(1, LoreItemsServiceV1.API_VERSION);
        assertTrue(LoreItemsServiceV1.class.isInterface());

        Method queueDelivery = LoreItemsServiceV1.class.getMethod(
                "queueDelivery", String.class, UUID.class, String.class);
        assertEquals(CompletionStage.class, queueDelivery.getReturnType());
        assertTrue(Modifier.isPublic(queueDelivery.getModifiers()));
        assertTrue(Modifier.isAbstract(queueDelivery.getModifiers()));
        Method lookup = LoreItemsServiceV1.class.getMethod("isDefinitionActive", String.class);
        assertTrue(lookup.isDefault());
        assertEquals(CompletionStage.class, lookup.getReturnType());
        assertEquals(2, LoreItemsServiceV1.class.getMethods().length);
    }

    @Test
    void v1ResultRecordAndStatusSetArePinned() {
        assertTrue(LoreDeliveryResult.class.isRecord());
        assertEquals(
                List.of("status", "externalOperationId", "detail"),
                Arrays.stream(LoreDeliveryResult.class.getRecordComponents())
                        .map(component -> component.getName())
                        .toList());
        assertEquals(
                List.of(
                        "ACCEPTED_QUEUED",
                        "ALREADY_ACCEPTED",
                        "UNKNOWN_DEFINITION",
                        "SERVICE_UNAVAILABLE",
                        "VALIDATION_FAILURE"),
                Arrays.stream(LoreDeliveryStatus.values()).map(Enum::name).toList());
    }

    @Test
    void sourceConsumerCompilesAgainstOnlyTheVersionedApiSurface() {
        LoreItemsServiceV1 consumerDependency = (definitionKey, playerId, externalOperationId) ->
                java.util.concurrent.CompletableFuture.completedFuture(new LoreDeliveryResult(
                        LoreDeliveryStatus.ACCEPTED_QUEUED,
                        externalOperationId,
                        "accepted"));

        LoreDeliveryResult result = consumerDependency.queueDelivery(
                        "starter_blade",
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        "tags:claim-45")
                .toCompletableFuture()
                .join();

        assertEquals(LoreDeliveryStatus.ACCEPTED_QUEUED, result.status());
    }
}
