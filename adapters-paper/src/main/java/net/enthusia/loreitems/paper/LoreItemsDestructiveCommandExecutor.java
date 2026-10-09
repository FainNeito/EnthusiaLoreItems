package net.enthusia.loreitems.paper;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.DestructiveAdministrationUseCase;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.DestructiveOperationType;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

/** Privileged command surface for durable destructive administration. */
@SuppressWarnings({"PMD.AvoidLiteralsInIfCondition"})
public final class LoreItemsDestructiveCommandExecutor implements AutoCloseable, TabCompleter {
    public static final String REMOVE_PERMISSION = "enthusia.loreitems.admin.remove";
    public static final String PURGE_PERMISSION = "enthusia.loreitems.admin.purge";
    public static final String DELETE_PERMISSION = "enthusia.loreitems.admin.delete";
    public static final String INSPECT_PERMISSION = "enthusia.loreitems.admin.destructive.inspect";
    public static final String CONTROL_PERMISSION = "enthusia.loreitems.admin.destructive.control";
    public static final String REVIEW_PERMISSION = "enthusia.loreitems.admin.destructive.review";

    private static final String REMOVE = "remove";
    private static final String PURGE = "purge";
    private static final String DELETE = "delete";
    private static final String CONFIRM_REMOVE = "confirm-remove";
    private static final String CONFIRM_PURGE = "confirm-purge";
    private static final String CONFIRM_DELETE = "confirm-delete";
    private static final String OPERATIONS = "operations";
    private static final String TARGETS = "targets";
    private static final String METRICS_ROUTE = "destructive-metrics";
    private static final String PAUSE = "pause-operation";
    private static final String RESUME = "resume-operation";
    private static final String RESOLVE_REMOVAL = "resolve-removal";
    private static final int MAX_IN_FLIGHT = 32;
    private static final int MAX_CONFIRMATIONS = 256;
    private static final Duration CONFIRMATION_TTL = Duration.ofMinutes(5L);

    private final Plugin plugin;
    private final Supplier<DestructiveAdministrationUseCase> useCaseSupplier;
    private final IntSupplier pageSizeSupplier;
    private final Runnable wakeDestructiveWork;
    private final DestructiveConfirmationRegistry confirmations;
    private final Semaphore capacity = new Semaphore(MAX_IN_FLIGHT);
    private final java.util.Set<String> activeActors = ConcurrentHashMap.newKeySet();
    private final Map<String, CommandHandler> handlers;
    private volatile boolean closed;

    public LoreItemsDestructiveCommandExecutor(
            Plugin plugin,
            Supplier<DestructiveAdministrationUseCase> useCaseSupplier,
            IntSupplier pageSizeSupplier,
            Runnable wakeDestructiveWork) {
        this(
                plugin,
                useCaseSupplier,
                pageSizeSupplier,
                wakeDestructiveWork,
                Clock.systemUTC());
    }

