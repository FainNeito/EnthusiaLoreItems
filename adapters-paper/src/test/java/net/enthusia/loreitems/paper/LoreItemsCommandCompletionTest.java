package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.AdoptHeldItemUseCase;
import net.enthusia.loreitems.application.PrepareHeldItemAdoptionRequest;
import net.enthusia.loreitems.application.PrepareHeldItemAdoptionResult;
import net.enthusia.loreitems.application.PreparedHeldItemAdoption;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class LoreItemsCommandCompletionTest {
    private Plugin plugin;
    private PlayerMock player;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        player = MockBukkit.getMock().addPlayer();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void hidesAdministrativeRoutesWithoutTheirPermissions() {
        assertEquals(List.of(), LoreItemsCommandExecutor.topLevelCompletions(player, ""));
    }

    @Test
    void filtersTopLevelRoutesByPermissionAndPrefix() {
        player.addAttachment(plugin, "enthusia.loreitems.admin.create", true);
        player.addAttachment(
                plugin, LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION, true);

        assertEquals(
                List.of("create", "browse", "anomalies", "audit", "recovery"),
                LoreItemsCommandExecutor.topLevelCompletions(player, ""));
        assertEquals(
                List.of("anomalies", "audit"),
                LoreItemsCommandExecutor.topLevelCompletions(player, "a"));
        assertEquals(
                List.of("browse"),
                LoreItemsCommandExecutor.topLevelCompletions(player, "BR"));
    }

    @Test
    void filtersDestructiveRoutesByTheirExactPermissions() {
        player.addAttachment(
                plugin, LoreItemsDestructiveCommandExecutor.PURGE_PERMISSION, true);
        player.addAttachment(
                plugin, LoreItemsDestructiveCommandExecutor.INSPECT_PERMISSION, true);

        assertEquals(
                List.of("purge", "operations", "targets", "destructive-metrics"),
                LoreItemsCommandExecutor.topLevelCompletions(player, ""));
        assertEquals(
                List.of("purge"),
                LoreItemsCommandExecutor.topLevelCompletions(player, "p"));
    }

    @Test
    void editOnlyAdministratorsCanBrowseWithoutEvidenceRoutes() {
        player.addAttachment(plugin, PaperTemplateEditorManager.EDIT_PERMISSION, true);

        assertEquals(
                List.of("set", "editor", "browse"),
                LoreItemsCommandExecutor.topLevelCompletions(player, ""));
        assertTrue(PaperTrackingAdministrationGui.canUseScreen(
                player, PaperTrackingAdministrationView.Screen.DEFINITIONS));
        assertFalse(PaperTrackingAdministrationGui.canUseScreen(
                player, PaperTrackingAdministrationView.Screen.INSTANCES));
        assertFalse(PaperTrackingAdministrationGui.canUseScreen(
                player, PaperTrackingAdministrationView.Screen.EVIDENCE));
        assertFalse(PaperTrackingAdministrationGui.canUseScreen(
                player, PaperTrackingAdministrationView.Screen.CONFIRMATION));
    }

    @Test
    void threeArgumentExecutorDoesNotAdvertiseUnavailableAdministrationRoutes() {
        player.addAttachment(
                plugin, LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION, true);
        LoreItemsCommandExecutor executor = new LoreItemsCommandExecutor(
                new CreateDefinitionCommandExecutor(
                        plugin,
                        request -> CompletableFuture.failedFuture(
                                new AssertionError("create must not execute")),
                        new PaperHeldItemDefinitionSnapshotter()),
                new AdoptHeldItemCommandExecutor(
                        plugin,
                        LoreItemsCommandCompletionTest::unusedAdoptionUseCase,
                        new PaperHeldItemAdoptionOperator()),
                new GiveLoreItemCommandExecutor(
                        plugin,
                        (definitionKey, playerId, operationId) ->
                                CompletableFuture.failedFuture(
                                        new AssertionError("give must not execute")),
                        ignored -> {}));

        assertEquals(
                List.of(),
                executor.onTabComplete(
                        player, command(), "loreitems", new String[] {""}));
    }

    private static AdoptHeldItemUseCase unusedAdoptionUseCase() {
        return new AdoptHeldItemUseCase() {
            @Override
            public CompletionStage<PrepareHeldItemAdoptionResult> prepare(
                    PrepareHeldItemAdoptionRequest request) {
                return CompletableFuture.failedFuture(
                        new AssertionError("adoption must not execute"));
            }

            @Override
            public CompletionStage<Boolean> complete(
                    PreparedHeldItemAdoption adoption, String afterFingerprint) {
                return CompletableFuture.failedFuture(
                        new AssertionError("adoption must not execute"));
            }

            @Override
            public CompletionStage<Boolean> requireReview(
                    PreparedHeldItemAdoption adoption, String reason) {
                return CompletableFuture.failedFuture(
                        new AssertionError("adoption must not execute"));
            }
        };
    }

    private static Command command() {
        return new Command("loreitems") {
            @Override
            public boolean execute(
                    CommandSender sender, String commandLabel, String[] arguments) {
                throw new AssertionError("command must not execute");
            }
        };
    }
}
