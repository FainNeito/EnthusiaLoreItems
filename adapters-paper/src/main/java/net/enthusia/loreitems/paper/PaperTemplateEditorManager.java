package net.enthusia.loreitems.paper;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.TemplateEditorDraft;
import net.enthusia.loreitems.application.TemplateManagementSnapshot;
import net.enthusia.loreitems.application.TemplateManagementUseCase;
import net.enthusia.loreitems.application.TemplateRevisionRolloutRequest;
import net.enthusia.loreitems.application.TemplateRevisionStartResult;
import net.enthusia.loreitems.application.TemplateRevisionStartStatus;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

/** Bounded GUI/chat draft lifecycle with explicit preview and durable confirmation. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.AvoidLiteralsInIfCondition", "PMD.CyclomaticComplexity", "PMD.NullAssignment"})
public final class PaperTemplateEditorManager implements AutoCloseable {
    public static final String EDIT_PERMISSION = "enthusia.loreitems.admin.edit";

    private static final int MAX_SESSIONS = 32;
    private static final long SESSION_TIMEOUT_TICKS = 20L * 60L * 5L;
    private static final String UNAVAILABLE =
            "Template editing is unavailable while durable storage is read-only or initializing.";

    private final Plugin plugin;
    private final IntSupplier batchLimitSupplier;
    private final Runnable rolloutWake;
    private final long sessionTimeoutTicks;
    private final PaperTemplateEditorRenderer renderer = new PaperTemplateEditorRenderer();
    private final PaperTemplateDraftEditor draftEditor = new PaperTemplateDraftEditor();
    private final PaperItemTemplateCodec templateCodec = new PaperItemTemplateCodec();
    private final Map<UUID, PaperTemplateEditorSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> pendingChatSessions = new ConcurrentHashMap<>();
    private final PaperTemplateManagementLoader managementLoader;
    private final PaperTemplateEditorEvents events;

    private InstanceNavigator instanceNavigator = (playerId, definitionId, page) -> {};
    private DefinitionNavigator definitionNavigator = (playerId, page) -> {};
    private boolean closed;

    public PaperTemplateEditorManager(
            Plugin plugin, IntSupplier batchLimitSupplier, Runnable rolloutWake) {
        this(plugin, batchLimitSupplier, rolloutWake, SESSION_TIMEOUT_TICKS);
    }

    PaperTemplateEditorManager(
            Plugin plugin,
            IntSupplier batchLimitSupplier,
            Runnable rolloutWake,
            long sessionTimeoutTicks) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.batchLimitSupplier = Objects.requireNonNull(batchLimitSupplier, "batchLimitSupplier");
        this.rolloutWake = Objects.requireNonNull(rolloutWake, "rolloutWake");
        if (sessionTimeoutTicks < 1L) {
            throw new IllegalArgumentException("sessionTimeoutTicks must be positive");
        }
        this.sessionTimeoutTicks = sessionTimeoutTicks;
        this.managementLoader = new PaperTemplateManagementLoader(
                plugin, renderer, templateCodec, this::handleFailure, this::runMain);
        this.events = new PaperTemplateEditorEvents(this);
        PaperTemplateEditorSupport.requireBatchLimit(batchLimitSupplier);
        plugin.getServer().getPluginManager().registerEvents(events, plugin);
    }

    void setInstanceNavigator(InstanceNavigator navigator) {
        this.instanceNavigator = Objects.requireNonNull(navigator, "navigator");
    }

    void setDefinitionNavigator(DefinitionNavigator navigator) {
        this.definitionNavigator = Objects.requireNonNull(navigator, "navigator");
    }

    public void openManagement(UUID playerId, LoreDefinitionId definitionId, int returnPage) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(definitionId, "definitionId");
        runMain(() -> openManagementMain(playerId, definitionId, returnPage));
    }

    public void onClick(InventoryClickEvent event) {
        events.onClick(event);
    }

    void onQuit(PlayerQuitEvent event) {
        events.onQuit(event);
    }

    UUID chatSessionId(UUID playerId) {
        return pendingChatSessions.get(Objects.requireNonNull(playerId, "playerId"));
    }

    void handleQuit(UUID playerId) {
        PaperTemplateEditorSession session = sessions.get(playerId);
        if (session != null && session.state == PaperTemplateEditorSession.State.AWAITING_CHAT) {
            pendingChatSessions.remove(playerId);
            resetTimeout(session);
            return;
        }
        session = sessions.remove(playerId);
        if (session != null) {
            pendingChatSessions.remove(session.playerId);
            session.close();
        }
    }

    void cancelOwnDraft(Player player) {
        Objects.requireNonNull(player, "player");
        if (!player.hasPermission(EDIT_PERMISSION)) {
            LoreItemsMessages.send(player, "You do not have permission to edit lore-item templates.");
            return;
        }
        PaperTemplateEditorSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            LoreItemsMessages.send(player, "You do not have an active template draft.");
            return;
        }
        if (session.state == PaperTemplateEditorSession.State.CONFIRMING) {
            LoreItemsMessages.send(player,
                    "Template confirmation is already processing and cannot be cancelled; reopen management to check durable status.");
            return;
        }
        cancelSession(player, session, "Template draft cancelled; no revision was created.", false);
    }

    void closeSessions(String reason) {
        Objects.requireNonNull(reason, "reason");
        runMain(() -> {
            for (PaperTemplateEditorSession session : sessions.values()) {
                Player player = Bukkit.getPlayer(session.playerId);
                if (player != null) {
                    LoreItemsMessages.send(player, "Template draft cancelled: " + reason);
                    org.bukkit.inventory.Inventory topInventory =
                            player.getOpenInventory().getTopInventory();
                    if (topInventory != null
                            && topInventory.getHolder() instanceof PaperTemplateEditorView) {
                        player.closeInventory();
                    }
                }
                session.close();
            }
            sessions.clear();
            pendingChatSessions.clear();
        });
    }

    private void openManagementMain(
            UUID playerId, LoreDefinitionId definitionId, int returnPage) {
        if (closed) {
            message(playerId, UNAVAILABLE);
            return;
        }
        managementLoader.open(playerId, definitionId, returnPage);
    }

    void dispatchClick(Player player, PaperTemplateEditorView view, int slot) {
        if (!LoreItemsAdministrationCommandExecutor.canBrowse(player)) {
            LoreItemsMessages.send(player, "You do not have permission to browse lore-item templates.");
            return;
        }
        switch (view.screen) {
            case MANAGEMENT -> clickManagement(player, view, slot);
            case EDITOR -> clickEditor(player, view, slot);
            case PREVIEW -> clickPreview(player, view, slot);
            default -> throw new IllegalStateException("Unknown template editor screen");
        }
    }

    private void clickManagement(Player player, PaperTemplateEditorView view, int slot) {
        if (slot == PaperTemplateEditorRenderer.MANAGEMENT_BACK) {
            definitionNavigator.open(player.getUniqueId(), view.returnPage);
        } else if (slot == PaperTemplateEditorRenderer.MANAGEMENT_REFRESH) {
            openManagementMain(player.getUniqueId(), view.definitionId(), view.returnPage);
        } else if (slot == PaperTemplateEditorRenderer.MANAGEMENT_INSTANCES) {
            instanceNavigator.open(player.getUniqueId(), view.definitionId(), 1);
        } else if (slot == PaperTemplateEditorRenderer.MANAGEMENT_EDIT) {
            beginEdit(player, view, false);
        } else if (slot == PaperTemplateEditorRenderer.MANAGEMENT_REPLACE) {
            beginEdit(player, view, true);
        }
    }

    private void beginEdit(Player player, PaperTemplateEditorView view, boolean replaceHeld) {
        if (!player.hasPermission(EDIT_PERMISSION)) {
            LoreItemsMessages.send(player, "You do not have permission to edit lore-item templates.");
            return;
        }
        if (sessions.containsKey(player.getUniqueId())) {
            LoreItemsMessages.send(player,
                    "You already have an active template draft. Use /loreitems editor cancel to discard it safely before starting another.");
            return;
        }
        if (sessions.size() >= MAX_SESSIONS) {
            LoreItemsMessages.send(player, "The bounded template-editor session limit is currently full.");
            return;
        }
        try {
            ItemStack before = templateCodec.decode(view.snapshot.currentTemplate());
            ItemStack draft = replaceHeld
                    ? PaperTemplateEditorSupport.normalizedHeld(templateCodec, player)
                    : before.clone();
            PaperTemplateEditorSession session = new PaperTemplateEditorSession(
                    player.getUniqueId(), view.snapshot, before, draft, view.returnPage);
            sessions.put(player.getUniqueId(), session);
            resetTimeout(session);
            if (replaceHeld) {
                session.state = PaperTemplateEditorSession.State.PREVIEW;
                renderer.showPreview(player, session);
            } else {
                renderer.showEditor(player, session);
            }
        } catch (IllegalArgumentException exception) {
            LoreItemsMessages.send(player, exception.getMessage());
        } catch (RuntimeException exception) {
            handleFailure(player.getUniqueId(), "begin template draft", exception);
        }
    }

    private void clickEditor(Player player, PaperTemplateEditorView view, int slot) {
        PaperTemplateEditorSession session = currentSession(player, view);
        if (session == null || session.state == PaperTemplateEditorSession.State.CONFIRMING) {
            return;
        }
        if (slot == PaperTemplateEditorRenderer.EDITOR_CANCEL) {
            cancelSession(player, session, "Draft cancelled; the template was not changed.", true);
            return;
        }
        if (slot == PaperTemplateEditorRenderer.EDITOR_PREVIEW) {
            session.state = PaperTemplateEditorSession.State.PREVIEW;
            resetTimeout(session);
            renderer.showPreview(player, session);
            return;
        }
        PaperTemplateEditorRenderer.ActionSpec action = PaperTemplateEditorRenderer.action(slot);
        if (action != null) {
            startPrompt(player, session, action);
        }
    }

    private void startPrompt(
            Player player,
            PaperTemplateEditorSession session,
            PaperTemplateEditorRenderer.ActionSpec action) {
        session.pendingAction = action.action();
        session.state = PaperTemplateEditorSession.State.AWAITING_CHAT;
        pendingChatSessions.put(player.getUniqueId(), session.sessionId);
        resetTimeout(session);
        player.closeInventory();
        LoreItemsMessages.send(player, "Editing " + action.title() + ": " + String.join(" ", action.help()));
        LoreItemsMessages.send(player, "Run /loreitems set <value> to apply this edit.");
        LoreItemsMessages.send(player, "Run /loreitems set cancel to return without changing the draft.");
    }

    void receiveChat(UUID playerId, String message) {
        UUID sessionId = chatSessionId(playerId);
        if (sessionId != null) {
            receiveChat(playerId, sessionId, message);
        }
    }

    void receiveChat(UUID playerId, UUID sessionId, String message) {
        PaperTemplateEditorSession session = sessions.get(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (!PaperTemplateEditorSupport.isCurrentChatSession(session, player, sessionId)) {
            pendingChatSessions.remove(playerId, sessionId);
            return;
        }
        if (!player.hasPermission(EDIT_PERMISSION)) {
            cancelSession(player, session, "Permission was removed; draft cancelled.", false);
            return;
        }
        processChatMessage(player, session, message.strip());
    }

    private void processChatMessage(
            Player player, PaperTemplateEditorSession session, String message) {
        if (message.equalsIgnoreCase("cancel")) {
            pendingChatSessions.remove(player.getUniqueId());
            session.pendingAction = null;
            session.state = PaperTemplateEditorSession.State.EDITING;
            resetTimeout(session);
            renderer.showEditor(player, session);
            return;
        }
        String input = message.strip();
        if (input.isEmpty()) {
            LoreItemsMessages.send(player, "Nothing was applied. Run /loreitems set <value>, or /loreitems set cancel.");
            return;
        }
        PaperTemplateEditResult result = draftEditor.apply(
                session.draft, session.pendingAction, input);
        if (!result.accepted()) {
            LoreItemsMessages.send(player, "Validation failed: " + result.detail());
            LoreItemsMessages.send(player, "Correct it with /loreitems set <value>, or /loreitems set cancel.");
            return;
        }
        session.draft = result.item();
        session.pendingAction = null;
        session.state = PaperTemplateEditorSession.State.EDITING;
        pendingChatSessions.remove(player.getUniqueId());
        resetTimeout(session);
        LoreItemsMessages.send(player, result.detail());
        renderer.showEditor(player, session);
    }

    private void clickPreview(Player player, PaperTemplateEditorView view, int slot) {
        PaperTemplateEditorSession session = currentSession(player, view);
        if (session == null) {
            return;
        }
        if (slot == PaperTemplateEditorRenderer.PREVIEW_BACK
                && session.state != PaperTemplateEditorSession.State.CONFIRMING) {
            session.state = PaperTemplateEditorSession.State.EDITING;
            resetTimeout(session);
            renderer.showEditor(player, session);
        } else if (slot == PaperTemplateEditorRenderer.PREVIEW_CANCEL
                && session.state != PaperTemplateEditorSession.State.CONFIRMING) {
            cancelSession(player, session, "Draft cancelled; the template was not changed.", true);
        } else if (slot == PaperTemplateEditorRenderer.PREVIEW_CONFIRM) {
            confirm(player, session);
        }
    }

    private void confirm(Player player, PaperTemplateEditorSession session) {
        if (!player.hasPermission(EDIT_PERMISSION)) {
            cancelSession(player, session, "Permission was removed; draft cancelled.", false);
            return;
        }
        if (session.state == PaperTemplateEditorSession.State.CONFIRMING) {
            LoreItemsMessages.send(player, "This draft confirmation is already being processed.");
            return;
        }
        TemplateManagementUseCase useCase = resolveUseCase();
        if (useCase == null) {
            LoreItemsMessages.send(player, UNAVAILABLE);
            return;
        }
        final TemplateRevisionRolloutRequest request;
        try {
            TemplateEditorDraft draft = TemplateEditorDraft.begin(
                    session.confirmationId,
                    session.snapshot.definition().id(),
                    session.snapshot.definition().currentRevision(),
                    templateCodec.encode(session.before),
                    player.getUniqueId()).withTemplate(templateCodec.encode(session.draft));
            request = draft.confirm(PaperTemplateEditorSupport.requireBatchLimit(batchLimitSupplier));
        } catch (IllegalArgumentException exception) {
            LoreItemsMessages.send(player, "Confirmation rejected: " + exception.getMessage());
            return;
        }
        session.state = PaperTemplateEditorSession.State.CONFIRMING;
        resetTimeout(session);
        CompletionStage<TemplateRevisionStartResult> stage;
        try {
            stage = Objects.requireNonNull(useCase.confirm(request), "template confirmation stage");
        } catch (RuntimeException exception) {
            session.state = PaperTemplateEditorSession.State.PREVIEW;
            handleFailure(player.getUniqueId(), "confirm template revision", exception);
            return;
        }
        stage.whenComplete((result, failure) -> runMain(() ->
                finishConfirmation(player.getUniqueId(), session.sessionId, result, failure)));
    }

    private void finishConfirmation(
            UUID playerId,
            UUID sessionId,
            TemplateRevisionStartResult result,
            Throwable failure) {
        PaperTemplateEditorSession session = sessions.get(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (session == null || !session.sessionId.equals(sessionId) || player == null) {
            return;
        }
        if (failure != null) {
            session.state = PaperTemplateEditorSession.State.PREVIEW;
            resetTimeout(session);
            handleFailure(playerId, "confirm template revision", failure);
            return;
        }
        if (result == null) {
            session.state = PaperTemplateEditorSession.State.PREVIEW;
            LoreItemsMessages.send(player, "Confirmation returned no result; no completion was claimed.");
            return;
        }
        if (result.status() == TemplateRevisionStartStatus.STARTED
                || result.status() == TemplateRevisionStartStatus.ALREADY_STARTED) {
            sessions.remove(playerId);
            pendingChatSessions.remove(playerId);
            session.close();
            player.closeInventory();
            LoreItemsMessages.send(player, "Template revision " + result.currentRevision().value()
                    + " is durable; rollout work is queued.");
            rolloutWake.run();
            openManagementMain(playerId, session.snapshot.definition().id(), session.returnPage);
            return;
        }
        sessions.remove(playerId);
        pendingChatSessions.remove(playerId);
        session.close();
        player.closeInventory();
        LoreItemsMessages.send(player, "Template confirmation was not applied: " + result.status());
        openManagementMain(playerId, session.snapshot.definition().id(), session.returnPage);
    }

    private PaperTemplateEditorSession currentSession(
            Player player, PaperTemplateEditorView view) {
        PaperTemplateEditorSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.matches(view.sessionId)) {
            LoreItemsMessages.send(player, "That template-editor screen is stale; no edit was applied.");
            return null;
        }
        resetTimeout(session);
        return session;
    }

    private void cancelSession(
            Player player,
            PaperTemplateEditorSession session,
            String detail,
            boolean reopenManagement) {
        sessions.remove(player.getUniqueId());
        pendingChatSessions.remove(player.getUniqueId());
        session.close();
        player.closeInventory();
        LoreItemsMessages.send(player, detail);
        if (reopenManagement) {
            openManagementMain(
                    player.getUniqueId(),
                    session.snapshot.definition().id(),
                    session.returnPage);
        }
    }

    private void resetTimeout(PaperTemplateEditorSession session) {
        if (session.timeoutTask != null) {
            session.timeoutTask.cancel();
        }
        session.timeoutTask = plugin.getServer().getScheduler().runTaskLater(
                plugin,
                () -> timeout(session.playerId, session.sessionId),
                sessionTimeoutTicks);
    }

    private void timeout(UUID playerId, UUID sessionId) {
        PaperTemplateEditorSession session = sessions.get(playerId);
        if (session == null || !session.sessionId.equals(sessionId)) {
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        boolean confirmationInFlight =
                session.state == PaperTemplateEditorSession.State.CONFIRMING;
        sessions.remove(playerId);
        pendingChatSessions.remove(playerId);
        session.close();
        if (player != null) {
            player.closeInventory();
            LoreItemsMessages.send(player, confirmationInFlight
                    ? "Template confirmation is still processing; reopen management to check durable status."
                    : "Template draft timed out; no revision was created.");
        }
    }

    int activeSessionCount() {
        return sessions.size();
    }

    boolean awaitingChat(UUID playerId) {
        return pendingChatSessions.containsKey(Objects.requireNonNull(playerId, "playerId"));
    }

    PaperTemplateEditorSession.State sessionState(UUID playerId) {
        PaperTemplateEditorSession session =
                sessions.get(Objects.requireNonNull(playerId, "playerId"));
        return session == null ? null : session.state;
    }

    private TemplateManagementUseCase resolveUseCase() {
        return plugin.getServer().getServicesManager().load(TemplateManagementUseCase.class);
    }

    private void handleFailure(UUID playerId, String operation, Throwable throwable) {
        Throwable failure = PaperTemplateEditorSupport.unwrap(throwable);
        plugin.getLogger().log(Level.SEVERE, "Could not " + operation + '.', failure);
        message(playerId, "Could not " + operation + "; no unconfirmed edit was persisted.");
    }

    private void message(UUID playerId, String detail) {
        runMain(() -> {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                LoreItemsMessages.send(player, detail);
            }
        });
    }

    private void runMain(Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, action);
        } catch (IllegalPluginAccessException exception) {
            plugin.getLogger().log(
                    Level.FINE, "Could not schedule template-editor work during shutdown.", exception);
        }
    }

    @Override
    public void close() {
        closed = true;
        closeSessions("plugin shutdown");
    }

    @FunctionalInterface
    interface DefinitionNavigator {
        void open(UUID playerId, int page);
    }

    @FunctionalInterface
    interface InstanceNavigator {
        void open(UUID playerId, LoreDefinitionId definitionId, int page);
    }
}
