package net.enthusia.loreitems.plugin;

import java.time.Clock;
import java.time.Duration;
import net.enthusia.loreitems.api.v1.LoreItemsServiceV1;
import net.enthusia.loreitems.application.AdoptHeldItemUseCase;
import net.enthusia.loreitems.application.CreateDefinitionUseCase;
import net.enthusia.loreitems.application.DirectDeliveryExecutionUseCase;
import net.enthusia.loreitems.application.DisplayItemObservationUseCase;
import net.enthusia.loreitems.application.FoundationConfiguration;
import net.enthusia.loreitems.application.ItemAnomalyObservationUseCase;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.PersistingAdoptHeldItemUseCase;
import net.enthusia.loreitems.application.PersistingCreateDefinitionUseCase;
import net.enthusia.loreitems.application.PersistingDirectDeliveryExecutionUseCase;
import net.enthusia.loreitems.application.PersistingDisplayItemObservationUseCase;
import net.enthusia.loreitems.application.PersistingExternalDeliveryUseCase;
import net.enthusia.loreitems.application.PersistingItemAnomalyObservationUseCase;
import net.enthusia.loreitems.application.PersistingLoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.PersistingTemplateManagementUseCase;
import net.enthusia.loreitems.application.PersistingTemplateRevisionRolloutUseCase;
import net.enthusia.loreitems.application.PersistingTrackingObservationUseCase;
import net.enthusia.loreitems.application.PersistingVoidLossUseCase;
import net.enthusia.loreitems.application.TemplateManagementUseCase;
import net.enthusia.loreitems.application.TemplateRevisionRolloutUseCase;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.application.VoidLossUseCase;
import net.enthusia.loreitems.sqlite.SQLiteAnomalyRepository;
import net.enthusia.loreitems.sqlite.SQLiteAuditRepository;
import net.enthusia.loreitems.sqlite.SQLiteCurrentStateRepository;
import net.enthusia.loreitems.sqlite.SQLiteDirectDeliveryRepository;
import net.enthusia.loreitems.sqlite.SQLiteDefinitionRepository;
import net.enthusia.loreitems.sqlite.SQLiteDisplayItemObservationStore;
import net.enthusia.loreitems.sqlite.SQLiteHeldItemAdoptionStore;
import net.enthusia.loreitems.sqlite.SQLiteItemAnomalyObservationStore;
import net.enthusia.loreitems.sqlite.SQLiteObservationRepository;
import net.enthusia.loreitems.sqlite.SQLitePendingMutationRepository;
import net.enthusia.loreitems.sqlite.SQLiteStorageRuntime;
import net.enthusia.loreitems.sqlite.SQLiteTemplateManagementQueryStore;
import net.enthusia.loreitems.sqlite.SQLiteTemplateRevisionRolloutStore;
import net.enthusia.loreitems.sqlite.SQLiteTrackingObservationStore;
import net.enthusia.loreitems.sqlite.SQLiteUnitOfWork;
import net.enthusia.loreitems.sqlite.SQLiteVoidLossStore;

/** Constructs the durable use-case graph after SQLite reaches read-write state. */
final class LoreItemsStorageServices {
    private LoreItemsStorageServices() {}

    static Services create(
            SQLiteStorageRuntime runtime,
            FoundationConfiguration configuration) {
        Clock clock = Clock.systemUTC();
        Repositories repositories = new Repositories(
                new SQLiteDirectDeliveryRepository(runtime),
                new SQLitePendingMutationRepository(runtime));
        TemplateRevisionRolloutUseCase rollout = rollout(runtime, clock);
        return new Services(
                repositories,
                new WritableServices(
                        deliveryService(repositories.deliveries(), runtime, clock),
                        directDelivery(repositories.deliveries(), configuration, clock),
                        new PersistingCreateDefinitionUseCase(new SQLiteUnitOfWork(runtime), clock),
                        adoptHeldItem(runtime, configuration, clock),
                        voidLoss(runtime, configuration, clock),
                        displayObservation(runtime, clock),
                        trackingObservation(runtime, clock)),
                new AdministrationServices(
                        administration(runtime, repositories),
                        anomalyObservation(runtime, clock),
                        rollout,
                        templateManagement(runtime, rollout)));
    }

