package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.AdoptHeldItemUseCase;
import net.enthusia.loreitems.application.ItemCodecException;
import net.enthusia.loreitems.application.PrepareHeldItemAdoptionRequest;
import net.enthusia.loreitems.application.PrepareHeldItemAdoptionResult;
import net.enthusia.loreitems.application.PreparedHeldItemAdoption;
import net.enthusia.loreitems.domain.DefinitionKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

public final class AdoptHeldItemCommandExecutor implements CommandExecutor {
    public static final String ADOPT_PERMISSION = "enthusia.loreitems.admin.adopt";

    private static final String ADOPT_SUBCOMMAND = "adopt";
    private static final String USAGE = "Usage: /loreitems adopt <lookup-key>";
    private static final int REQUIRED_ARGUMENT_COUNT = 2;
    private static final int MAXIMUM_CONCURRENT_ADOPTIONS = 256;

    private final Plugin plugin;
    private final Supplier<AdoptHeldItemUseCase> useCaseSupplier;
    private final PaperHeldItemAdoptionOperator operator;
    private final Set<UUID> activePlayers = ConcurrentHashMap.newKeySet();
    private final Semaphore capacity = new Semaphore(MAXIMUM_CONCURRENT_ADOPTIONS);

    public AdoptHeldItemCommandExecutor(
            Plugin plugin,
            Supplier<AdoptHeldItemUseCase> useCaseSupplier,
            PaperHeldItemAdoptionOperator operator) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCaseSupplier = Objects.requireNonNull(useCaseSupplier, "useCaseSupplier");
        this.operator = Objects.requireNonNull(operator, "operator");
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] arguments) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(arguments, "arguments");
        if (arguments.length == 0
                || !ADOPT_SUBCOMMAND.equalsIgnoreCase(arguments[0])) {
            LoreItemsMessages.send(sender, USAGE);
            return true;
        }
        executeAdopt(sender, arguments);
        return true;
    }

    private void executeAdopt(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission(ADOPT_PERMISSION)) {
            LoreItemsMessages.send(sender, "You do not have permission to adopt lore items.");
            return;
        }
        if (!(sender instanceof Player player)) {
            LoreItemsMessages.send(sender, "This command requires a player holding the item to adopt.");
            return;
        }
        if (arguments.length != REQUIRED_ARGUMENT_COUNT) {
            LoreItemsMessages.send(sender, USAGE);
            return;
        }
        submitAdoption(player, arguments[1]);
    }

    private void submitAdoption(Player player, String keyValue) {
        UUID playerId = player.getUniqueId();
        if (!capacity.tryAcquire()) {
            LoreItemsMessages.send(player, "Too many lore-item mutations are already active; try again later.");
            return;
        }
        if (!activePlayers.add(playerId)) {
            capacity.release();
            LoreItemsMessages.send(player, "Your previous lore-item adoption is still being resolved.");
            return;
        }
        try {
            DefinitionKey key = new DefinitionKey(keyValue);
            PrepareHeldItemAdoptionRequest request = operator.snapshot(player, key);
            AdoptHeldItemUseCase useCase = Objects.requireNonNull(
                    useCaseSupplier.get(), "active adoption use case");
            CompletionStage<PrepareHeldItemAdoptionResult> preparation = Objects.requireNonNull(
                    useCase.prepare(request), "adoption preparation stage");
            preparation.whenComplete((result, throwable) ->
                    handlePreparation(useCase, request, result, throwable));
        } catch (IllegalArgumentException | ItemCodecException exception) {
            release(playerId);
            LoreItemsMessages.send(player, safeMessage(
                    exception,
                    "The adoption request was invalid; the held item was not changed."));
        } catch (RuntimeException exception) {
            release(playerId);
            plugin.getLogger().log(Level.SEVERE, "Could not start held-item adoption.", exception);
            LoreItemsMessages.send(player, "Held-item adoption failed before durable preparation.");
        }
    }

    private void handlePreparation(
            AdoptHeldItemUseCase useCase,
            PrepareHeldItemAdoptionRequest request,
            PrepareHeldItemAdoptionResult result,
            Throwable throwable) {
        if (releaseIfLifecycleChanged(useCase, request.playerId())) {
            return;
        }
        if (throwable != null) {
            release(request.playerId());
            logFailure("Held-item adoption preparation failed.", throwable);
            notifyPlayer(request.playerId(),
                    "Held-item adoption failed before the item was changed.");
            return;
        }
        if (result == null) {
            release(request.playerId());
            plugin.getLogger().severe("Held-item adoption preparation returned no result.");
            notifyPlayer(request.playerId(),
                    "Held-item adoption failed before the item was changed.");
            return;
        }
        switch (result.status()) {
            case UNKNOWN_DEFINITION -> {
                release(request.playerId());
                notifyPlayer(request.playerId(),
                        "No active lore definition uses key '"
                                + request.definitionKey().value() + "'.");
            }
            case SERVICE_UNAVAILABLE -> {
                release(request.playerId());
                notifyPlayer(request.playerId(),
                        "Lore item storage is not currently available for writes.");
            }
            case PREPARED -> scheduleApplication(
                    useCase,
                    Objects.requireNonNull(result.preparedAdoption(), "preparedAdoption"));
            default -> {
                release(request.playerId());
                plugin.getLogger().severe(
                        "Held-item adoption returned unsupported preparation status: "
                                + result.status());
                notifyPlayer(request.playerId(),
                        "Held-item adoption returned an unsupported durable state.");
            }
        }
    }

    private void scheduleApplication(
            AdoptHeldItemUseCase useCase,
            PreparedHeldItemAdoption adoption) {
        try {
            plugin.getServer().getScheduler().runTask(
                    plugin,
                    () -> applyPreparedAdoption(useCase, adoption));
        } catch (RuntimeException exception) {
            requireReview(
                    useCase,
                    adoption,
                    "The server stopped before the prepared adoption could access the held slot.",
                    exception);
        }
    }

    private void applyPreparedAdoption(
            AdoptHeldItemUseCase useCase,
            PreparedHeldItemAdoption adoption) {
        if (releaseIfLifecycleChanged(useCase, adoption.playerId())) {
            return;
        }
        Player player = plugin.getServer().getPlayer(adoption.playerId());
        if (player == null) {
            requireReview(
                    useCase,
                    adoption,
                    "The player became unavailable before the prepared slot mutation.",
                    null);
            return;
        }
        PaperHeldItemAdoptionOperator.ApplyResult result = operator.apply(player, adoption);
        if (result.status()
                == PaperHeldItemAdoptionOperator.ApplyResult.Status.REVIEW_REQUIRED) {
            requireReview(useCase, adoption, result.detail(), null);
            return;
        }
        completeAdoption(
                useCase,
                adoption,
                Objects.requireNonNull(result.afterFingerprint(), "afterFingerprint"));
    }

    private void completeAdoption(
            AdoptHeldItemUseCase useCase,
            PreparedHeldItemAdoption adoption,
            String afterFingerprint) {
        if (releaseIfLifecycleChanged(useCase, adoption.playerId())) {
            return;
        }
        CompletionStage<Boolean> completion;
        try {
            completion = Objects.requireNonNull(
                    useCase.complete(adoption, afterFingerprint),
                    "adoption completion stage");
        } catch (RuntimeException exception) {
            requireReview(
                    useCase,
                    adoption,
                    "Durable completion could not be submitted after exact-slot verification.",
                    exception);
            return;
        }
        completion.whenComplete((completed, throwable) -> {
            if (releaseIfLifecycleChanged(useCase, adoption.playerId())) {
                return;
            }
            if (throwable != null) {
                requireReview(
                        useCase,
                        adoption,
                        "Durable completion failed after the exact slot was mutated and verified.",
                        throwable);
                return;
            }
            if (!Boolean.TRUE.equals(completed)) {
                requireReview(
                        useCase,
                        adoption,
                        "The durable adoption claim was unavailable after exact-slot verification.",
                        null);
                return;
            }
            release(adoption.playerId());
            notifyPlayer(adoption.playerId(),
                    "Adopted the held item into lore definition '"
                            + adoption.definitionKey().value() + "'.");
        });
    }

    private void requireReview(
            AdoptHeldItemUseCase useCase,
            PreparedHeldItemAdoption adoption,
            String reason,
            Throwable precedingFailure) {
        if (releaseIfLifecycleChanged(useCase, adoption.playerId())) {
            return;
        }
        if (precedingFailure != null) {
            logFailure("Held-item adoption entered review after an operational failure.",
                    precedingFailure);
        }
        try {
            CompletionStage<Boolean> review = Objects.requireNonNull(
                    useCase.requireReview(adoption, reason),
                    "adoption review stage");
            review.whenComplete((reviewed, throwable) -> {
                release(adoption.playerId());
                if (releaseIfLifecycleChanged(useCase, adoption.playerId())) {
                    return;
                }
                if (throwable != null) {
                    logFailure("Could not persist held-item adoption review state.", throwable);
                    notifyPlayer(adoption.playerId(),
                            "The adoption outcome is ambiguous; staff must inspect the server log.");
                    return;
                }
                if (!Boolean.TRUE.equals(reviewed)) {
                    plugin.getLogger().severe(
                            "Held-item adoption could not be completed or moved to review for "
                                    + "mutation " + adoption.mutationId() + '.');
                    notifyPlayer(adoption.playerId(),
                            "The adoption outcome is ambiguous; staff must inspect the server log.");
                    return;
                }
                notifyPlayer(adoption.playerId(),
                        "The adoption could not be safely confirmed and requires staff review.");
            });
        } catch (RuntimeException exception) {
            release(adoption.playerId());
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not submit held-item adoption review persistence.",
                    exception);
        }
    }

    private boolean releaseIfLifecycleChanged(
            AdoptHeldItemUseCase expectedUseCase,
            UUID playerId) {
        AdoptHeldItemUseCase currentUseCase;
        try {
            currentUseCase = useCaseSupplier.get();
        } catch (RuntimeException exception) {
            release(playerId);
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not verify the active held-item adoption lifecycle; "
                            + "the stale callback was discarded.",
                    exception);
            return true;
        }
        if (currentUseCase == expectedUseCase) {
            return false;
        }
        release(playerId);
        plugin.getLogger().fine(
                "Discarded a held-item adoption callback from a replaced runtime; "
                        + "durable recovery owns any prepared mutation.");
        return true;
    }

    private void notifyPlayer(UUID playerId, String message) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null) {
                    LoreItemsMessages.send(player, message);
                }
            });
        } catch (IllegalPluginAccessException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule held-item adoption notification during shutdown.",
                    exception);
        }
    }

    private void release(UUID playerId) {
        if (activePlayers.remove(playerId)) {
            capacity.release();
        }
    }

    private void logFailure(String message, Throwable throwable) {
        plugin.getLogger().log(Level.SEVERE, message, unwrap(throwable));
    }

    private static String safeMessage(RuntimeException exception, String fallback) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? fallback : message;
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException exception && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }
}
