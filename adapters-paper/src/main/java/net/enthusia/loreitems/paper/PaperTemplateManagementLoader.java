package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import net.enthusia.loreitems.application.TemplateManagementSnapshot;
import net.enthusia.loreitems.application.TemplateManagementUseCase;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Bounded asynchronous loading for the read-only template-management screen. */
final class PaperTemplateManagementLoader {
    private static final int MAX_QUERIES = 32;
    private static final int MINIMUM_QUERY_CAPACITY = 1;
    private static final long QUERY_TIMEOUT_SECONDS = 10L;
    private static final long MINIMUM_QUERY_TIMEOUT = 1L;
    private static final String UNAVAILABLE =
            "Template editing is unavailable while durable storage is read-only or initializing.";

    private final Plugin plugin;
    private final PaperTemplateEditorRenderer renderer;
    private final PaperItemTemplateCodec templateCodec;
    private final FailureHandler failureHandler;
    private final MainThreadExecutor mainThreadExecutor;
    private final Semaphore queryCapacity;
    private final long queryTimeout;
    private final TimeUnit queryTimeoutUnit;

    PaperTemplateManagementLoader(
            Plugin plugin,
            PaperTemplateEditorRenderer renderer,
            PaperItemTemplateCodec templateCodec,
            FailureHandler failureHandler,
            MainThreadExecutor mainThreadExecutor) {
        this(
                plugin,
                renderer,
                templateCodec,
                failureHandler,
                mainThreadExecutor,
                MAX_QUERIES,
                QUERY_TIMEOUT_SECONDS,
                TimeUnit.SECONDS);
    }

    PaperTemplateManagementLoader(
            Plugin plugin,
            PaperTemplateEditorRenderer renderer,
            PaperItemTemplateCodec templateCodec,
            FailureHandler failureHandler,
            MainThreadExecutor mainThreadExecutor,
            int maximumQueries,
            long queryTimeout,
            TimeUnit queryTimeoutUnit) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.templateCodec = Objects.requireNonNull(templateCodec, "templateCodec");
        this.failureHandler = Objects.requireNonNull(failureHandler, "failureHandler");
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
        if (maximumQueries < MINIMUM_QUERY_CAPACITY) {
            throw new IllegalArgumentException("maximumQueries must be positive");
        }
        if (queryTimeout < MINIMUM_QUERY_TIMEOUT) {
            throw new IllegalArgumentException("queryTimeout must be positive");
        }
        this.queryCapacity = new Semaphore(maximumQueries);
        this.queryTimeout = queryTimeout;
        this.queryTimeoutUnit = Objects.requireNonNull(queryTimeoutUnit, "queryTimeoutUnit");
    }

    void open(UUID playerId, LoreDefinitionId definitionId, int returnPage) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !LoreItemsAdministrationCommandExecutor.canBrowse(player)) {
            return;
        }
        TemplateManagementUseCase useCase = resolveUseCase();
        if (useCase == null) {
            LoreItemsMessages.send(player, UNAVAILABLE);
            return;
        }
        if (!queryCapacity.tryAcquire()) {
            LoreItemsMessages.send(player, "Too many template-management queries are active.");
            return;
        }
        loadSnapshot(playerId, definitionId, returnPage, useCase);
    }

    private void loadSnapshot(
            UUID playerId,
            LoreDefinitionId definitionId,
            int returnPage,
            TemplateManagementUseCase useCase) {
        CompletionStage<Optional<TemplateManagementSnapshot>> stage;
        try {
            stage = Objects.requireNonNull(
                    useCase.findSnapshot(definitionId), "template snapshot stage");
        } catch (RuntimeException exception) {
            queryCapacity.release();
            failureHandler.handle(playerId, "load template management", exception);
            return;
        }
        stage.toCompletableFuture()
                .copy()
                .orTimeout(queryTimeout, queryTimeoutUnit)
                .whenComplete((snapshot, failure) -> {
                    queryCapacity.release();
                    if (failure != null) {
                        failureHandler.handle(playerId, "load template management", failure);
                        return;
                    }
                    mainThreadExecutor.execute(() -> show(playerId, snapshot, returnPage));
                });
    }

    private void show(
            UUID playerId,
            Optional<TemplateManagementSnapshot> snapshot,
            int returnPage) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !LoreItemsAdministrationCommandExecutor.canBrowse(player)) {
            return;
        }
        if (snapshot == null || snapshot.isEmpty()) {
            LoreItemsMessages.send(player, "That lore definition is no longer active.");
            return;
        }
        try {
            TemplateManagementSnapshot value = snapshot.orElseThrow();
            renderer.showManagement(
                    player,
                    value,
                    templateCodec.decode(value.currentTemplate()),
                    returnPage);
        } catch (RuntimeException exception) {
            failureHandler.handle(playerId, "decode template preview", exception);
        }
    }

    private TemplateManagementUseCase resolveUseCase() {
        return plugin.getServer().getServicesManager().load(TemplateManagementUseCase.class);
    }

    @FunctionalInterface
    interface FailureHandler {
        void handle(UUID playerId, String operation, Throwable throwable);
    }

    @FunctionalInterface
    interface MainThreadExecutor {
        void execute(Runnable action);
    }
}