    private static LoreItemsServiceV1 deliveryService(
            SQLiteDirectDeliveryRepository deliveries,
            SQLiteStorageRuntime runtime,
            Clock clock) {
        return new FoundationLoreItemsService(
                new PersistingExternalDeliveryUseCase(deliveries, clock),
                new SQLiteDefinitionRepository(runtime));
    }

    private static DirectDeliveryExecutionUseCase directDelivery(
            SQLiteDirectDeliveryRepository deliveries,
            FoundationConfiguration configuration,
            Clock clock) {
        return new PersistingDirectDeliveryExecutionUseCase(
                deliveries,
                clock,
                Duration.ofSeconds(configuration.deliveryClaimLeaseSeconds()));
    }

    private static AdoptHeldItemUseCase adoptHeldItem(
            SQLiteStorageRuntime runtime,
            FoundationConfiguration configuration,
            Clock clock) {
        return new PersistingAdoptHeldItemUseCase(
                new SQLiteHeldItemAdoptionStore(runtime),
                clock,
                Duration.ofSeconds(configuration.deliveryClaimLeaseSeconds()));
    }

    private static VoidLossUseCase voidLoss(
            SQLiteStorageRuntime runtime,
            FoundationConfiguration configuration,
            Clock clock) {
        return new PersistingVoidLossUseCase(
                new SQLiteVoidLossStore(runtime),
                clock,
                Duration.ofSeconds(configuration.deliveryClaimLeaseSeconds()));
    }

    private static DisplayItemObservationUseCase displayObservation(
            SQLiteStorageRuntime runtime,
            Clock clock) {
        return new PersistingDisplayItemObservationUseCase(
                new SQLiteDisplayItemObservationStore(runtime), clock);
    }

    private static TrackingObservationUseCase trackingObservation(
            SQLiteStorageRuntime runtime,
            Clock clock) {
        return new PersistingTrackingObservationUseCase(
                new SQLiteTrackingObservationStore(runtime), clock);
    }

    private static LoreItemsAdministrationUseCase administration(
            SQLiteStorageRuntime runtime,
            Repositories repositories) {
        return new PersistingLoreItemsAdministrationUseCase(
                new SQLiteAnomalyRepository(runtime),
                new SQLiteAuditRepository(runtime),
                new SQLiteCurrentStateRepository(runtime),
                new SQLiteObservationRepository(runtime),
                repositories.deliveries(),
                repositories.mutations());
    }

    private static ItemAnomalyObservationUseCase anomalyObservation(
            SQLiteStorageRuntime runtime,
            Clock clock) {
        return new PersistingItemAnomalyObservationUseCase(
                new SQLiteItemAnomalyObservationStore(runtime), clock);
    }

    private static TemplateRevisionRolloutUseCase rollout(
            SQLiteStorageRuntime runtime,
            Clock clock) {
        return new PersistingTemplateRevisionRolloutUseCase(
                new SQLiteTemplateRevisionRolloutStore(runtime), clock);
    }

    private static TemplateManagementUseCase templateManagement(
            SQLiteStorageRuntime runtime,
            TemplateRevisionRolloutUseCase rollout) {
        return new PersistingTemplateManagementUseCase(
                new SQLiteTemplateManagementQueryStore(runtime), rollout);
    }

    record Services(
            Repositories repositories,
            WritableServices writable,
            AdministrationServices administration) {}

    record Repositories(
            SQLiteDirectDeliveryRepository deliveries,
            SQLitePendingMutationRepository mutations) {}

    record WritableServices(
            LoreItemsServiceV1 deliveryService,
            DirectDeliveryExecutionUseCase directDelivery,
            CreateDefinitionUseCase createDefinition,
            AdoptHeldItemUseCase adoptHeldItem,
            VoidLossUseCase voidLoss,
            DisplayItemObservationUseCase displayObservation,
            TrackingObservationUseCase trackingObservation) {}

    record AdministrationServices(
            LoreItemsAdministrationUseCase administrationUseCase,
            ItemAnomalyObservationUseCase anomalyObservation,
            TemplateRevisionRolloutUseCase rollout,
            TemplateManagementUseCase templateManagement) {}
}
