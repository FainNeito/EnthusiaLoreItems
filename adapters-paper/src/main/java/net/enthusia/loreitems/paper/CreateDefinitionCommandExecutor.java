package net.enthusia.loreitems.paper;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import net.enthusia.loreitems.application.CreateDefinitionRequest;
import net.enthusia.loreitems.application.CreateDefinitionResult;
import net.enthusia.loreitems.application.CreateDefinitionStatus;
import net.enthusia.loreitems.application.CreateDefinitionUseCase;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import net.enthusia.loreitems.application.ItemCodecException;
import net.enthusia.loreitems.domain.DefinitionKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

public final class CreateDefinitionCommandExecutor implements CommandExecutor {
    public static final String CREATE_PERMISSION = "enthusia.loreitems.admin.create";

    private static final String CREATE_SUBCOMMAND = "create";
    private static final int MINIMUM_CREATE_ARGUMENTS = 3;
    private static final int DISPLAY_NAME_START_INDEX = 2;
    private static final String USAGE =
            "Usage: /loreitems create <lookup-key> <display name>";

    private final Plugin plugin;
    private final CreateDefinitionUseCase useCase;
    private final PaperHeldItemDefinitionSnapshotter snapshotter;

    public CreateDefinitionCommandExecutor(
            Plugin plugin,
            CreateDefinitionUseCase useCase,
            PaperHeldItemDefinitionSnapshotter snapshotter) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.snapshotter = Objects.requireNonNull(snapshotter, "snapshotter");
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
                || !CREATE_SUBCOMMAND.equalsIgnoreCase(arguments[0])) {
            LoreItemsMessages.send(sender, USAGE);
            return true;
        }
        executeCreate(sender, arguments);
        return true;
    }

    private void executeCreate(CommandSender sender, String[] arguments) {
        if (!sender.hasPermission(CREATE_PERMISSION)) {
            LoreItemsMessages.send(sender, "You do not have permission to create lore definitions.");
            return;
        }
        if (!(sender instanceof Player player)) {
            LoreItemsMessages.send(sender, "This command requires a player holding the source item.");
            return;
        }
        if (arguments.length < MINIMUM_CREATE_ARGUMENTS) {
            LoreItemsMessages.send(sender, USAGE);
            return;
        }
        submitCreate(player, arguments);
    }

    private void submitCreate(Player player, String[] arguments) {
        DefinitionKey key = null;
        try {
            key = new DefinitionKey(arguments[1]);
            EncodedItemTemplate template =
                    snapshotter.snapshot(player.getInventory().getItemInMainHand());
            String displayName = String.join(
                    " ", Arrays.copyOfRange(
                            arguments, DISPLAY_NAME_START_INDEX, arguments.length));
            CreateDefinitionRequest request = new CreateDefinitionRequest(
                    key, displayName, template, player.getUniqueId());
            UUID playerId = player.getUniqueId();
            CompletionStage<CreateDefinitionResult> creation = Objects.requireNonNull(
                    useCase.create(request),
                    "definition creation stage");
            creation.whenComplete((result, throwable) ->
                    scheduleResult(playerId, request.key(), result, throwable));
        } catch (IllegalArgumentException | ItemCodecException exception) {
            LoreItemsMessages.send(player, safeMessage(
                    exception,
                    "The definition request was invalid; nothing was created."));
        } catch (RuntimeException exception) {
            DefinitionKey attemptedKey = key;
            plugin.getLogger().log(
                    Level.SEVERE,
                    attemptedKey == null
                            ? "Could not submit lore definition creation."
                            : "Could not submit definition creation for key "
                                    + attemptedKey.value(),
                    exception);
            LoreItemsMessages.send(player, "Lore definition creation could not be submitted.");
        }
    }

    private void scheduleResult(
            UUID playerId,
            DefinitionKey key,
            CreateDefinitionResult result,
            Throwable throwable) {
        try {
            plugin.getServer().getScheduler().runTask(
                    plugin,
                    () -> notifyPlayer(playerId, key, result, throwable));
        } catch (IllegalPluginAccessException exception) {
            plugin.getLogger().log(
                    Level.WARNING,
                    "Could not schedule the definition-creation result during shutdown.",
                    exception);
        }
    }

    private void notifyPlayer(
            UUID playerId,
            DefinitionKey key,
            CreateDefinitionResult result,
            Throwable throwable) {
        Player player = plugin.getServer().getPlayer(playerId);
        if (throwable != null) {
            logFailure(key, throwable);
            if (player != null) {
                LoreItemsMessages.send(player, "Lore definition creation failed; check the server log.");
            }
            return;
        }
        if (result == null) {
            plugin.getLogger().severe(
                    "Definition creation returned no result for key " + key.value());
            if (player != null) {
                LoreItemsMessages.send(player, "Lore definition creation returned no durable result.");
            }
            return;
        }
        if (player != null) {
            sendResult(player, key, result);
        }
    }

    private void logFailure(DefinitionKey key, Throwable throwable) {
        plugin.getLogger().log(
                Level.SEVERE,
                "Definition creation failed for key " + key.value(),
                unwrap(throwable));
    }

    private static void sendResult(
            Player player, DefinitionKey key, CreateDefinitionResult result) {
        CreateDefinitionStatus status = result.status();
        switch (status) {
            case CREATED -> LoreItemsMessages.send(player, 
                    "Created lore definition '" + key.value() + "' from the held item.");
            case ACTIVE_KEY_EXISTS -> LoreItemsMessages.send(player, 
                    "An active lore definition already uses key '" + key.value() + "'.");
            case SERVICE_UNAVAILABLE -> LoreItemsMessages.send(player, 
                    "Lore item storage is not currently available for writes.");
            default -> LoreItemsMessages.send(player, 
                    "Lore definition creation returned an unsupported durable state.");
        }
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
