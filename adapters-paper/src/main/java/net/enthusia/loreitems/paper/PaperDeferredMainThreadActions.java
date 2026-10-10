package net.enthusia.loreitems.paper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.logging.Level;
import org.bukkit.plugin.Plugin;

/**
 * Owns one-tick Bukkit continuations until they either execute or are drained during listener close.
 *
 * <p>The queue is main-thread confined: event intake, Bukkit task execution, and plugin disable all
 * run on the server thread. Keeping accepted actions here prevents Bukkit task cancellation during
 * disable from silently discarding work before the tracking coordinator's persistence barrier sees
 * it.
 */
final class PaperDeferredMainThreadActions implements AutoCloseable {
    private final Plugin plugin;
    private final String rejectionMessage;
    private final Queue<PendingAction> pending = new ArrayDeque<>();
    private boolean closing;
    private boolean closed;

    PaperDeferredMainThreadActions(Plugin plugin, String rejectionMessage) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.rejectionMessage = Objects.requireNonNull(rejectionMessage, "rejectionMessage");
    }

    /** True only if this action was actually accepted by the Bukkit scheduler. */
    boolean schedule(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (closing || closed) {
            return false;
        }
        PendingAction pendingAction = new PendingAction(action);
        pending.add(pendingAction);
        try {
            plugin.getServer().getScheduler().runTask(
                    plugin,
                    () -> execute(pendingAction));
            return true;
        } catch (RuntimeException exception) {
            pending.remove(pendingAction);
            plugin.getLogger().log(Level.FINE, rejectionMessage, exception);
            return false;
        }
    }

    private void execute(PendingAction pendingAction) {
        if (!pending.remove(pendingAction) || closing || closed) {
            return;
        }
        pendingAction.action().run();
    }

    @Override
    public void close() {
        if (closing || closed) {
            return;
        }
        closing = true;
        List<PendingAction> accepted = new ArrayList<>(pending);
        pending.clear();
        for (PendingAction pendingAction : accepted) {
            try {
                pendingAction.action().run();
            } catch (RuntimeException exception) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Could not drain accepted lore-item tracking work during shutdown.",
                        exception);
            }
        }
        closed = true;
    }

    private record PendingAction(Runnable action) {
        private PendingAction {
            Objects.requireNonNull(action, "action");
        }
    }
}
