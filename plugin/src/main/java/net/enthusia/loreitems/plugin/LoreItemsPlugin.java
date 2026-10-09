package net.enthusia.loreitems.plugin;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicReference;
import net.enthusia.loreitems.api.v1.LoreItemsServiceV1;
import net.enthusia.loreitems.application.AdoptHeldItemUseCase;
import net.enthusia.loreitems.application.AnomalyWarningSink;
import net.enthusia.loreitems.application.AtomicConfiguration;
import net.enthusia.loreitems.application.CreateDefinitionUseCase;
import net.enthusia.loreitems.application.DirectDeliveryExecutionUseCase;
import net.enthusia.loreitems.application.DisplayItemObservationUseCase;
import net.enthusia.loreitems.application.FoundationConfiguration;
import net.enthusia.loreitems.application.ItemAnomalyObservationUseCase;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.StorageState;
import net.enthusia.loreitems.application.TemplateManagementUseCase;
import net.enthusia.loreitems.application.TemplateRevisionRolloutUseCase;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.application.VoidLossUseCase;
import net.enthusia.loreitems.paper.AdoptHeldItemCommandExecutor;
import net.enthusia.loreitems.paper.CreateDefinitionCommandExecutor;
import net.enthusia.loreitems.paper.FoundationConfigurationReloadCommandExecutor;
import net.enthusia.loreitems.paper.GiveLoreItemCommandExecutor;
import net.enthusia.loreitems.paper.LoreItemsAdministrationCommandExecutor;
import net.enthusia.loreitems.paper.LoreItemsCommandExecutor;
import net.enthusia.loreitems.paper.PaperAnomalyWarningWorker;
import net.enthusia.loreitems.paper.PaperDirectDeliveryOperator;
import net.enthusia.loreitems.paper.PaperDirectDeliveryWorker;
import net.enthusia.loreitems.paper.PaperDisplayItemListener;
import net.enthusia.loreitems.paper.PaperHeldItemAdoptionOperator;
import net.enthusia.loreitems.paper.PaperHeldItemDefinitionSnapshotter;
import net.enthusia.loreitems.paper.PaperIdentityAnomalyListener;
import net.enthusia.loreitems.paper.PaperMutationRecoveryWorker;
import net.enthusia.loreitems.paper.PaperPhysicalTrackingListener;
import net.enthusia.loreitems.paper.PaperTrackingCoordinator;
import net.enthusia.loreitems.paper.PaperTrackedItemProtectionListener;
import net.enthusia.loreitems.paper.PaperTemplateRevisionPlannerWorker;
import net.enthusia.loreitems.paper.PaperUniqueAccessTrackingListener;
import net.enthusia.loreitems.sqlite.BoundedDatabaseExecutor;
import net.enthusia.loreitems.sqlite.MigrationRunner;
import net.enthusia.loreitems.sqlite.SQLiteConnectionFactory;
import net.enthusia.loreitems.sqlite.SQLiteDirectDeliveryRepository;
import net.enthusia.loreitems.sqlite.SQLitePendingMutationRepository;
import net.enthusia.loreitems.sqlite.SQLiteStorageRuntime;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.java.JavaPlugin;

// Paper plugins may own bounded lifecycle executors; this is not a J2EE web application.
@SuppressWarnings({"PMD.DoNotUseThreads", "PMD.NullAssignment"})
public final class LoreItemsPlugin extends JavaPlugin {
    private static final String STOPPING_RELOAD_DETAIL =
            "The plugin is stopping; configuration reload was not applied.";

