package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import net.enthusia.loreitems.application.AnomalyWarningSink;
import net.enthusia.loreitems.application.ItemAnomalyObservationUseCase;
import net.enthusia.loreitems.application.LoreItemsAdministrationUseCase;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.application.TrackingMetrics;
import net.enthusia.loreitems.application.TrackingMetricsSource;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredListener;
import org.bukkit.scheduler.BukkitTask;

public final class PaperAnomalyWarningWorker
        implements AnomalyWarningSink, TrackingMetricsSource, AutoCloseable {
    private static final long TICKS_PER_SECOND = 20L;
    private static final int MIN_POSITIVE_VALUE = 1;
    private static final int DEFAULT_TRACKING_BUDGET_PER_TICK = 16;

    private final Plugin plugin;
    private final LoreItemsAdministrationUseCase useCase;
    private final int pageSize;
    private final int trackingBudgetPerTick;
    private final long intervalTicks;
    private final Object queryLock = new Object();
    private final TrackingMetrics trackingMetricsState = new TrackingMetrics();

    private Optional<BukkitTask> task = Optional.empty();
    private StartedTracking startedTracking = StartedTracking.empty();
    private boolean queryInFlight;
    private boolean rerunRequested;
    private volatile boolean closed;

    public PaperAnomalyWarningWorker(
            Plugin plugin,
            LoreItemsAdministrationUseCase useCase,
            int intervalSeconds,
            int pageSize) {
        this(
                plugin,
                useCase,
                intervalSeconds,
                pageSize,
                DEFAULT_TRACKING_BUDGET_PER_TICK);
    }

    public PaperAnomalyWarningWorker(
            Plugin plugin,
            LoreItemsAdministrationUseCase useCase,
            int intervalSeconds,
            int pageSize,
            int trackingBudgetPerTick) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        if (intervalSeconds < MIN_POSITIVE_VALUE) {
            throw new IllegalArgumentException("intervalSeconds must be positive");
        }
        if (pageSize < MIN_POSITIVE_VALUE || pageSize > PageRequest.MAX_LIMIT) {
            throw new IllegalArgumentException("pageSize is outside supported bounds");
        }
        if (trackingBudgetPerTick < MIN_POSITIVE_VALUE) {
            throw new IllegalArgumentException("trackingBudgetPerTick must be positive");
        }
        this.pageSize = pageSize;
        this.trackingBudgetPerTick = trackingBudgetPerTick;
        this.intervalTicks = Math.multiplyExact(intervalSeconds, TICKS_PER_SECOND);
    }

    public void start() {
        BukkitTask warningTask = scheduleWarningTask();
        StartedTracking started = StartedTracking.empty();
        try {
            started = startTrackingListeners();
            publishTracking(started);
        } catch (RuntimeException exception) {
            started.close();
            rollbackWarningTask(warningTask);
            throw exception;
        }
        requestWarning();
    }

    private BukkitTask scheduleWarningTask() {
        synchronized (queryLock) {
            if (closed) {
                throw new IllegalStateException("Anomaly warning worker is closed");
            }
            if (task.isPresent()) {
                throw new IllegalStateException("Anomaly warning worker is already started");
            }
            BukkitTask warningTask = plugin.getServer().getScheduler().runTaskTimer(
                    plugin,
                    this::requestWarning,
                    intervalTicks,
                    intervalTicks);
            task = Optional.of(warningTask);
            return warningTask;
        }
    }

    private StartedTracking startTrackingListeners() {
        if (findTrackingUseCase().isEmpty()) {
            plugin.getLogger().warning(
                    "Physical lore-item tracking is unavailable; anomaly warnings remain active.");
            return StartedTracking.empty();
        }
        closeExistingTrackingListeners();
        PaperUniqueAccessTrackingListener unique = new PaperUniqueAccessTrackingListener(
                plugin,
                this::trackingUseCase,
                () -> trackingBudgetPerTick,
                trackingMetricsState.additionalQueueView());
        PaperPhysicalTrackingListener physical = new PaperPhysicalTrackingListener(
                plugin,
                this::trackingUseCase,
                () -> trackingBudgetPerTick,
                trackingMetricsState);
        StartedTracking started = StartedTracking.of(unique, physical);
        try {
            unique.start();
            physical.start();
            return started;
        } catch (RuntimeException exception) {
            started.close();
            throw exception;
        }
    }

    private void closeExistingTrackingListeners() {
        for (RegisteredListener registration : HandlerList.getRegisteredListeners(plugin)) {
            Listener listener = registration.getListener();
            if (listener instanceof PaperPhysicalTrackingListener physical) {
                physical.close();
            } else if (listener instanceof PaperUniqueAccessTrackingListener unique) {
                unique.close();
            }
        }
    }

    private void publishTracking(StartedTracking started) {
        synchronized (queryLock) {
            if (closed) {
                throw new IllegalStateException("Anomaly warning worker is closed");
            }
            startedTracking = started;
        }
    }

    private void rollbackWarningTask(BukkitTask warningTask) {
        warningTask.cancel();
        synchronized (queryLock) {
            if (task.isPresent() && task.orElseThrow() == warningTask) {
                task = Optional.empty();
            }
        }
    }

    private Optional<TrackingObservationUseCase> findTrackingUseCase() {
        ItemAnomalyObservationUseCase service = plugin.getServer()
                .getServicesManager()
                .load(ItemAnomalyObservationUseCase.class);
        return service instanceof TrackingObservationUseCase tracking
                ? Optional.of(tracking)
                : Optional.empty();
    }

    private TrackingObservationUseCase trackingUseCase() {
        return findTrackingUseCase().orElseThrow(() -> new IllegalStateException(
                "Registered item-observation service does not support physical tracking"));
    }

    @Override
    public TrackingMetrics.Snapshot trackingMetrics() {
        return trackingMetricsState.snapshot();
    }

    @Override
    public void requestWarning() {
        synchronized (queryLock) {
            if (closed) {
                return;
            }
            if (queryInFlight) {
                rerunRequested = true;
                return;
            }
            queryInFlight = true;
        }
        startQuery();
    }

    private void startQuery() {
        CompletionStage<Page<InstanceAnomaly>> query;
        try {
            query = Objects.requireNonNull(
                    useCase.listWarningAnomalies(PageRequest.first(pageSize)),
                    "warning anomaly query returned null");
        } catch (RuntimeException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not start the lore-item warning query.",
                    exception);
            finishQuery();
            return;
        }
        query.whenComplete((page, failure) -> {
            if (failure != null) {
                plugin.getLogger().log(
                        Level.SEVERE,
                        "Could not query active lore-item warning anomalies.",
                        unwrap(failure));
            } else if (!closed && page != null && !page.items().isEmpty()) {
                scheduleWarning(page);
            } else if (page == null) {
                plugin.getLogger().severe(
                        "The lore-item warning query completed without a result.");
            }
            finishQuery();
        });
    }

    private void finishQuery() {
        boolean runAgain;
        synchronized (queryLock) {
            queryInFlight = false;
            runAgain = !closed && rerunRequested;
            rerunRequested = false;
            if (runAgain) {
                queryInFlight = true;
            }
        }
        if (runAgain) {
            startQuery();
        }
    }

    private void scheduleWarning(Page<InstanceAnomaly> page) {
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> sendWarning(page));
        } catch (IllegalPluginAccessException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule lore-item anomaly warnings during shutdown.",
                    exception);
        }
    }

    private void sendWarning(Page<InstanceAnomaly> page) {
        if (closed) {
            return;
        }
        long duplicateCount = page.items().stream()
                .filter(anomaly -> anomaly.type() == InstanceAnomaly.Type.DUPLICATE_INSTANCE)
                .count();
        long malformedCount = page.items().stream()
                .filter(anomaly -> anomaly.type() == InstanceAnomaly.Type.MALFORMED_STACK)
                .count();
        String count = page.hasMore()
                ? "at least " + page.items().size()
                : Integer.toString(page.items().size());
        String message = "[LoreItems] " + count
                + " unresolved identity anomalies require review (duplicates="
                + duplicateCount + ", malformed=" + malformedCount
                + "). Use /loreitems anomalies.";
        plugin.getServer().getConsoleSender().sendMessage(message);
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.hasPermission(LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION)) {
                LoreItemsMessages.send(player, message);
            }
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException exception && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }

    @Override
    public void close() {
        Optional<BukkitTask> currentTask;
        StartedTracking currentTracking;
        synchronized (queryLock) {
            closed = true;
            rerunRequested = false;
            currentTask = task;
            task = Optional.empty();
            currentTracking = startedTracking;
            startedTracking = StartedTracking.empty();
        }
        currentTask.ifPresent(BukkitTask::cancel);
        currentTracking.close();
    }

    private record StartedTracking(
            Optional<PaperUniqueAccessTrackingListener> unique,
            Optional<PaperPhysicalTrackingListener> physical) {
        private static StartedTracking empty() {
            return new StartedTracking(Optional.empty(), Optional.empty());
        }

        private static StartedTracking of(
                PaperUniqueAccessTrackingListener unique,
                PaperPhysicalTrackingListener physical) {
            return new StartedTracking(Optional.of(unique), Optional.of(physical));
        }

        private void close() {
            physical.ifPresent(PaperPhysicalTrackingListener::close);
            unique.ifPresent(PaperUniqueAccessTrackingListener::close);
        }
    }
}
