package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class PaperCrownHelmetEvidenceTest {
    @BeforeEach void start() { MockBukkit.mock(); }
    @AfterEach void stop() { MockBukkit.unmock(); }

    @Test void snapshotsTheOriginalHelmetWithoutMutatingItOrExposingMutableBytes() {
        var player = MockBukkit.getMock().addPlayer();
        var guard = new PaperItemCodecThreadGuard(() -> true);
        var capture = new PaperCrownHelmetEvidence(guard);
        var id = player.getUniqueId();
        var helmet = ItemStack.of(Material.NETHERITE_HELMET);
        player.getInventory().setHelmet(helmet);
        var expected = player.getInventory().getHelmet().serializeAsBytes();
        var snapshot = capture.capture(player, id).orElseThrow();
        assertEquals(id, snapshot.playerId());
        assertFalse(snapshot.empty());
        assertArrayEquals(expected, snapshot.bytes());
        byte[] leaked = snapshot.bytes(); leaked[0] ^= 1;
        assertArrayEquals(expected, snapshot.bytes());
        assertEquals(helmet, player.getInventory().getHelmet());
    }

    @Test void emptySlotHasExplicitEvidenceButUnavailablePlayersDoNot() {
        var player = MockBukkit.getMock().addPlayer();
        var guard = new PaperItemCodecThreadGuard(() -> true);
        var capture = new PaperCrownHelmetEvidence(guard);
        var id = player.getUniqueId();
        assertTrue(capture.capture(null, id).isEmpty());
        var empty = capture.capture(player, id).orElseThrow();
        assertTrue(empty.empty());
        assertEquals(0, empty.bytes().length);
        player.getInventory().setHelmet(ItemStack.of(Material.NETHERITE_HELMET));
        assertTrue(capture.capture(player, UUID.randomUUID()).isEmpty());
        var identity = new LoreItemIdentity(new LoreDefinitionId(UUID.randomUUID()), new LoreInstanceId(UUID.randomUUID()), new TemplateRevision(1));
        player.getInventory().setHelmet(new PaperItemIdentityCodec(guard).writeIdentity(ItemStack.of(Material.NETHERITE_HELMET), identity));
        assertTrue(capture.capture(player, id).isEmpty());
        assertThrows(IllegalStateException.class,
                () -> new PaperCrownHelmetEvidence(new PaperItemCodecThreadGuard(() -> false)).capture(player, id));
    }
}
