package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.TemplateManagementSnapshot;
import net.enthusia.loreitems.application.TemplateManagementUseCase;
import net.enthusia.loreitems.application.TemplateRevisionRolloutBatchResult;
import net.enthusia.loreitems.application.TemplateRevisionRolloutRequest;
import net.enthusia.loreitems.application.TemplateRevisionStartResult;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.permissions.PermissionAttachment;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

@SuppressWarnings({"PMD.AvoidFieldNameMatchingMethodName"})
class PaperTemplateEditorManagerTest {
    private static final TemplateRevision REVISION_ONE = new TemplateRevision(1);
    private static final TemplateRevision REVISION_TWO = new TemplateRevision(2);

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock player;
    private PermissionAttachment auditPermission;
    private PermissionAttachment editPermission;
    private RecordingUseCase useCase;
    private AtomicInteger rolloutWakes;
    private PaperTemplateEditorManager manager;
    private TemplateManagementSnapshot snapshot;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = server.addPlayer();
        auditPermission = player.addAttachment(plugin);
        auditPermission.setPermission(
                LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION, true);
        editPermission = player.addAttachment(plugin);
        editPermission.setPermission(PaperTemplateEditorManager.EDIT_PERMISSION, true);
        snapshot = snapshot(ItemStack.of(Material.PAPER));
        useCase = new RecordingUseCase(snapshot);
        server.getServicesManager().register(
                TemplateManagementUseCase.class,
                useCase,
                plugin,
                ServicePriority.Normal);
        rolloutWakes = new AtomicInteger();
        manager = new PaperTemplateEditorManager(plugin, () -> 8, rolloutWakes::incrementAndGet, 3L);
    }

    @AfterEach
    void tearDown() {
        manager.close();
        MockBukkit.unmock();
    }

    @Test
    void invalidInputCancelReloadDisconnectAndTimeoutCreateNoRevision() {
        openEditor();
        click(10);
        assertTrue(manager.awaitingChat(player.getUniqueId()));
        manager.receiveChat(player.getUniqueId(), "missing-style");
        assertTrue(manager.awaitingChat(player.getUniqueId()));
        assertEquals(PaperTemplateEditorSession.State.AWAITING_CHAT,
                manager.sessionState(player.getUniqueId()));
        manager.receiveChat(player.getUniqueId(), "cancel");
        assertFalse(manager.awaitingChat(player.getUniqueId()));
        assertEquals(PaperTemplateEditorSession.State.EDITING,
                manager.sessionState(player.getUniqueId()));
        assertEquals(0, useCase.confirmCalls);

        manager.closeSessions("configuration reload");
        assertEquals(0, manager.activeSessionCount());
        assertEquals(0, useCase.confirmCalls);

        openEditor();
        server.getPluginManager().callEvent(new PlayerQuitEvent(
                player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        assertEquals(0, manager.activeSessionCount());
        assertEquals(0, useCase.confirmCalls);

        openEditor();
        server.getScheduler().performOneTick();
        server.getScheduler().performOneTick();
        server.getScheduler().performOneTick();
        assertEquals(0, manager.activeSessionCount());
        assertEquals(0, useCase.confirmCalls);
    }

    @Test
    void staleAsyncChatFromAClosedSessionCannotEditANewSession() {
        openEditor();
        click(10);
        UUID staleSessionId = manager.chatSessionId(player.getUniqueId());
        assertNotNull(staleSessionId);
        manager.closeSessions("controlled restart");

        openEditor();
        click(10);
        UUID currentSessionId = manager.chatSessionId(player.getUniqueId());
        assertNotNull(currentSessionId);
        assertFalse(staleSessionId.equals(currentSessionId));

        manager.receiveChat(player.getUniqueId(), staleSessionId, "literal stale");
        assertTrue(manager.awaitingChat(player.getUniqueId()));
        assertEquals(PaperTemplateEditorSession.State.AWAITING_CHAT,
                manager.sessionState(player.getUniqueId()));
        assertEquals(0, useCase.confirmCalls);
    }

    @Test
    void privateSetCommandEditsTheSelectedFieldWithoutPublicChat() {
        openEditor();
        click(11);
        assertTrue(manager.awaitingChat(player.getUniqueId()));

        LoreItemsEditorCommandSupport.execute(
                player, new String[] {"set", "literal", "Private", "name"}, manager);

        assertFalse(manager.awaitingChat(player.getUniqueId()));
        assertEquals(PaperTemplateEditorSession.State.EDITING,
                manager.sessionState(player.getUniqueId()));
        ItemStack draft = player.getOpenInventory().getTopInventory().getItem(4);
        assertNotNull(draft);
        assertEquals("Private name",
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(draft.getItemMeta().customName()));
        assertEquals(0, useCase.confirmCalls);
    }

    @Test
    void duplicateConfirmationClicksCreateOneLogicalRequestAndWakeOneRollout() {
        openEditor();
        click(10);
        manager.receiveChat(player.getUniqueId(), "literal Edited name");
        click(PaperTemplateEditorRenderer.EDITOR_PREVIEW);
        assertEquals(PaperTemplateEditorSession.State.PREVIEW,
                manager.sessionState(player.getUniqueId()));

        click(PaperTemplateEditorRenderer.PREVIEW_CONFIRM);
        click(PaperTemplateEditorRenderer.PREVIEW_CONFIRM);

        assertEquals(1, useCase.confirmCalls);
        assertNotNull(useCase.request);
        assertEquals(REVISION_ONE, useCase.request.expectedCurrentRevision());
        assertFalse(useCase.request.beforeTemplate().equals(useCase.request.template()));
        assertEquals(PaperTemplateEditorSession.State.CONFIRMING,
                manager.sessionState(player.getUniqueId()));

        useCase.confirmation.complete(TemplateRevisionStartResult.started(
                snapshot.definition().id(),
                REVISION_TWO,
                TemplateRevisionRolloutBatchResult.complete(0)));

        assertEquals(0, manager.activeSessionCount());
        assertEquals(1, rolloutWakes.get());
    }

    @Test
    void confirmationTimeoutDoesNotClaimTheDurableRequestWasCancelled() {
        openEditor();
        click(10);
        manager.receiveChat(player.getUniqueId(), "literal Edited name");
        click(PaperTemplateEditorRenderer.EDITOR_PREVIEW);
        click(PaperTemplateEditorRenderer.PREVIEW_CONFIRM);
        assertEquals(1, useCase.confirmCalls);
        while (player.nextComponentMessage() != null) {
            // Discard setup and prompt messages before asserting the timeout outcome.
        }

        server.getScheduler().performOneTick();
        server.getScheduler().performOneTick();
        server.getScheduler().performOneTick();

        assertEquals(0, manager.activeSessionCount());
        assertEquals(
                LoreItemsMessages.format(
                        "Template confirmation is still processing; reopen management to check durable status."),
                player.nextComponentMessage());
        assertEquals(0, rolloutWakes.get());
    }

    @Test
    void unchangedDraftCannotCreateARevision() {
        openEditor();
        click(10);
        manager.receiveChat(player.getUniqueId(), "clear");
        click(PaperTemplateEditorRenderer.EDITOR_PREVIEW);
        click(PaperTemplateEditorRenderer.PREVIEW_CONFIRM);

        assertEquals(0, useCase.confirmCalls);
        assertEquals(PaperTemplateEditorSession.State.PREVIEW,
                manager.sessionState(player.getUniqueId()));
        assertEquals(0, rolloutWakes.get());
    }

    @Test
    void replaceHeldUsesExactCodecFallbackAndStripsLoreIdentity() {
        PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();
        ItemStack held = ItemStack.of(Material.DIAMOND_SWORD, 4);
        ItemMeta meta = held.getItemMeta();
        meta.customName(Component.text("Exact replacement"));
        assertTrue(held.setItemMeta(meta));
        held = identityCodec.writeIdentity(held, new LoreItemIdentity(
                snapshot.definition().id(),
                new LoreInstanceId(UUID.randomUUID()),
                REVISION_ONE));
        player.getInventory().setItemInMainHand(held);
        manager.openManagement(player.getUniqueId(), snapshot.definition().id(), 1);

        click(PaperTemplateEditorRenderer.MANAGEMENT_REPLACE);

        PaperTemplateEditorView view = assertInstanceOf(
                PaperTemplateEditorView.class,
                player.getOpenInventory().getTopInventory().getHolder());
        assertEquals(PaperTemplateEditorView.Screen.PREVIEW, view.screen);
        ItemStack draft = player.getOpenInventory().getTopInventory().getItem(15);
        assertNotNull(draft);
        assertEquals(Material.DIAMOND_SWORD, draft.getType());
        assertEquals(1, draft.getAmount());
        assertEquals(1, draft.getItemMeta().getMaxStackSize());
        assertEquals("Exact replacement",
                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(draft.getItemMeta().customName()));
        assertInstanceOf(ItemIdentityReadResult.Untracked.class,
                identityCodec.readIdentity(draft));
        assertEquals(0, useCase.confirmCalls);
    }

    @Test
    void editOnlyAdministratorCanUseTheManagementGui() {
        auditPermission.setPermission(
                LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION, false);

        manager.openManagement(player.getUniqueId(), snapshot.definition().id(), 1);
        click(PaperTemplateEditorRenderer.MANAGEMENT_EDIT);

        assertEquals(1, manager.activeSessionCount());
        assertEquals(
                PaperTemplateEditorSession.State.EDITING,
                manager.sessionState(player.getUniqueId()));
        click(10);
        assertTrue(manager.awaitingChat(player.getUniqueId()));
    }

    @Test
    void rejectsDuplicateAndOverCapacityEditorSessions() {
        openEditor();
        manager.openManagement(player.getUniqueId(), snapshot.definition().id(), 1);
        click(PaperTemplateEditorRenderer.MANAGEMENT_EDIT);
        assertEquals(1, manager.activeSessionCount());

        for (int index = 1; index < 32; index++) {
            PlayerMock additional = editorPlayer();
            manager.openManagement(additional.getUniqueId(), snapshot.definition().id(), 1);
            click(additional, PaperTemplateEditorRenderer.MANAGEMENT_EDIT);
        }
        assertEquals(32, manager.activeSessionCount());

        PlayerMock overflow = editorPlayer();
        manager.openManagement(overflow.getUniqueId(), snapshot.definition().id(), 1);
        click(overflow, PaperTemplateEditorRenderer.MANAGEMENT_EDIT);
        assertEquals(32, manager.activeSessionCount());
    }

    @Test
    void revokedBrowsePermissionWhileManagementQueryIsPendingPreventsRendering() {
        server.getServicesManager().unregister(TemplateManagementUseCase.class, useCase);
        CompletableFuture<Optional<TemplateManagementSnapshot>> pending = new CompletableFuture<>();
        TemplateManagementUseCase delayedUseCase = new TemplateManagementUseCase() {
            @Override
            public CompletionStage<Optional<TemplateManagementSnapshot>> findSnapshot(
                    LoreDefinitionId definitionId) {
                return pending;
            }

            @Override
            public CompletionStage<TemplateRevisionStartResult> confirm(
                    TemplateRevisionRolloutRequest request) {
                return CompletableFuture.failedFuture(
                        new AssertionError("confirmation must not run"));
            }
        };
        server.getServicesManager().register(
                TemplateManagementUseCase.class,
                delayedUseCase,
                plugin,
                ServicePriority.Normal);

        manager.openManagement(player.getUniqueId(), snapshot.definition().id(), 1);
        auditPermission.setPermission(
                LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION, false);
        editPermission.setPermission(PaperTemplateEditorManager.EDIT_PERMISSION, false);
        pending.complete(Optional.of(snapshot));

        var topInventory = player.getOpenInventory().getTopInventory();
        assertFalse(topInventory != null
                && topInventory.getHolder() instanceof PaperTemplateEditorView);
    }

    @Test
    void timedOutManagementQueryReleasesItsPermit() throws InterruptedException {
        server.getServicesManager().unregister(TemplateManagementUseCase.class, useCase);
        AtomicInteger calls = new AtomicInteger();
        CompletableFuture<Optional<TemplateManagementSnapshot>> hanging =
                new CompletableFuture<>();
        TemplateManagementUseCase timedUseCase = new TemplateManagementUseCase() {
            @Override
            public CompletionStage<Optional<TemplateManagementSnapshot>> findSnapshot(
                    LoreDefinitionId definitionId) {
                return calls.incrementAndGet() == 1
                        ? hanging
                        : CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletionStage<TemplateRevisionStartResult> confirm(
                    TemplateRevisionRolloutRequest request) {
                return CompletableFuture.failedFuture(
                        new AssertionError("confirmation must not run"));
            }
        };
        server.getServicesManager().register(
                TemplateManagementUseCase.class,
                timedUseCase,
                plugin,
                ServicePriority.Normal);
        CountDownLatch timedOut = new CountDownLatch(1);
        PaperTemplateManagementLoader loader = new PaperTemplateManagementLoader(
                plugin,
                new PaperTemplateEditorRenderer(),
                new PaperItemTemplateCodec(),
                (playerId, operation, failure) -> timedOut.countDown(),
                Runnable::run,
                1,
                10L,
                TimeUnit.MILLISECONDS);

        loader.open(player.getUniqueId(), snapshot.definition().id(), 1);
        assertTrue(timedOut.await(2L, TimeUnit.SECONDS));
        loader.open(player.getUniqueId(), snapshot.definition().id(), 1);

        assertEquals(2, calls.get());
    }

    @Test
    void permissionAndDegradedServicePathsDoNotOpenDrafts() {
        editPermission.setPermission(PaperTemplateEditorManager.EDIT_PERMISSION, false);
        manager.openManagement(player.getUniqueId(), snapshot.definition().id(), 1);
        click(PaperTemplateEditorRenderer.MANAGEMENT_EDIT);
        assertEquals(0, manager.activeSessionCount());
        assertEquals(0, useCase.confirmCalls);

        server.getServicesManager().unregister(TemplateManagementUseCase.class, useCase);
        manager.openManagement(player.getUniqueId(), snapshot.definition().id(), 1);
        assertEquals(0, manager.activeSessionCount());
        assertEquals(0, useCase.confirmCalls);
    }

    private void openEditor() {
        manager.openManagement(player.getUniqueId(), snapshot.definition().id(), 1);
        click(PaperTemplateEditorRenderer.MANAGEMENT_EDIT);
        assertEquals(1, manager.activeSessionCount());
        assertEquals(PaperTemplateEditorSession.State.EDITING,
                manager.sessionState(player.getUniqueId()));
    }

    private PlayerMock editorPlayer() {
        PlayerMock additional = server.addPlayer();
        additional.addAttachment(
                plugin, LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION, true);
        additional.addAttachment(plugin, PaperTemplateEditorManager.EDIT_PERMISSION, true);
        return additional;
    }

    private void click(int rawSlot) {
        click(player, rawSlot);
    }

    private void click(PlayerMock clickingPlayer, int rawSlot) {
        InventoryClickEvent event = new InventoryClickEvent(
                clickingPlayer.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                rawSlot,
                ClickType.LEFT,
                InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled());
    }

    private TemplateManagementSnapshot snapshot(ItemStack item) {
        LoreDefinitionId definitionId = new LoreDefinitionId(UUID.randomUUID());
        LoreDefinition definition = new LoreDefinition(
                definitionId,
                new DefinitionKey("editor-manager"),
                "Editor Manager",
                REVISION_ONE,
                1L,
                null);
        return new TemplateManagementSnapshot(
                definition,
                new PaperItemTemplateCodec().encode(item),
                2L,
                1L,
                0L);
    }

    private static final class RecordingUseCase implements TemplateManagementUseCase {
        private final TemplateManagementSnapshot snapshot;
        private final CompletableFuture<TemplateRevisionStartResult> confirmation =
                new CompletableFuture<>();
        private TemplateRevisionRolloutRequest request;
        private int confirmCalls;

        private RecordingUseCase(TemplateManagementSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public CompletionStage<Optional<TemplateManagementSnapshot>> findSnapshot(
                LoreDefinitionId definitionId) {
            return CompletableFuture.completedFuture(
                    snapshot.definition().id().equals(definitionId)
                            ? Optional.of(snapshot)
                            : Optional.empty());
        }

        @Override
        public CompletionStage<TemplateRevisionStartResult> confirm(
                TemplateRevisionRolloutRequest request) {
            confirmCalls++;
            this.request = request;
            return confirmation;
        }
    }
}
