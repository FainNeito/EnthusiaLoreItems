package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.loreitems.api.v1.LoreDeliveryResult;
import net.enthusia.loreitems.api.v1.LoreDeliveryStatus;
import net.enthusia.loreitems.application.DefinitionRepository;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.DefinitionKey;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperCreativeCopyControllerTest {
    private static final LoreDefinitionId DEFINITION_ID = new LoreDefinitionId(UUID.fromString(
            "11111111-1111-1111-1111-111111111111"));
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            DEFINITION_ID,
            new LoreInstanceId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    private static final String COPY_COMMAND = "copy";
    private PlayerMock player;
    private AtomicInteger queued;
    private AtomicInteger woken;
    private PaperCreativeCopyController controller;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin();
        player = MockBukkit.getMock().addPlayer();
        player.setGameMode(GameMode.CREATIVE);
        player.addAttachment(plugin, GiveLoreItemCommandExecutor.GIVE_PERMISSION, true);
        queued = new AtomicInteger();
        woken = new AtomicInteger();
        controller = new PaperCreativeCopyController(
                plugin,
                PaperCreativeCopyControllerTest::definitionRepository,
                (key, playerId, operationId) -> {
                    assertEquals("creative_copy_test", key);
                    assertEquals(player.getUniqueId(), playerId);
                    queued.incrementAndGet();
                    return CompletableFuture.completedFuture(new LoreDeliveryResult(
                            LoreDeliveryStatus.ACCEPTED_QUEUED, operationId, "queued"));
                },
                ignored -> woken.incrementAndGet());
        player.getInventory().setItem(0, trackedItem());
    }

    @AfterEach
    void tearDown() {
        controller.clear();
        MockBukkit.unmock();
    }

    @Test
    void confirmsOneFreshDurableDeliveryAndPreventsReplay() {
        controller.request(player, player.getInventory().getItem(0));
        UUID token = controller.pendingTokenForTest(player.getUniqueId());
        assertNotNull(token);
        assertEquals(0, queued.get());

        controller.execute(player, new String[]{COPY_COMMAND, "confirm", token.toString()});
        assertEquals(1, queued.get());
        assertEquals(1, woken.get());
        controller.execute(player, new String[]{COPY_COMMAND, "confirm", token.toString()});
        assertEquals(1, queued.get());
    }

    @Test
    void cancelledAndMissingSourceRequestsDoNotDeliver() {
        controller.request(player, player.getInventory().getItem(0));
        UUID token = controller.pendingTokenForTest(player.getUniqueId());
        controller.execute(player, new String[]{COPY_COMMAND, "cancel", token.toString()});
        assertEquals(0, queued.get());

        controller.request(player, player.getInventory().getItem(0));
        token = controller.pendingTokenForTest(player.getUniqueId());
        player.getInventory().setItem(0, ItemStack.empty());
        controller.execute(player, new String[]{COPY_COMMAND, "confirm", token.toString()});
        assertEquals(0, queued.get());
    }

    private static ItemStack trackedItem() {
        return new PaperItemIdentityCodec().writeIdentity(ItemStack.of(Material.SUGAR), IDENTITY);
    }

    private static DefinitionRepository definitionRepository() {
        LoreDefinition definition = new LoreDefinition(
                DEFINITION_ID, new DefinitionKey("creative_copy_test"),
                "Creative Copy Test", new TemplateRevision(1), 0L, null);
        return (DefinitionRepository) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[]{DefinitionRepository.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("findById")) {
                        return CompletableFuture.completedFuture(Optional.of(definition));
                    }
                    throw new AssertionError("Unexpected definition operation: " + method.getName());
                });
    }
}