    private final AtomicReference<LoreItemsServiceV1> serviceDelegate =
            new AtomicReference<>(LoreItemsServiceDelegates.unavailable(
                    "Foundation storage has not started."));
    private final AtomicReference<AtomicConfiguration> configuration =
            new AtomicReference<>(new AtomicConfiguration(FoundationConfiguration.defaults()));
    private final StartupConfigurationGate startupConfigurationGate = new StartupConfigurationGate();
    private final AtomicReference<CreateDefinitionUseCase> createDefinitionDelegate =
            new AtomicReference<>(UnavailableLoreItemsUseCases.createDefinition());
    private final AtomicReference<AdoptHeldItemUseCase> adoptHeldItemDelegate =
            new AtomicReference<>(UnavailableLoreItemsUseCases.adoptHeldItem());
    private final AtomicReference<VoidLossUseCase> voidLossDelegate =
            new AtomicReference<>(UnavailableLoreItemsUseCases.voidLoss());
    private final AtomicReference<DisplayItemObservationUseCase> displayObservationDelegate =
            new AtomicReference<>(UnavailableLoreItemsUseCases.displayObservation());
    private final LoreItemsServiceV1 registeredService =
            LoreItemsServiceDelegates.delegating(serviceDelegate);
    private final LoreItemsCreativeSupport creativeSupport =
            new LoreItemsCreativeSupport(this, registeredService, this::wakeDirectDeliveries);
    private final CreateDefinitionUseCase registeredCreateDefinitionUseCase =
            request -> createDefinitionDelegate.get().create(request);
    private final Object lifecycleLock = new Object();
    private final Set<CompletableFuture<AtomicConfiguration.ReloadResult>> pendingReloads =
            ConcurrentHashMap.newKeySet();
    private volatile ThreadPoolExecutor lifecycleExecutor =
            LoreItemsShutdownSupport.createLifecycleExecutor();
    private volatile CompletionStage<Void> shutdownTrackingQuiescence =
            CompletableFuture.completedFuture(null);

    private volatile SQLiteStorageRuntime storageRuntime;
    private volatile PaperDirectDeliveryWorker directDeliveryWorker;
    private volatile PaperMutationRecoveryWorker mutationRecoveryWorker;
    private volatile PaperTrackedItemProtectionListener protectionListener;
    private volatile PaperDisplayItemListener displayItemListener;
    private volatile PaperPhysicalTrackingListener physicalTrackingListener;
    private volatile PaperUniqueAccessTrackingListener uniqueAccessTrackingListener;
    private volatile PaperIdentityAnomalyListener identityAnomalyListener;
    private volatile PaperAnomalyWarningWorker anomalyWarningWorker;
    private volatile PaperTemplateRevisionPlannerWorker templateRevisionPlannerWorker;
    private volatile LoreItemsAdministrationCommandExecutor administrationCommandExecutor;
    private volatile DistributionRuntime distributionRuntime;
    private volatile boolean shutdownCleanupComplete = true;
    private volatile boolean stopping;

