package net.enthusia.loreitems.paper;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.PendingMutationReviewUseCase;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

/** Privileged, evidence-required operator command for REVIEW_REQUIRED mutations. */
public final class LoreItemsMutationReviewCommandExecutor
        implements CommandExecutor, TabCompleter, AutoCloseable {
    public static final String REVIEW_PERMISSION = "enthusia.loreitems.admin.recovery.review";
    private static final String USAGE =
            "Usage: /loreitemsreview <mutation-uuid> <mutation-type> <retry|cancel> <evidence>";
    private static final int MIN_ARGUMENTS = 4;
    private final Plugin plugin;
    private final Supplier<PendingMutationReviewUseCase> useCaseSupplier;
    private final Runnable retryWake;
    private volatile boolean closed;

    LoreItemsMutationReviewCommandExecutor(
            Plugin plugin,
            Supplier<PendingMutationReviewUseCase> useCaseSupplier,
            Runnable retryWake) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCaseSupplier = Objects.requireNonNull(useCaseSupplier, "useCaseSupplier");
        this.retryWake = Objects.requireNonNull(retryWake, "retryWake");
    }

    @Override
    public boolean onCommand(
            CommandSender sender, Command command, String label, String[] arguments) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(arguments, "arguments");
        if (!DestructiveCommandSupport.requirePermission(sender, REVIEW_PERMISSION)) {
            return true;
        }
        if (arguments.length < MIN_ARGUMENTS) {
            LoreItemsMessages.send(sender, USAGE);
            return true;
        }
        PendingMutationReviewUseCase.Request request;
        try {
            request = new PendingMutationReviewUseCase.Request(
                    DestructiveCommandSupport.parseUuid(arguments[0], "mutation"),
                    arguments[1],
                    parseResolution(arguments[2]),
                    DestructiveCommandSupport.actorId(sender),
                    String.join(" ", Arrays.copyOfRange(arguments, 3, arguments.length)));
        } catch (IllegalArgumentException exception) {
            LoreItemsMessages.send(sender, "Invalid mutation review: " + exception.getMessage());
            return true;
        }
        return submit(sender, request);
    }

    private boolean submit(CommandSender sender, PendingMutationReviewUseCase.Request request) {
        if (closed) {
            LoreItemsMessages.send(sender, "Mutation review is unavailable.");
            return true;
        }
        PendingMutationReviewUseCase useCase = useCaseSupplier.get();
        if (useCase == null) {
            LoreItemsMessages.send(sender, "Durable mutation review is not active.");
            return true;
        }
        CompletionStage<PendingMutationReviewUseCase.Result> stage = resolve(sender, useCase, request);
        if (stage != null) {
            stage.whenComplete((result, throwable) -> receiveResult(sender, result, throwable));
        }
        return true;
    }

    private CompletionStage<PendingMutationReviewUseCase.Result> resolve(
            CommandSender sender,
            PendingMutationReviewUseCase useCase,
            PendingMutationReviewUseCase.Request request) {
        try {
            return Objects.requireNonNull(useCase.resolve(request), "mutation-review stage");
        } catch (RuntimeException exception) {
            reportFailure(sender, exception);
            return null;
        }
    }

    private void receiveResult(
            CommandSender sender,
            PendingMutationReviewUseCase.Result result,
            Throwable throwable) {
        if (closed) {
            return;
        }
        scheduleResult(() -> deliverResult(sender, result, throwable));
    }

    private void deliverResult(
            CommandSender sender,
            PendingMutationReviewUseCase.Result result,
            Throwable throwable) {
        if (closed) {
            return;
        }
        if (throwable != null) {
            reportFailure(sender, throwable);
            return;
        }
        if (result == null) {
            LoreItemsMessages.send(sender, "Mutation review returned no result.");
            return;
        }
        LoreItemsMessages.send(sender, result.detail());
        if (result.status() == PendingMutationReviewUseCase.Status.RETRIED) {
            retryWake.run();
        }
    }

    private void scheduleResult(Runnable task) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule mutation-review output during shutdown.",
                    exception);
        }
    }

    private void reportFailure(CommandSender sender, Throwable throwable) {
        Throwable cause = DestructiveCommandSupport.unwrap(throwable);
        plugin.getLogger().log(Level.SEVERE, "Mutation-review command failed.", cause);
        LoreItemsMessages.send(sender, "Mutation review failed: " + cause.getClass().getSimpleName()
                + DestructiveCommandSupport.safeDetail(cause));
    }

    static PendingMutationReviewUseCase.Resolution parseResolution(String input) {
        return switch (Objects.requireNonNull(input, "input").toLowerCase(Locale.ROOT)) {
            case "retry" -> PendingMutationReviewUseCase.Resolution.RETRY;
            case "cancel" -> PendingMutationReviewUseCase.Resolution.CANCEL;
            default -> throw new IllegalArgumentException("resolution must be retry or cancel");
        };
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender, Command command, String alias, String[] arguments) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(arguments, "arguments");
        if (!sender.hasPermission(REVIEW_PERMISSION) || arguments.length != 3) {
            return List.of();
        }
        String prefix = arguments[2].toLowerCase(Locale.ROOT);
        return List.of("retry", "cancel").stream()
                .filter(value -> value.startsWith(prefix))
                .toList();
    }

    @Override
    public void close() {
        closed = true;
    }
}
