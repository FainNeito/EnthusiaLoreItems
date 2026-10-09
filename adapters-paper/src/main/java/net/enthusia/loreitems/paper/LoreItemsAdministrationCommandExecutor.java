package net.enthusia.loreitems.paper;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.AuditEventRecord;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase;
import net.enthusia.loreitems.application.DistributionCampaignAdministrationUseCase;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.paper.LoreItemsAdministrationFormatter.AuditView;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

public final class LoreItemsAdministrationCommandExecutor implements CommandExecutor, AutoCloseable {
    public static final String AUDIT_PERMISSION = "enthusia.loreitems.admin.audit";

    private static final String BROWSE_SUBCOMMAND = "browse";
    private static final String ANOMALIES_SUBCOMMAND = "anomalies";
    private static final String AUDIT_SUBCOMMAND = "audit";
    private static final String RECOVERY_SUBCOMMAND = "recovery";
    private static final String CONFIGURATION_RELOAD_REASON = "configuration reload";
    private static final String USAGE = "Usage: /loreitems browse | "
            + "/loreitems anomalies [page] | "
            + "/loreitems audit <instance-uuid> [page] | /loreitems recovery [page]";
    private static final int MAX_CONCURRENT_QUERIES = 32;
    private static final int MIN_PAGE_NUMBER = 1;
    private static final int MIN_AUDIT_ARGUMENTS = 2;
    private static final int MAX_AUDIT_ARGUMENTS = 3;
    private static final int AUDIT_PAGE_ARGUMENT_INDEX = 2;

    private final Plugin plugin;
    private final IntSupplier pageSizeSupplier;
    private final PaperTrackingAdministrationGui trackingGui;
    private final PaperTemplateEditorManager templateEditor;
    private final LoreItemsDestructiveCommandExecutor destructiveExecutor;
    private final Set<CommandActor> activeActors = ConcurrentHashMap.newKeySet();
    private final Semaphore queryCapacity = new Semaphore(MAX_CONCURRENT_QUERIES);

    public LoreItemsAdministrationCommandExecutor(Plugin plugin, int pageSize) {
        this(plugin, () -> pageSize, () -> pageSize, () -> {});
    }

    public LoreItemsAdministrationCommandExecutor(
            Plugin plugin,
            IntSupplier pageSizeSupplier) {
        this(plugin, pageSizeSupplier, pageSizeSupplier, () -> {});
    }