    @Override
    public void onEnable() {
        if (!prepareForEnable()) {
            getLogger().severe(
                    "LoreItems cannot re-enable because previous asynchronous workers have not terminated.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        registerCommands();
        if (!activateProtectionListeners()) {
            return;
        }
        getServer().getServicesManager().register(
                LoreItemsServiceV1.class,
                registeredService,
                this,
                ServicePriority.Normal);
        Path dataDirectory = getDataFolder().toPath();
        try {
            lifecycleExecutor.execute(() -> initialize(dataDirectory));
        } catch (RejectedExecutionException exception) {
            publishUnavailableServices("Foundation lifecycle queue rejected startup.");
            getLogger().severe("LoreItems startup was rejected: " + exception.getMessage());
        }
        getLogger().info("Foundation bootstrap enabled; durable storage is initializing off-thread.");
    }

    private boolean prepareForEnable() {
        synchronized (lifecycleLock) {
            if (!stopping) {
                return true;
            }
            if (!shutdownReadyForEnable()) {
                return false;
            }
            resetAfterShutdown();
            return true;
        }
    }

    private boolean shutdownReadyForEnable() {
        if (!shutdownCleanupComplete
                || !lifecycleExecutor.isTerminated()
                || !LoreItemsShutdownSupport.trackingQuiesced(shutdownTrackingQuiescence)) {
            return false;
        }
        SQLiteStorageRuntime previousStorage = storageRuntime;
        if (previousStorage != null && !previousStorage.isTerminated()) {
            return false;
        }
        DistributionRuntime previousDistribution = distributionRuntime;
        return previousDistribution == null || previousDistribution.isTerminated();
    }

    private void resetAfterShutdown() {
        startupConfigurationGate.reset();
        storageRuntime = null;
        directDeliveryWorker = null;
        mutationRecoveryWorker = null;
        protectionListener = null;
        displayItemListener = null;
        physicalTrackingListener = null;
        uniqueAccessTrackingListener = null;
        identityAnomalyListener = null;
        anomalyWarningWorker = null;
        templateRevisionPlannerWorker = null;
        administrationCommandExecutor = null;
        distributionRuntime = null;
        pendingReloads.clear();
        shutdownTrackingQuiescence = CompletableFuture.completedFuture(null);
        lifecycleExecutor = LoreItemsShutdownSupport.createLifecycleExecutor();
        stopping = false;
    }

    private void registerCommands() {
        PluginCommand command = Objects.requireNonNull(
                getCommand("loreitems"), "plugin.yml must declare the loreitems command");
        CreateDefinitionCommandExecutor createExecutor = new CreateDefinitionCommandExecutor(
                this, registeredCreateDefinitionUseCase, new PaperHeldItemDefinitionSnapshotter());
        AdoptHeldItemCommandExecutor adoptExecutor = new AdoptHeldItemCommandExecutor(
                this, adoptHeldItemDelegate::get, new PaperHeldItemAdoptionOperator());
        GiveLoreItemCommandExecutor giveExecutor = new GiveLoreItemCommandExecutor(
                this, registeredService, this::wakeDirectDeliveries);
        LoreItemsAdministrationCommandExecutor administrationExecutor =
                new LoreItemsAdministrationCommandExecutor(
                        this,
                        () -> configuration.get().current().defaultPageSize(),
                        () -> configuration.get().current().mutationBudgetPerTick(),
                        this::wakeTemplateRolloutPlanning);
        administrationCommandExecutor = administrationExecutor;
        FoundationConfigurationReloadCommandExecutor reloadExecutor =
                new FoundationConfigurationReloadCommandExecutor(
                        this, this::reloadFoundationConfiguration);
        LoreItemsCommandExecutor executor = new LoreItemsCommandExecutor(
                createExecutor,
                adoptExecutor,
                giveExecutor,
                administrationExecutor,
                reloadExecutor);
        creativeSupport.install(executor);
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private boolean activateProtectionListeners() {
        PaperTrackedItemProtectionListener protection = null;
        PaperDisplayItemListener display = null;
        try {
            protection = new PaperTrackedItemProtectionListener(
                    this,
                    voidLossDelegate::get,
                    () -> configuration.get().current().mutationBudgetPerTick(),
                    () -> startupConfigurationGate.sharedContainersAllowed(
                            configuration.get().current()));
            creativeSupport.install(protection);
            display = new PaperDisplayItemListener(
                    this,
                    displayObservationDelegate::get,
                    () -> configuration.get().current().mutationBudgetPerTick());
            protection.start();
            display.start();
            protectionListener = protection;
            displayItemListener = display;
            return true;
        } catch (RuntimeException exception) {
            closeQuietly(display, "display-item listener");
            closeQuietly(protection, "tracked-item protection listener");
            getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Could not start tracked-item protection listeners; disabling LoreItems.",
                    exception);
            getServer().getPluginManager().disablePlugin(this);
            return false;
        }
    }

    @Override
    public void onDisable() {
        synchronized (lifecycleLock) {
            if (stopping && !shutdownCleanupComplete) {
                return;
            }
            stopping = true;
            shutdownCleanupComplete = false;
            setUnavailableDelegates("The plugin is stopping.");
        }
        creativeSupport.clear();
        CompletionStage<Void> trackingQuiescence = PaperTrackingCoordinator.quiescenceFor(this);
        shutdownTrackingQuiescence = trackingQuiescence;
        closeQuietly(uniqueAccessTrackingListener, "unique-access tracking listener");
        closeQuietly(physicalTrackingListener, "physical tracking listener");
        closeQuietly(distributionRuntime, "mass distribution runtime");
        closeQuietly(identityAnomalyListener, "identity-anomaly listener");
        closeQuietly(anomalyWarningWorker, "anomaly-warning worker");
        closeQuietly(templateRevisionPlannerWorker, "template-revision planner");
        closeQuietly(administrationCommandExecutor, "administration command executor");
        closeQuietly(displayItemListener, "display-item listener");
        closeQuietly(protectionListener, "tracked-item protection listener");
        closeQuietly(directDeliveryWorker, "direct-delivery worker");
        closeQuietly(mutationRecoveryWorker, "mutation-recovery worker");
        getServer().getServicesManager().unregisterAll(this);
        ThreadPoolExecutor executor = lifecycleExecutor;
        executor.shutdownNow();
        failPendingReloads(STOPPING_RELOAD_DETAIL);

        int timeoutSeconds = configuration.get().current().databaseShutdownTimeoutSeconds();
        LoreItemsShutdownSupport.start(
                getLogger(),
                trackingQuiescence,
                storageRuntime,
                executor,
                Duration.ofSeconds(timeoutSeconds),
                () -> {
                    synchronized (lifecycleLock) {
                        shutdownCleanupComplete = true;
                    }
                });
    }

    public CompletionStage<AtomicConfiguration.ReloadResult> reloadFoundationConfiguration() {
        LoreItemsAdministrationCommandExecutor administration = administrationCommandExecutor;
        if (administration != null) {
            administration.closeEditorSessions("configuration reload");
        }
        CompletableFuture<AtomicConfiguration.ReloadResult> result = new CompletableFuture<>();
        pendingReloads.add(result);
        result.whenComplete((ignored, throwable) -> pendingReloads.remove(result));
        synchronized (lifecycleLock) {
            if (stopping) {
                result.complete(new AtomicConfiguration.ReloadResult(false, STOPPING_RELOAD_DETAIL));
                return result;
            }
            try {
                lifecycleExecutor.execute(() -> reloadConfiguration(result));
            } catch (RejectedExecutionException exception) {
                result.complete(new AtomicConfiguration.ReloadResult(
                        false, "The lifecycle queue is not accepting reload work."));
            }
        }
        return result;
    }

    private void reloadConfiguration(
            CompletableFuture<AtomicConfiguration.ReloadResult> result) {
        if (stopping || result.isDone()) {
            result.complete(new AtomicConfiguration.ReloadResult(false, STOPPING_RELOAD_DETAIL));
            return;
        }
        try {
            FoundationConfiguration candidate =
                    new FoundationConfigurationLoader(getDataFolder().toPath()).loadOrCreate();
            synchronized (lifecycleLock) {
                if (stopping || result.isDone()) {
                    result.complete(new AtomicConfiguration.ReloadResult(
                            false, STOPPING_RELOAD_DETAIL));
                    return;
                }
                result.complete(configuration.get().replace(candidate));
            }
        } catch (Exception exception) {
            result.complete(new AtomicConfiguration.ReloadResult(
                    false,
                    exception.getClass().getSimpleName() + ": " + safeMessage(exception)));
        }
    }

    private void failPendingReloads(String detail) {
        AtomicConfiguration.ReloadResult failure =
                new AtomicConfiguration.ReloadResult(false, detail);
        pendingReloads.forEach(future -> future.complete(failure));
    }

    private void initialize(Path dataDirectory) {
        try {
            FoundationConfiguration loaded =
                    new FoundationConfigurationLoader(dataDirectory).loadOrCreate();
            if (!publishConfiguration(loaded)) {
                return;
            }
            SQLiteStorageRuntime runtime = createStorageRuntime(dataDirectory, loaded);
            if (!publishStorageRuntime(runtime)) {
                runtime.close(Duration.ZERO);
                return;
            }
            initializeStorage(runtime, loaded);
        } catch (Exception exception) {
            handleInitializationFailure(exception);
        }
    }

    private boolean publishConfiguration(FoundationConfiguration loaded) {
        synchronized (lifecycleLock) {
            if (stopping) {
                return false;
            }
            configuration.set(new AtomicConfiguration(loaded));
            startupConfigurationGate.publish();
            return true;
        }
    }

    private static SQLiteStorageRuntime createStorageRuntime(
            Path dataDirectory, FoundationConfiguration loaded) {
        MetricsPort metrics = MetricsPort.noOp();
        BoundedDatabaseExecutor databaseExecutor = new BoundedDatabaseExecutor(
                "loreitems-database", loaded.databaseQueueCapacity(), metrics);
        return new SQLiteStorageRuntime(
                new SQLiteConnectionFactory(
                        dataDirectory.resolve("loreitems.db"), loaded.databaseBusyTimeoutMillis()),
                new MigrationRunner(),
                databaseExecutor,
                metrics);
    }

    private boolean publishStorageRuntime(SQLiteStorageRuntime runtime) {
        synchronized (lifecycleLock) {
            if (stopping) {
                return false;
            }
            storageRuntime = runtime;
            return true;
        }
    }

    private void initializeStorage(
            SQLiteStorageRuntime runtime,
            FoundationConfiguration loaded) {
        SQLiteStorageRuntime.StartupResult startup = runtime.start().toCompletableFuture().join();
        if (stopping) {
            return;
        }
        if (startup.state() != StorageState.READ_WRITE) {
            publishDegradedService(startup);
            return;
        }
        LoreItemsStorageServices.Services services = LoreItemsStorageServices.create(runtime, loaded);
        LoreItemsStorageServices.Repositories repositories = services.repositories();
        LoreItemsStorageServices.WritableServices writable = services.writable();
        LoreItemsStorageServices.AdministrationServices administration = services.administration();
        recoverExpiredClaims(repositories.deliveries(), loaded.deliveryClaimBatchSize());
        recoverExpiredMutationClaims(repositories.mutations(), loaded.deliveryClaimBatchSize());
        if (!publishWritableServices(
                writable.deliveryService(),
                writable.createDefinition(),
                writable.adoptHeldItem(),
                writable.voidLoss(),
                writable.displayObservation())) {
            return;
        }
        creativeSupport.activate(runtime);
        if (!StartupActivationSequence.run(
                () -> activateTrackingListeners(writable.trackingObservation(), runtime.metrics()),
                () -> activateDirectDeliveryWorker(writable.directDelivery(), loaded),
                () -> activateMutationRecoveryWorker(repositories.mutations(), loaded),
                () -> activateAdministrationServices(
                        administration.administrationUseCase(),
                        administration.anomalyObservation(),
                        administration.templateManagement(),
                        administration.rollout(),
                        loaded),
                () -> activateDistributionRuntime(runtime, loaded))) {
            return;
        }
        getLogger().info(
                "Durable storage is active; definition creation, adoption, protection, "
                        + "physical tracking, display observations, terminal void loss, and "
                        + "mass distributions are available. Delivery, recovery, anomaly, "
                        + "tracking, and administration components are activating on the "
                        + "server thread.");
    }

    private boolean activateTrackingListeners(
            TrackingObservationUseCase useCase,
            MetricsPort metrics) {
        try {
            getServer().getScheduler().runTask(
                    this,
                    () -> activateTrackingListenersOnMainThread(useCase, metrics));
            return true;
        } catch (RuntimeException exception) {
            failStartupActivation(
                    "Lore-item physical tracking activation could not be scheduled.",
                    "Could not schedule lore-item physical tracking; disabling LoreItems.",
                    exception);
            return false;
        }
    }

    private void activateTrackingListenersOnMainThread(
            TrackingObservationUseCase useCase,
            MetricsPort metrics) {
        synchronized (lifecycleLock) {
            if (stopping
                    || physicalTrackingListener != null
                    || uniqueAccessTrackingListener != null) {
                return;
            }
            PaperPhysicalTrackingListener physical = null;
            PaperUniqueAccessTrackingListener unique = null;
            try {
                physical = new PaperPhysicalTrackingListener(
                        this,
                        () -> useCase,
                        () -> configuration.get().current().mutationBudgetPerTick(),
                        metrics);
                unique = new PaperUniqueAccessTrackingListener(
                        this,
                        () -> useCase,
                        () -> configuration.get().current().mutationBudgetPerTick(),
                        metrics);
                physical.start();
                unique.start();
                physicalTrackingListener = physical;
                uniqueAccessTrackingListener = unique;
                getLogger().info(
                        "Physical lore-item tracking and natural-access reconciliation are active.");
            } catch (RuntimeException exception) {
                PaperUniqueAccessTrackingListener failedUnique = unique;
                PaperPhysicalTrackingListener failedPhysical = physical;
                failMainThreadStartupActivation(
                        "Lore-item physical tracking activation failed.",
                        "Could not start lore-item physical tracking; disabling LoreItems.",
                        exception,
                        () -> {
                            closeQuietly(failedUnique, "unique-access tracking listener");
                            closeQuietly(failedPhysical, "physical tracking listener");
                        });
            }
        }
    }

    private boolean activateDirectDeliveryWorker(
            DirectDeliveryExecutionUseCase useCase,
            FoundationConfiguration loaded) {
        try {
            getServer().getScheduler().runTask(this, () -> {
                synchronized (lifecycleLock) {
                    if (stopping || directDeliveryWorker != null) {
                        return;
                    }
                    PaperDirectDeliveryWorker worker = null;
                    try {
                        worker = new PaperDirectDeliveryWorker(
                                this,
                                useCase,
                                new PaperDirectDeliveryOperator(),
                                loaded.deliveryClaimBatchSize(),
                                loaded.mutationBudgetPerTick());
                        worker.start();
                        directDeliveryWorker = worker;
                        getLogger().info("Queued direct-delivery processing is active.");
                    } catch (RuntimeException exception) {
                        PaperDirectDeliveryWorker failedWorker = worker;
                        failMainThreadStartupActivation(
                                "Direct-delivery activation failed.",
                                "Could not start the direct-delivery worker; disabling LoreItems.",
                                exception,
                                () -> closeQuietly(failedWorker, "direct-delivery worker"));
                    }
                }
            });
            return true;
        } catch (RuntimeException exception) {
            failStartupActivation(
                    "Direct-delivery activation could not be scheduled.",
                    "Could not schedule the direct-delivery worker; disabling LoreItems.",
                    exception);
            return false;
        }
    }

    private boolean activateMutationRecoveryWorker(
            SQLitePendingMutationRepository repository,
            FoundationConfiguration loaded) {
        try {
            getServer().getScheduler().runTask(this, () -> {
                synchronized (lifecycleLock) {
                    if (stopping || mutationRecoveryWorker != null) {
                        return;
                    }
                    PaperMutationRecoveryWorker worker = null;
                    try {
                        worker = new PaperMutationRecoveryWorker(
                                this,
                                repository,
                                Math.min(
                                        loaded.deliveryClaimBatchSize(),
                                        loaded.mutationBudgetPerTick()));
                        worker.start();
                        mutationRecoveryWorker = worker;
                        getLogger().info("Expired item-mutation recovery is active.");
                    } catch (RuntimeException exception) {
                        PaperMutationRecoveryWorker failedWorker = worker;
                        failMainThreadStartupActivation(
                                "Expired mutation recovery activation failed.",
                                "Could not start expired mutation recovery; disabling LoreItems.",
                                exception,
                                () -> closeQuietly(failedWorker, "mutation-recovery worker"));
                    }
                }
            });
            return true;
        } catch (RuntimeException exception) {
            failStartupActivation(
                    "Expired mutation recovery activation could not be scheduled.",
                    "Could not schedule expired mutation recovery; disabling LoreItems.",
                    exception);
            return false;
        }
    }

    private boolean activateDistributionRuntime(
            SQLiteStorageRuntime runtime,
            FoundationConfiguration loaded) {
        DistributionRuntime distribution = null;
        try {
            distribution = new DistributionRuntime(
                    this,
                    runtime,
                    loaded,
                    lifecycleExecutor,
                    () -> !stopping,
                    () -> fenceFatalStartup(
                            "Mass distribution startup failed; writes are unavailable."));
            synchronized (lifecycleLock) {
                if (stopping) {
                    closeQuietly(distribution, "mass distribution runtime");
                    return false;
                }
                distributionRuntime = distribution;
            }
            distribution.activate();
            return true;
        } catch (Exception exception) {
            fenceFatalStartup("Mass distribution startup failed; writes are unavailable.");
            closeQuietly(distribution, "mass distribution runtime");
            failStartupActivation(
                    "Mass distribution startup failed; writes are unavailable.",
                    "Could not initialize mass distribution runtime; disabling LoreItems.",
                    exception);
            return false;
        }
    }

    private boolean activateAdministrationServices(
            LoreItemsAdministrationUseCase administrationUseCase,
            ItemAnomalyObservationUseCase anomalyObservationUseCase,
            TemplateManagementUseCase templateManagementUseCase,
            TemplateRevisionRolloutUseCase rolloutUseCase,
            FoundationConfiguration loaded) {
        try {
            getServer().getScheduler().runTask(
                    this,
                    () -> activateAdministrationServicesOnMainThread(
                            administrationUseCase,
                            anomalyObservationUseCase,
                            templateManagementUseCase,
                            rolloutUseCase,
                            loaded));
            return true;
        } catch (RuntimeException exception) {
            failStartupActivation(
                    "Lore-item anomaly and administration activation could not be scheduled.",
                    "Could not schedule lore-item anomaly and administration services; "
                            + "disabling LoreItems.",
                    exception);
            return false;
        }
    }

    private void activateAdministrationServicesOnMainThread(
            LoreItemsAdministrationUseCase administrationUseCase,
            ItemAnomalyObservationUseCase anomalyObservationUseCase,
            TemplateManagementUseCase templateManagementUseCase,
            TemplateRevisionRolloutUseCase rolloutUseCase,
            FoundationConfiguration loaded) {
        synchronized (lifecycleLock) {
            if (stopping || identityAnomalyListener != null) {
                return;
            }
            startAdministrationComponents(
                    administrationUseCase,
                    anomalyObservationUseCase,
                    templateManagementUseCase,
                    rolloutUseCase,
                    loaded);
        }
    }

    private void startAdministrationComponents(
            LoreItemsAdministrationUseCase administrationUseCase,
            ItemAnomalyObservationUseCase anomalyObservationUseCase,
            TemplateManagementUseCase templateManagementUseCase,
            TemplateRevisionRolloutUseCase rolloutUseCase,
            FoundationConfiguration loaded) {
        AdministrationStartupComponents components = null;
        ServicesManager services = null;
        try {
            components = AdministrationStartupComponents.create(
                    this, administrationUseCase, rolloutUseCase, loaded,
                    this::wakeAccessibleTemplateUpdates);
            services = getServer().getServicesManager();
            registerAdministrationServices(
                    services, administrationUseCase, anomalyObservationUseCase,
                    templateManagementUseCase, components.warningWorker());
            activateAdministrationWorkers(components);
        } catch (RuntimeException exception) {
            ServicesManager failedServices = services;
            AdministrationStartupComponents failedComponents = components;
            failMainThreadStartupActivation(
                    "Lore-item anomaly and administration activation failed.",
                    "Could not activate lore-item anomaly and administration services; "
                            + "disabling LoreItems.",
                    exception,
                    () -> rollbackAdministrationServices(
                            failedServices, administrationUseCase, anomalyObservationUseCase,
                            templateManagementUseCase, failedComponents));
        }
    }

    private void activateAdministrationWorkers(AdministrationStartupComponents components) {
        components.warningWorker().start();
        components.anomalyListener().start();
        components.planner().start();
        anomalyWarningWorker = components.warningWorker();
        identityAnomalyListener = components.anomalyListener();
        templateRevisionPlannerWorker = components.planner();
        getLogger().info(
                "Lore-item anomaly detection, warnings, and administration are active.");
    }

    private void registerAdministrationServices(
            ServicesManager services,
            LoreItemsAdministrationUseCase administrationUseCase,
            ItemAnomalyObservationUseCase anomalyObservationUseCase,
            TemplateManagementUseCase templateManagementUseCase,
            PaperAnomalyWarningWorker warningWorker) {
        services.register(
                LoreItemsAdministrationUseCase.class, administrationUseCase, this, ServicePriority.Normal);
        services.register(
                ItemAnomalyObservationUseCase.class, anomalyObservationUseCase, this, ServicePriority.Normal);
        services.register(
                TemplateManagementUseCase.class, templateManagementUseCase, this, ServicePriority.Normal);
        services.register(
                AnomalyWarningSink.class, warningWorker, this, ServicePriority.Normal);
    }

    private void rollbackAdministrationServices(
            ServicesManager services,
            LoreItemsAdministrationUseCase administrationUseCase,
            ItemAnomalyObservationUseCase anomalyObservationUseCase,
            TemplateManagementUseCase templateManagementUseCase,
            AdministrationStartupComponents components) {
        if (components != null) {
            components.close();
        }
        if (services == null) {
            return;
        }
        if (components != null) {
            services.unregister(AnomalyWarningSink.class, components.warningWorker());
        }
        services.unregister(TemplateManagementUseCase.class, templateManagementUseCase);
        services.unregister(ItemAnomalyObservationUseCase.class, anomalyObservationUseCase);
        services.unregister(LoreItemsAdministrationUseCase.class, administrationUseCase);
    }

    private void wakeDirectDeliveries(UUID playerId) {
        PaperDirectDeliveryWorker worker = directDeliveryWorker;
        if (worker != null) {
            worker.wakePlayer(playerId);
        }
    }

    private void wakeTemplateRolloutPlanning() {
        PaperTemplateRevisionPlannerWorker planner = templateRevisionPlannerWorker;
        if (planner != null) {
            planner.wake();
        }
        wakeAccessibleTemplateUpdates();
    }

    private void wakeAccessibleTemplateUpdates() {
        PaperMutationRecoveryWorker mutations = mutationRecoveryWorker;
        if (mutations != null) {
            mutations.wakeAccessible();
        }
    }

    private void publishDegradedService(SQLiteStorageRuntime.StartupResult startup) {
        if (publishUnavailableServices(
                "LoreItems is in degraded read-only mode: " + startup.detail())) {
            getLogger().severe(
                    "LoreItems entered degraded read-only mode: " + startup.detail());
        }
    }

    private boolean publishWritableServices(
            LoreItemsServiceV1 service,
            CreateDefinitionUseCase createDefinitionUseCase,
            AdoptHeldItemUseCase adoptHeldItemUseCase,
            VoidLossUseCase voidLossUseCase,
            DisplayItemObservationUseCase displayObservationUseCase) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(createDefinitionUseCase, "createDefinitionUseCase");
        Objects.requireNonNull(adoptHeldItemUseCase, "adoptHeldItemUseCase");
        Objects.requireNonNull(voidLossUseCase, "voidLossUseCase");
        Objects.requireNonNull(displayObservationUseCase, "displayObservationUseCase");
        synchronized (lifecycleLock) {
            if (stopping) {
                return false;
            }
            serviceDelegate.set(service);
            createDefinitionDelegate.set(createDefinitionUseCase);
            adoptHeldItemDelegate.set(adoptHeldItemUseCase);
            voidLossDelegate.set(voidLossUseCase);
            displayObservationDelegate.set(displayObservationUseCase);
            return true;
        }
    }

    private boolean publishUnavailableServices(String detail) {
        Objects.requireNonNull(detail, "detail");
        synchronized (lifecycleLock) {
            if (stopping) {
                return false;
            }
            setUnavailableDelegates(detail);
            return true;
        }
    }

    private void failStartupActivation(
            String unavailableDetail,
            String message,
            Exception exception) {
        FatalStartupFailurePolicy.revokeWritesThenRequestDisable(
                () -> fenceFatalStartup(unavailableDetail),
                () -> getServer().getScheduler().runTask(
                        this,
                        () -> getServer().getPluginManager().disablePlugin(this)),
                schedulingFailure -> getLogger().log(
                        java.util.logging.Level.SEVERE,
                        "Could not schedule LoreItems disable after fatal startup failure.",
                        schedulingFailure));
        getLogger().log(java.util.logging.Level.SEVERE, message, exception);
    }

    private void failMainThreadStartupActivation(
            String unavailableDetail,
            String message,
            Exception exception,
            Runnable cleanup) {
        FatalStartupFailurePolicy.revokeWritesThenCleanupAndRequestDisable(
                () -> fenceFatalStartup(unavailableDetail),
                cleanup,
                () -> getServer().getPluginManager().disablePlugin(this),
                cleanupOrDisableFailure -> getLogger().log(
                        java.util.logging.Level.SEVERE,
                        "Could not complete cleanup/disable after fatal startup failure.",
                        cleanupOrDisableFailure));
        getLogger().log(java.util.logging.Level.SEVERE, message, exception);
    }

    private void fenceFatalStartup(String detail) {
        Objects.requireNonNull(detail, "detail");
        synchronized (lifecycleLock) {
            if (stopping) {
                return;
            }
            setUnavailableDelegates(detail);
            stopping = true;
        }
    }

    private void setUnavailableDelegates(String detail) {
        serviceDelegate.set(LoreItemsServiceDelegates.unavailable(detail));
        createDefinitionDelegate.set(UnavailableLoreItemsUseCases.createDefinition());
        adoptHeldItemDelegate.set(UnavailableLoreItemsUseCases.adoptHeldItem());
        voidLossDelegate.set(UnavailableLoreItemsUseCases.voidLoss());
        displayObservationDelegate.set(UnavailableLoreItemsUseCases.displayObservation());
    }

    private void recoverExpiredClaims(
            SQLiteDirectDeliveryRepository repository,
            int recoveryLimit) {
        int recovered = repository.moveExpiredClaimsToReview(Instant.now(), recoveryLimit)
                .toCompletableFuture()
                .join();
        if (recovered > 0) {
            getLogger().warning(
                    "Moved " + recovered + " expired delivery claims to REVIEW_REQUIRED.");
        }
        if (recovered == recoveryLimit) {
            getLogger().warning(
                    "The bounded startup recovery batch was full; additional expired "
                            + "delivery claims may remain for later recovery.");
        }
    }

    private void recoverExpiredMutationClaims(
            SQLitePendingMutationRepository repository,
            int recoveryLimit) {
        int recovered = repository.moveExpiredClaimsToReview(Instant.now(), recoveryLimit)
                .toCompletableFuture()
                .join();
        if (recovered > 0) {
            getLogger().warning(
                    "Moved " + recovered + " expired item-mutation claims to REVIEW_REQUIRED.");
        }
        if (recovered == recoveryLimit) {
            getLogger().warning(
                    "The bounded startup mutation-recovery batch was full; additional expired "
                            + "claims remain for bounded periodic recovery.");
        }
    }

    private void handleInitializationFailure(Exception exception) {
        publishUnavailableServices(
                "Foundation initialization failed: " + safeMessage(exception));
        getLogger().log(
                java.util.logging.Level.SEVERE,
                "LoreItems foundation initialization failed; writes remain unavailable.",
                exception);
    }

    private void closeQuietly(AutoCloseable component, String name) {
        if (component == null) {
            return;
        }
        try {
            component.close();
        } catch (Exception exception) {
            getLogger().log(
                    java.util.logging.Level.SEVERE,
                    "Could not close the LoreItems " + name + '.',
                    exception);
        }
    }

    private static String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "no detail" : message;
    }
}