    LoreItemsDestructiveCommandExecutor(
            Plugin plugin,
            Supplier<DestructiveAdministrationUseCase> useCaseSupplier,
            IntSupplier pageSizeSupplier,
            Runnable wakeDestructiveWork,
            Clock clock) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCaseSupplier = Objects.requireNonNull(useCaseSupplier, "useCaseSupplier");
        this.pageSizeSupplier = Objects.requireNonNull(pageSizeSupplier, "pageSizeSupplier");
        this.wakeDestructiveWork = Objects.requireNonNull(
                wakeDestructiveWork, "wakeDestructiveWork");
        confirmations = new DestructiveConfirmationRegistry(
                Objects.requireNonNull(clock, "clock"), CONFIRMATION_TTL, MAX_CONFIRMATIONS);
        handlers = createHandlers();
    }

    private Map<String, CommandHandler> createHandlers() {
        return Map.ofEntries(
                Map.entry(REMOVE, this::previewExactRemoval),
                Map.entry(
                        PURGE,
                        (sender, arguments) -> previewDefinition(
                                sender, arguments, DestructiveOperationType.PURGE_DEFINITION)),
                Map.entry(
                        DELETE,
                        (sender, arguments) -> previewDefinition(
                                sender, arguments, DestructiveOperationType.DELETE_DEFINITION)),
                Map.entry(
                        CONFIRM_REMOVE,
                        (sender, arguments) -> confirm(
                                sender,
                                arguments,
                                DestructiveOperationType.EXACT_INSTANCE_REMOVAL)),
                Map.entry(
                        CONFIRM_PURGE,
                        (sender, arguments) -> confirm(
                                sender, arguments, DestructiveOperationType.PURGE_DEFINITION)),
                Map.entry(
                        CONFIRM_DELETE,
                        (sender, arguments) -> confirm(
                                sender, arguments, DestructiveOperationType.DELETE_DEFINITION)),
                Map.entry(OPERATIONS, this::listOperations),
                Map.entry(TARGETS, this::listTargets),
                Map.entry(METRICS_ROUTE, this::metrics),
                Map.entry(PAUSE, (sender, arguments) -> control(sender, arguments, true)),
                Map.entry(RESUME, (sender, arguments) -> control(sender, arguments, false)),
                Map.entry(RESOLVE_REMOVAL, this::review));
    }

    public boolean handles(String subcommand) {
        String normalized = Objects.requireNonNull(subcommand, "subcommand")
                .toLowerCase(Locale.ROOT);
        return handlers.containsKey(normalized);
    }

    public boolean onCommand(
            CommandSender sender, Command command, String label, String[] arguments) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(arguments, "arguments");
        if (closed || arguments.length == 0) {
            LoreItemsMessages.send(sender, "Destructive administration is unavailable.");
            return true;
        }
        CommandHandler handler = handlers.get(arguments[0].toLowerCase(Locale.ROOT));
        if (handler == null) {
            return false;
        }
        try {
            return handler.execute(sender, arguments);
        } catch (IllegalArgumentException exception) {
            LoreItemsMessages.send(sender, "Invalid destructive command: " + exception.getMessage());
            return true;
        }
    }

    private boolean previewExactRemoval(CommandSender sender, String[] arguments) {
        if (!DestructiveCommandSupport.requirePermission(sender, REMOVE_PERMISSION)
                || arguments.length != 3) {
            LoreItemsMessages.send(sender,
                    "Usage: /loreitems remove <definition-uuid> <instance-uuid>");
            return true;
        }
        LoreDefinitionId definitionId = new LoreDefinitionId(
                DestructiveCommandSupport.parseUuid(arguments[1], "definition"));
        LoreInstanceId instanceId = new LoreInstanceId(
                DestructiveCommandSupport.parseUuid(arguments[2], "instance"));
        return submit(
                sender,
                () -> useCase().preview(new DestructiveAdministrationUseCase.PreviewRequest(
                        DestructiveOperationType.EXACT_INSTANCE_REMOVAL,
                        definitionId,
                        instanceId)),
                preview -> showPreview(sender, preview));
    }

    private boolean previewDefinition(
            CommandSender sender,
            String[] arguments,
            DestructiveOperationType operationType) {
        String permission = DestructiveCommandSupport.permissionFor(operationType);
        String route = operationType == DestructiveOperationType.PURGE_DEFINITION ? PURGE : DELETE;
        if (!DestructiveCommandSupport.requirePermission(sender, permission)
                || arguments.length != 2) {
            LoreItemsMessages.send(sender, "Usage: /loreitems " + route + " <definition-uuid>");
            return true;
        }
        LoreDefinitionId definitionId = new LoreDefinitionId(
                DestructiveCommandSupport.parseUuid(arguments[1], "definition"));
        return submit(
                sender,
                () -> useCase().preview(new DestructiveAdministrationUseCase.PreviewRequest(
                        operationType, definitionId, null)),
                preview -> showPreview(sender, preview));
    }

    private void showPreview(
            CommandSender sender, Optional<DestructiveAdministrationUseCase.Preview> result) {
        if (result.isEmpty()) {
            LoreItemsMessages.send(sender, "No matching active lore definition or instance was found.");
            return;
        }
        DestructiveAdministrationUseCase.Preview preview = result.orElseThrow();
        confirmations.remember(DestructiveCommandSupport.actorId(sender), preview);
        LoreItemsMessages.send(sender, "Destructive preview for " + preview.displayName()
                + " [" + preview.lookupKey().value() + "] at revision "
                + preview.expectedRevision().value() + ':');
        LoreItemsMessages.send(sender, "targets=" + preview.targetCount()
                + ", inaccessible=" + preview.inaccessibleCount()
                + ", queued=" + preview.queuedCount()
                + ", anomalies=" + preview.anomalyCount());
        LoreItemsMessages.send(sender, effectWarning(preview.operationType()));
        LoreItemsMessages.send(sender, "This snapshot is fixed for five minutes. Confirm with: /loreitems "
                + DestructiveCommandSupport.confirmationRoute(preview.operationType()) + ' '
                + preview.confirmationToken());
    }

    private static String effectWarning(DestructiveOperationType operationType) {
        return switch (operationType) {
            case EXACT_INSTANCE_REMOVAL ->
                    "Irreversible effect: physically remove exactly this tracked instance.";
            case PURGE_DEFINITION ->
                    "Irreversible effect: physically remove every known or returning instance; "
                            + "the definition remains active.";
            case DELETE_DEFINITION ->
                    "Irreversible effect: delete the definition and physically remove every known "
                            + "or returning instance; copies are not merely untracked.";
        };
    }

    private boolean confirm(
            CommandSender sender,
            String[] arguments,
            DestructiveOperationType operationType) {
        if (!DestructiveCommandSupport.requirePermission(
                        sender, DestructiveCommandSupport.permissionFor(operationType))
                || arguments.length != 2) {
            LoreItemsMessages.send(sender, "Usage: /loreitems "
                    + DestructiveCommandSupport.confirmationRoute(operationType)
                    + " <confirmation-token>");
            return true;
        }
        Optional<DestructiveConfirmationRegistry.Session> session = confirmations.consume(
                DestructiveCommandSupport.actorId(sender), operationType, arguments[1]);
        if (session.isEmpty()) {
            LoreItemsMessages.send(sender,
                    "No matching unexpired confirmation exists. Run the destructive preview again.");
            return true;
        }
        DestructiveConfirmationRegistry.Session confirmed = session.orElseThrow();
        return submit(
                sender,
                () -> useCase().start(new DestructiveAdministrationUseCase.StartRequest(
                        confirmed.preview(), confirmed.actorId(), confirmed.idempotencyKey())),
                result -> showStartResult(sender, result));
    }

    private void showStartResult(
            CommandSender sender, DestructiveAdministrationUseCase.StartResult result) {
        LoreItemsMessages.send(sender, result.detail());
        if (result.operation() == null) {
            return;
        }
        LoreItemsMessages.send(sender, DestructiveCommandSupport.formatOperation(result.operation()));
        wakeDestructiveWork.run();
    }

    private boolean listOperations(CommandSender sender, String[] arguments) {
        if (!DestructiveCommandSupport.requirePermission(sender, INSPECT_PERMISSION)
                || arguments.length > 2) {
            LoreItemsMessages.send(sender, "Usage: /loreitems operations [page]");
            return true;
        }
        PageRequest page = DestructiveCommandSupport.pageRequest(
                arguments, 1, pageSizeSupplier.getAsInt());
        return submit(
                sender,
                () -> useCase().listOperations(page),
                result -> DestructiveCommandSupport.showOperations(sender, result));
    }

    private boolean listTargets(CommandSender sender, String[] arguments) {
        if (!DestructiveCommandSupport.requirePermission(sender, INSPECT_PERMISSION)
                || arguments.length < 2
                || arguments.length > 3) {
            LoreItemsMessages.send(sender, "Usage: /loreitems targets <operation-uuid> [page]");
            return true;
        }
        UUID operationId = DestructiveCommandSupport.parseUuid(arguments[1], "operation");
        PageRequest page = DestructiveCommandSupport.pageRequest(
                arguments, 2, pageSizeSupplier.getAsInt());
        return submit(
                sender,
                () -> useCase().listTargets(operationId, page),
                result -> DestructiveCommandSupport.showTargets(sender, operationId, result));
    }

    private boolean metrics(CommandSender sender, String[] arguments) {
        if (!DestructiveCommandSupport.requirePermission(sender, INSPECT_PERMISSION)
                || arguments.length != 1) {
            LoreItemsMessages.send(sender, "Usage: /loreitems destructive-metrics");
            return true;
        }
        return submit(
                sender,
                () -> useCase().metrics(),
                result -> DestructiveCommandSupport.showMetrics(sender, result));
    }

    private boolean control(CommandSender sender, String[] arguments, boolean pause) {
        if (!DestructiveCommandSupport.requirePermission(sender, CONTROL_PERMISSION)
                || arguments.length != 2) {
            LoreItemsMessages.send(sender, "Usage: /loreitems " + (pause ? PAUSE : RESUME)
                    + " <operation-uuid>");
            return true;
        }
        DestructiveAdministrationUseCase.ControlRequest request =
                new DestructiveAdministrationUseCase.ControlRequest(
                        DestructiveCommandSupport.parseUuid(arguments[1], "operation"),
                        DestructiveCommandSupport.actorId(sender));
        return submit(
                sender,
                () -> pause ? useCase().pause(request) : useCase().resume(request),
                result -> showControlResult(sender, result, pause));
    }

    private void showControlResult(
            CommandSender sender,
            DestructiveAdministrationUseCase.ControlResult result,
            boolean pause) {
        LoreItemsMessages.send(sender, result.detail());
        if (result.operation() == null) {
            return;
        }
        LoreItemsMessages.send(sender, DestructiveCommandSupport.formatOperation(result.operation()));
        if (!pause) {
            wakeDestructiveWork.run();
        }
    }

    private boolean review(CommandSender sender, String[] arguments) {
        if (!DestructiveCommandSupport.requirePermission(sender, REVIEW_PERMISSION)
                || arguments.length < 5) {
            LoreItemsMessages.send(sender, "Usage: /loreitems resolve-removal <operation-uuid> "
                    + "<instance-uuid> <requeue|removed|abort> <evidence>");
            return true;
        }
        DestructiveAdministrationUseCase.ReviewResolution resolution =
                DestructiveCommandSupport.parseResolution(arguments[3]);
        DestructiveAdministrationUseCase.ReviewRequest request =
                new DestructiveAdministrationUseCase.ReviewRequest(
                        DestructiveCommandSupport.parseUuid(arguments[1], "operation"),
                        new LoreInstanceId(
                                DestructiveCommandSupport.parseUuid(arguments[2], "instance")),
                        resolution,
                        DestructiveCommandSupport.actorId(sender),
                        String.join(" ", Arrays.copyOfRange(arguments, 4, arguments.length)));
        return submit(
                sender,
                () -> useCase().resolveReview(request),
                result -> showReviewResult(sender, result, resolution));
    }

    private void showReviewResult(
            CommandSender sender,
            DestructiveAdministrationUseCase.ReviewResult result,
            DestructiveAdministrationUseCase.ReviewResolution resolution) {
        LoreItemsMessages.send(sender, result.detail());
        if (result.target() == null) {
            return;
        }
        LoreItemsMessages.send(sender, DestructiveCommandSupport.formatTarget(result.target()));
        if (resolution
                == DestructiveAdministrationUseCase.ReviewResolution.REQUEUE_NO_SIDE_EFFECT) {
            wakeDestructiveWork.run();
        }
    }

    private <T> boolean submit(
            CommandSender sender,
            Supplier<CompletionStage<T>> task,
            Consumer<T> success) {
        String actor = DestructiveCommandSupport.actorId(sender);
        if (closed) {
            LoreItemsMessages.send(sender, "Destructive administration is unavailable.");
            return true;
        }
        if (!activeActors.add(actor)) {
            LoreItemsMessages.send(sender, "Your previous destructive administration request is still running.");
            return true;
        }
        if (!capacity.tryAcquire()) {
            activeActors.remove(actor);
            LoreItemsMessages.send(sender, "Destructive administration is busy; retry shortly.");
            return true;
        }
        CompletionStage<T> stage;
        try {
            stage = Objects.requireNonNull(task.get(), "destructive command stage");
        } catch (RuntimeException exception) {
            release(actor);
            reportFailure(sender, exception);
            return true;
        }
        stage.whenComplete((result, throwable) -> {
            release(actor);
            scheduleResult(() -> deliverResult(sender, result, throwable, success));
        });
        return true;
    }

    private <T> void deliverResult(
            CommandSender sender, T result, Throwable throwable, Consumer<T> success) {
        if (closed) {
            return;
        }
        if (throwable != null) {
            reportFailure(sender, throwable);
        } else if (result == null) {
            LoreItemsMessages.send(sender, "Destructive administration returned no result.");
        } else {
            success.accept(result);
        }
    }

    private DestructiveAdministrationUseCase useCase() {
        DestructiveAdministrationUseCase useCase = useCaseSupplier.get();
        if (useCase == null) {
            throw new IllegalStateException("Durable destructive administration is not active");
        }
        return useCase;
    }

    private void scheduleResult(Runnable task) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule a destructive command result during shutdown.",
                    exception);
        }
    }

    private void release(String actor) {
        activeActors.remove(actor);
        capacity.release();
    }

    private void reportFailure(CommandSender sender, Throwable throwable) {
        Throwable cause = DestructiveCommandSupport.unwrap(throwable);
        plugin.getLogger().log(Level.SEVERE, "Destructive administration command failed.", cause);
        LoreItemsMessages.send(sender, "Destructive administration failed: "
                + cause.getClass().getSimpleName()
                + DestructiveCommandSupport.safeDetail(cause));
    }

    static List<String> topLevelCompletions(CommandSender sender, String input) {
        return DestructiveCommandSupport.topLevelCompletions(sender, input);
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] arguments) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(arguments, "arguments");
        if (arguments.length == 1) {
            return topLevelCompletions(sender, arguments[0]);
        }
        if (arguments.length == 4
                && RESOLVE_REMOVAL.equalsIgnoreCase(arguments[0])
                && sender.hasPermission(REVIEW_PERMISSION)) {
            return List.of("requeue", "removed", "abort").stream()
                    .filter(value -> value.startsWith(arguments[3].toLowerCase(Locale.ROOT)))
                    .toList();
        }
        return List.of();
    }

    void clearConfirmations() {
        confirmations.clear();
    }

    @Override
    public void close() {
        closed = true;
        confirmations.clear();
    }

    @FunctionalInterface
    private interface CommandHandler {
        boolean execute(CommandSender sender, String[] arguments);
    }
}
