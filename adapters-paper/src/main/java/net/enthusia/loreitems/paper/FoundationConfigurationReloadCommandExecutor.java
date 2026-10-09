package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.AtomicConfiguration;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/** Privileged command bridge for the plugin-owned atomic configuration reload path. */
public final class FoundationConfigurationReloadCommandExecutor {
    public static final String RELOAD_PERMISSION = "enthusia.loreitems.admin.reload";

    private final Plugin plugin;
    private final Supplier<CompletionStage<AtomicConfiguration.ReloadResult>> reloadAction;
    private final AtomicBoolean inFlight = new AtomicBoolean();

    public FoundationConfigurationReloadCommandExecutor(
            Plugin plugin,
            Supplier<CompletionStage<AtomicConfiguration.ReloadResult>> reloadAction) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.reloadAction = Objects.requireNonNull(reloadAction, "reloadAction");
    }

    public boolean execute(CommandSender sender) {
        Objects.requireNonNull(sender, "sender");
        if (!sender.hasPermission(RELOAD_PERMISSION)) {
            LoreItemsMessages.send(sender, "You do not have permission to reload LoreItems configuration.");
            return true;
        }
        if (!inFlight.compareAndSet(false, true)) {
            LoreItemsMessages.send(sender, "A LoreItems configuration reload is already running.");
            return true;
        }

        CompletionStage<AtomicConfiguration.ReloadResult> stage;
        try {
            stage = Objects.requireNonNull(reloadAction.get(), "configuration reload stage");
        } catch (RuntimeException exception) {
            inFlight.set(false);
            reportFailure(sender, exception);
            return true;
        }

        stage.whenComplete((result, throwable) -> {
            inFlight.set(false);
            scheduleResult(() -> deliverResult(sender, result, throwable));
        });
        LoreItemsMessages.send(sender, "LoreItems configuration reload started.");
        return true;
    }

    private void deliverResult(
            CommandSender sender,
            AtomicConfiguration.ReloadResult result,
            Throwable throwable) {
        if (throwable != null) {
            reportFailure(sender, throwable);
            return;
        }
        if (result == null) {
            LoreItemsMessages.send(sender, "LoreItems configuration reload failed safely: no result was returned.");
            return;
        }
        String prefix = result.applied()
                ? "LoreItems configuration reload applied: "
                : "LoreItems configuration reload rejected: ";
        LoreItemsMessages.send(sender, prefix + result.detail());
    }

    private void scheduleResult(Runnable task) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule a configuration reload command result during shutdown.",
                    exception);
        }
    }

    private void reportFailure(CommandSender sender, Throwable throwable) {
        Throwable cause = unwrap(throwable);
        plugin.getLogger().log(Level.SEVERE, "LoreItems configuration reload command failed.", cause);
        String message = cause.getMessage();
        LoreItemsMessages.send(sender, "LoreItems configuration reload failed safely: "
                + cause.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message));
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof java.util.concurrent.CompletionException completion
                && completion.getCause() != null) {
            return completion.getCause();
        }
        return throwable;
    }
}
