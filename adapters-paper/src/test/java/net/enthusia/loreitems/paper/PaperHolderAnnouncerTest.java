package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperHolderAnnouncerTest {
    private static final LoreItemIdentity CROWN = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString("22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    @TempDir
    Path folder;

    private ServerMock server;
    private JavaPlugin plugin;
    private PlayerMock watcher;
    private PlayerMock alice;
    private PlayerMock bob;
    private PlayerMock carol;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private PaperHolderAnnouncer announcer;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        watcher = server.addPlayer("Watcher");
        alice = server.addPlayer("Alice");
        bob = server.addPlayer("Bob");
        carol = server.addPlayer("Carol");
        announcer = newAnnouncer();
    }

    @AfterEach
    void tearDown() {
        announcer.close();
        MockBukkit.unmock();
    }

    @Test
    void newHolderIsAnnouncedOnceAndTheSameHolderIsNotRepeated() {
        hold(alice);
        assertEquals("The Crown of Sorrow has a new bearer: Alice", nextBroadcast());

        announcer.checkHolder(alice);
        assertNull(nextBroadcast());
    }

    @Test
    void handOffsInsideTheCooldownCollapseIntoOneMessageAboutTheLatestHolder() {
        hold(alice);
        nextBroadcast();
        now.addAndGet(10_000L);
        give(alice, bob);
        give(bob, carol);
        assertNull(nextBroadcast());

        now.addAndGet(PaperHolderAnnouncer.COOLDOWN.toMillis());
        server.getScheduler().performTicks(PaperHolderAnnouncer.COOLDOWN.toSeconds() * 20 + 5);

        assertEquals("The Crown of Sorrow has a new bearer: Carol", nextBroadcast());
        assertNull(nextBroadcast());
    }

    @Test
    void handingItBackToTheAnnouncedHolderInsideTheCooldownSaysNothing() {
        hold(alice);
        nextBroadcast();
        give(alice, bob);
        give(bob, alice);

        now.addAndGet(PaperHolderAnnouncer.COOLDOWN.toMillis());
        server.getScheduler().performTicks(PaperHolderAnnouncer.COOLDOWN.toSeconds() * 20 + 5);

        assertNull(nextBroadcast());
    }

    @Test
    void holderAfterTheCooldownIsAnnouncedImmediately() {
        hold(alice);
        nextBroadcast();
        now.addAndGet(PaperHolderAnnouncer.COOLDOWN.toMillis() + 1);
        give(alice, bob);

        assertEquals("The Crown of Sorrow has a new bearer: Bob", nextBroadcast());
    }

    @Test
    @SuppressWarnings("deprecation")
    void creativeAndVanishedPlayersNeverAnnounce() {
        alice.setGameMode(GameMode.CREATIVE);
        hold(alice);
        bob.setMetadata("vanished", new FixedMetadataValue(plugin, true));
        give(alice, bob);

        assertNull(nextBroadcast());
    }

    @Test
    void lastAnnouncedHolderSurvivesARestart() throws Exception {
        hold(alice);
        nextBroadcast();
        server.getScheduler().waitAsyncTasksFinished();
        assertEquals(true, Files.exists(folder.resolve("holder-announcements.yml")));

        announcer.close();
        announcer = newAnnouncer();
        announcer.checkHolder(alice);

        assertNull(nextBroadcast());
    }

    @Test
    void slotChangeAnnouncesOnceTheTickEnds() {
        alice.openInventory(server.createInventory(null, 9));
        ItemStack crown = crown();
        alice.getInventory().setItem(3, crown);
        // With a 9-slot chest open, hotbar slot 3 is raw slot 39.
        announcer.onSlotChange(new PlayerInventorySlotChangeEvent(alice, 9 + 27 + 3, ItemStack.empty(), crown));
        assertNull(nextBroadcast());

        server.getScheduler().performOneTick();

        assertEquals("The Crown of Sorrow has a new bearer: Alice", nextBroadcast());
    }

    private PaperHolderAnnouncer newAnnouncer() {
        PaperHolderAnnouncer created = new PaperHolderAnnouncer(
                plugin, folder.resolve("holder-announcements.yml"), now::get);
        created.start();
        return created;
    }

    private void hold(PlayerMock player) {
        player.getInventory().setItem(0, crown());
        announcer.checkHolder(player);
    }

    private void give(PlayerMock from, PlayerMock to) {
        from.getInventory().clear();
        to.getInventory().setItem(0, crown());
        announcer.checkHolder(to);
    }

    private String nextBroadcast() {
        Component message = watcher.nextComponentMessage();
        return message == null ? null
                : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(message);
    }

    private static ItemStack crown() {
        ItemStack item = new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHERITE_HELMET), CROWN);
        item.editMeta(meta -> {
            meta.itemName(Component.text("Crown of Sorrow"));
            meta.getPersistentDataContainer().set(PaperHolderAnnouncer.KEY, PersistentDataType.BOOLEAN, true);
        });
        return item;
    }
}