    public LoreItemsAdministrationCommandExecutor(
            Plugin plugin,
            IntSupplier pageSizeSupplier,
            IntSupplier rolloutBatchSupplier,
            Runnable rolloutWake) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.pageSizeSupplier = Objects.requireNonNull(pageSizeSupplier, "pageSizeSupplier");
        validatePageSize(currentPageSize());
        this.destructiveExecutor = new LoreItemsDestructiveCommandExecutor(
                plugin,
                () -> plugin.getServer()
                        .getServicesManager()
                        .load(DestructiveAdministrationUseCase.class),
                pageSizeSupplier,
                () -> wakeDestructiveWork(plugin));
        this.templateEditor = new PaperTemplateEditorManager(
                plugin,
                Objects.requireNonNull(rolloutBatchSupplier, "rolloutBatchSupplier"),
                Objects.requireNonNull(rolloutWake, "rolloutWake"));
        this.trackingGui = new PaperTrackingAdministrationGui(
                plugin, pageSizeSupplier, templateEditor);
        this.templateEditor.setDefinitionNavigator(trackingGui::openDefinitions);
        this.templateEditor.setInstanceNavigator(trackingGui::openInstances);
    }

    private static void wakeDestructiveWork(Plugin plugin) {
        PaperMutationRecoveryWorker worker = plugin.getServer()
                .getServicesManager()
                .load(PaperMutationRecoveryWorker.class);
        if (worker != null) {
            worker.wakeAccessible();
        }
    }

    LoreItemsDestructiveCommandExecutor destructiveCommandExecutor() {
        return destructiveExecutor;
    }

    boolean executeEditorCommand(CommandSender sender, String[] arguments) {
        return LoreItemsEditorCommandSupport.execute(sender, arguments, templateEditor);
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] arguments) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(command, "command for /" + label);
        Objects.requireNonNull(arguments, "arguments");
        String subcommand = parseSubcommand(sender, arguments);
        if (subcommand == null) {
            return true;
        }
        if (BROWSE_SUBCOMMAND.equals(subcommand)) {
            if (!canBrowse(sender)) {
                LoreItemsMessages.send(sender, "You do not have permission to browse lore-item templates.");
                return true;
            }
            executeBrowse(sender, arguments);
            return true;
        }
        LoreItemsAdministrationUseCase useCase = resolveEvidenceUseCase(sender);
        if (useCase == null) {
            return true;
        }
        CommandActor actor = CommandActor.capture(sender);
        if (!beginQuery(actor)) {
            LoreItemsMessages.send(sender, "A previous lore-item evidence query is still active; try again shortly.");
            return true;
        }
        dispatchQuery(subcommand, actor, useCase, arguments);
        return true;
    }

    private void executeBrowse(CommandSender sender, String[] arguments) {
        if (arguments.length != MIN_PAGE_NUMBER) {
            LoreItemsMessages.send(sender, USAGE);
            return;
        }
        if (!(sender instanceof Player player)) {
            LoreItemsMessages.send(sender, "The lore-item browser requires an in-game player.");
            return;
        }
        trackingGui.openDefinitions(player.getUniqueId(), MIN_PAGE_NUMBER);
    }

    static boolean canBrowse(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        return sender.hasPermission(AUDIT_PERMISSION)
                || sender.hasPermission(PaperTemplateEditorManager.EDIT_PERMISSION);
    }

    private LoreItemsAdministrationUseCase resolveEvidenceUseCase(CommandSender sender) {
        if (!sender.hasPermission(AUDIT_PERMISSION)) {
            LoreItemsMessages.send(sender, "You do not have permission to inspect lore-item evidence.");
            return null;
        }
        LoreItemsAdministrationUseCase useCase = plugin.getServer()
                .getServicesManager()
                .load(LoreItemsAdministrationUseCase.class);
        if (useCase == null) {
            LoreItemsMessages.send(sender, "Lore-item administration is unavailable while storage initializes.");
        }
        return useCase;
    }

    private static String parseSubcommand(CommandSender sender, String[] arguments) {
        if (arguments.length == 0) {
            LoreItemsMessages.send(sender, USAGE);
            return null;
        }
        String subcommand = arguments[0].toLowerCase(Locale.ROOT);
        if (!isSupportedSubcommand(subcommand)) {
            LoreItemsMessages.send(sender, USAGE);
            return null;
        }
        return subcommand;
    }

    private static boolean isSupportedSubcommand(String subcommand) {
        return switch (subcommand) {
            case BROWSE_SUBCOMMAND, ANOMALIES_SUBCOMMAND, AUDIT_SUBCOMMAND,
                    RECOVERY_SUBCOMMAND -> true;
            default -> false;
        };
    }

    private void dispatchQuery(
            String subcommand,
            CommandActor actor,
            LoreItemsAdministrationUseCase useCase,
            String[] arguments) {
        switch (subcommand) {
            case ANOMALIES_SUBCOMMAND -> executeAnomalies(actor, useCase, arguments);
            case AUDIT_SUBCOMMAND -> executeAudit(actor, useCase, arguments);
            case RECOVERY_SUBCOMMAND -> executeRecovery(actor, useCase, arguments);
            default -> {
                finishQuery(actor);
                throw new IllegalStateException("Validated administration subcommand was lost");
            }
        }
    }

    private void executeAnomalies(
            CommandActor actor,
            LoreItemsAdministrationUseCase useCase,
            String[] arguments) {
        PageRequest request = parsePage(actor, arguments, MIN_PAGE_NUMBER);
        if (request == null) {
            finishQuery(actor);
            return;
        }
        CompletionStage<Page<InstanceAnomaly>> stage;
        try {
            stage = Objects.requireNonNull(
                    useCase.listActiveAnomalies(request),
                    "active anomaly query stage");
        } catch (RuntimeException exception) {
            finishQuery(actor);
            handleFailure(actor, "active anomalies", exception);
            return;
        }
        stage.whenComplete((page, failure) -> {
            finishQuery(actor);
            if (failure != null) {
                handleFailure(actor, "active anomalies", failure);
                return;
            }
            if (page == null) {
                handleFailure(
                        actor,
                        "active anomalies",
                        new IllegalStateException("Active anomaly query returned no page"));
                return;
            }
            notifyActor(actor, LoreItemsAdministrationFormatter.anomalyLines(page));
        });
    }

    private void executeAudit(
            CommandActor actor,
            LoreItemsAdministrationUseCase useCase,
            String[] arguments) {
        AuditRequest request = parseAuditRequest(actor, arguments);
        if (request == null) {
            finishQuery(actor);
            return;
        }
        CompletionStage<AuditView> auditView = submitAuditView(actor, useCase, request);
        if (auditView != null) {
            auditView.whenComplete((view, failure) ->
                    completeAuditQuery(actor, request.instanceId(), view, failure));
        }
    }

    private AuditRequest parseAuditRequest(CommandActor actor, String[] arguments) {
        if (arguments.length < MIN_AUDIT_ARGUMENTS
                || arguments.length > MAX_AUDIT_ARGUMENTS) {
            notifyActor(actor, List.of(USAGE));
            return null;
        }
        LoreInstanceId instanceId;
        try {
            instanceId = new LoreInstanceId(UUID.fromString(arguments[1]));
        } catch (IllegalArgumentException exception) {
            notifyActor(actor, List.of("The instance ID must be a valid UUID."));
            return null;
        }
        PageRequest page = parsePage(actor, arguments, AUDIT_PAGE_ARGUMENT_INDEX);
        return page == null ? null : new AuditRequest(instanceId, page);
    }

    private CompletionStage<AuditView> submitAuditView(
            CommandActor actor,
            LoreItemsAdministrationUseCase useCase,
            AuditRequest request) {
        try {
            CompletionStage<Optional<InstanceCurrentState>> currentState = Objects.requireNonNull(
                    useCase.findCurrentState(request.instanceId()),
                    "current-state query stage");
            CompletionStage<Page<InstanceObservation>> observations = Objects.requireNonNull(
                    useCase.listInstanceObservations(request.instanceId(), request.page()),
                    "observation query stage");
            CompletionStage<Page<InstanceAnomaly>> anomalies = Objects.requireNonNull(
                    useCase.listInstanceAnomalies(request.instanceId(), request.page()),
                    "instance anomaly query stage");
            CompletionStage<Page<AuditEventRecord>> audit = Objects.requireNonNull(
                    useCase.listInstanceAudit(request.instanceId(), request.page()),
                    "audit-event query stage");
            return combineAuditView(currentState, observations, anomalies, audit);
        } catch (RuntimeException exception) {
            finishQuery(actor);
            handleFailure(actor, "instance audit", exception);
            return null;
        }
    }

    private static CompletionStage<AuditView> combineAuditView(
            CompletionStage<Optional<InstanceCurrentState>> currentState,
            CompletionStage<Page<InstanceObservation>> observations,
            CompletionStage<Page<InstanceAnomaly>> anomalies,
            CompletionStage<Page<AuditEventRecord>> audit) {
        CompletionStage<StateEvidence> stateEvidence = currentState.thenCombine(
                observations,
                StateEvidence::new);
        CompletionStage<HistoryEvidence> historyEvidence = anomalies.thenCombine(
                audit,
                HistoryEvidence::new);
        return stateEvidence.thenCombine(
                historyEvidence,
                (state, history) -> new AuditView(
                        state.currentState(),
                        state.observations(),
                        history.anomalies(),
                        history.audit()));
    }

    private void completeAuditQuery(
            CommandActor actor,
            LoreInstanceId instanceId,
            AuditView view,
            Throwable failure) {
        finishQuery(actor);
        if (failure != null) {
            handleFailure(actor, "instance audit", failure);
            return;
        }
        if (view == null) {
            handleFailure(
                    actor,
                    "instance audit",
                    new IllegalStateException("Instance audit query returned no view"));
            return;
        }
        notifyActor(actor, LoreItemsAdministrationFormatter.auditLines(instanceId, view));
    }

    private void executeRecovery(
            CommandActor actor,
            LoreItemsAdministrationUseCase useCase,
            String[] arguments) {
        PageRequest request = parsePage(actor, arguments, MIN_PAGE_NUMBER);
        if (request == null) {
            finishQuery(actor);
            return;
        }
        CompletionStage<RecoveryView> stage;
        try {
            stage = submitRecoveryView(useCase, request);
        } catch (RuntimeException exception) {
            finishQuery(actor);
            handleFailure(actor, "recovery work", exception);
            return;
        }
        stage.whenComplete((view, failure) -> {
            finishQuery(actor);
            if (failure != null) {
                handleFailure(actor, "recovery work", failure);
                return;
            }
            if (view == null) {
                handleFailure(
                        actor,
                        "recovery work",
                        new IllegalStateException("Recovery query returned no view"));
                return;
            }
            notifyActor(
                    actor,
                    LoreItemsAdministrationFormatter.recoveryLines(
                            view.recovery(),
                            view.campaignReviews(),
                            view.distributionAvailable()));
        });
    }

    private CompletionStage<RecoveryView> submitRecoveryView(
            LoreItemsAdministrationUseCase useCase, PageRequest request) {
        CompletionStage<LoreItemsAdministrationUseCase.RecoveryPage> recovery =
                Objects.requireNonNull(useCase.listRecovery(request), "recovery query stage");
        DistributionCampaignAdministrationUseCase distribution = plugin.getServer()
                .getServicesManager()
                .load(DistributionCampaignAdministrationUseCase.class);
        boolean distributionAvailable = distribution != null;
        CompletionStage<Page<CampaignRecipient>> campaignReviews = !distributionAvailable
                ? CompletableFuture.completedFuture(emptyCampaignPage(request))
                : Objects.requireNonNull(
                        distribution.listReviewRequired(request),
                        "campaign review query stage");
        return recovery.thenCombine(
                campaignReviews,
                (recoveryPage, reviews) -> new RecoveryView(
                        recoveryPage, reviews, distributionAvailable));
    }

    private static Page<CampaignRecipient> emptyCampaignPage(PageRequest request) {
        return new Page<>(List.of(), request.offset(), request.limit(), false);
    }

    private PageRequest parsePage(
            CommandActor actor,
            String[] arguments,
            int pageArgumentIndex) {
        if (arguments.length > pageArgumentIndex + MIN_PAGE_NUMBER) {
            notifyActor(actor, List.of(USAGE));
            return null;
        }
        int pageNumber = MIN_PAGE_NUMBER;
        if (arguments.length > pageArgumentIndex) {
            try {
                pageNumber = Integer.parseInt(arguments[pageArgumentIndex]);
            } catch (NumberFormatException exception) {
                notifyActor(actor, List.of("Page must be a positive whole number."));
                return null;
            }
        }
        if (pageNumber < MIN_PAGE_NUMBER) {
            notifyActor(actor, List.of("Page must be a positive whole number."));
            return null;
        }
        int pageSize = currentPageSize();
        try {
            int offset = Math.multiplyExact(pageNumber - MIN_PAGE_NUMBER, pageSize);
            return new PageRequest(offset, pageSize);
        } catch (ArithmeticException exception) {
            notifyActor(actor, List.of("That page number is too large."));
            return null;
        }
    }

    private boolean beginQuery(CommandActor actor) {
        if (!queryCapacity.tryAcquire()) {
            return false;
        }
        if (!activeActors.add(actor)) {
            queryCapacity.release();
            return false;
        }
        return true;
    }

    private void finishQuery(CommandActor actor) {
        if (activeActors.remove(actor)) {
            queryCapacity.release();
        }
    }

    private int currentPageSize() {
        int pageSize = pageSizeSupplier.getAsInt();
        validatePageSize(pageSize);
        return pageSize;
    }

    private static void validatePageSize(int pageSize) {
        if (pageSize < MIN_PAGE_NUMBER || pageSize > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("pageSize is outside supported bounds");
        }
    }

    private void handleFailure(CommandActor actor, String operation, Throwable failure) {
        plugin.getLogger().log(
                Level.SEVERE,
                "Could not query lore-item " + operation + '.',
                unwrap(failure));
        notifyActor(actor, List.of("The lore-item " + operation + " query failed."));
    }

    private void notifyActor(CommandActor actor, List<String> lines) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                CommandSender sender = actor.playerId() == null
                        ? plugin.getServer().getConsoleSender()
                        : plugin.getServer().getPlayer(actor.playerId());
                if (sender != null) {
                    lines.forEach(line -> LoreItemsMessages.send(sender, line));
                }
            });
        } catch (IllegalPluginAccessException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule lore-item administration output during shutdown.",
                    exception);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException exception && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }

    private record CommandActor(UUID playerId) {
        private static CommandActor capture(CommandSender sender) {
            return sender instanceof Player player
                    ? new CommandActor(player.getUniqueId())
                    : new CommandActor(null);
        }
    }

    private record AuditRequest(LoreInstanceId instanceId, PageRequest page) {}

    private record StateEvidence(
            Optional<InstanceCurrentState> currentState,
            Page<InstanceObservation> observations) {}

    private record RecoveryView(
            LoreItemsAdministrationUseCase.RecoveryPage recovery,
            Page<CampaignRecipient> campaignReviews,
            boolean distributionAvailable) {}

    public void closeEditorSessions(String reason) {
        Objects.requireNonNull(reason, "reason");
        if (CONFIGURATION_RELOAD_REASON.equals(reason)) {
            // The only supported hot reload is a live-read policy toggle. Reload attempts must not
            // discard unrelated operator drafts or confirmations, especially when validation fails.
            return;
        }
        destructiveExecutor.clearConfirmations();
        templateEditor.closeSessions(reason);
    }

    @Override
    public void close() {
        destructiveExecutor.close();
        templateEditor.close();
    }

    private record HistoryEvidence(
            Page<InstanceAnomaly> anomalies,
            Page<AuditEventRecord> audit) {}
}
