package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class PaperCrownHelmetProbeTest {
    @BeforeEach void start() { MockBukkit.mock(); }
    @AfterEach void stop() { MockBukkit.unmock(); }

    @Test void observesExactIdentityWithoutChangingEquipment() {
        var player = MockBukkit.getMock().addPlayer();
        var guard = new PaperItemCodecThreadGuard(() -> true);
        var probe = new PaperCrownHelmetProbe(guard);
        UUID id = UUID.randomUUID();
        UUID definition = UUID.randomUUID();
        assertEquals(PaperCrownHelmetProbe.Observation.OFFLINE, probe.observe(null, definition, id));
        assertEquals(PaperCrownHelmetProbe.Observation.EMPTY, probe.observe(player, definition, id));
        var helmet = ItemStack.of(Material.NETHERITE_HELMET);
        player.getInventory().setHelmet(helmet);
        assertEquals(PaperCrownHelmetProbe.Observation.UNTRACKED_ITEM, probe.observe(player, definition, id));
        var identity = new LoreItemIdentity(new LoreDefinitionId(definition), new LoreInstanceId(id), new TemplateRevision(1));
        var tracked = new PaperItemIdentityCodec(guard).writeIdentity(helmet, identity);
        player.getInventory().setHelmet(tracked);
        assertEquals(PaperCrownHelmetProbe.Observation.EXPECTED_INSTANCE, probe.observe(player, definition, id));
        assertEquals(PaperCrownHelmetProbe.Observation.OTHER_TRACKED_ITEM, probe.observe(player, definition, UUID.randomUUID()));
        assertEquals(PaperCrownHelmetProbe.Observation.OTHER_TRACKED_ITEM, probe.observe(player, UUID.randomUUID(), id));
        assertEquals(tracked, player.getInventory().getHelmet());
        tracked.setAmount(2); player.getInventory().setHelmet(tracked);
        assertEquals(PaperCrownHelmetProbe.Observation.INVALID_IDENTITY, probe.observe(player, definition, id));
        var malformed = ItemStack.of(Material.NETHERITE_HELMET);
        var meta = malformed.getItemMeta();
        meta.getPersistentDataContainer().set(new NamespacedKey("enthusialoreitems", "identity_version"), PersistentDataType.INTEGER, 1);
        malformed.setItemMeta(meta);
        player.getInventory().setHelmet(malformed);
        assertEquals(PaperCrownHelmetProbe.Observation.INVALID_IDENTITY, probe.observe(player, definition, id));
        assertEquals(malformed, player.getInventory().getHelmet());
    }

    @Test void refusesAccessOffServerThread() {
        var player = MockBukkit.getMock().addPlayer();
        var probe = new PaperCrownHelmetProbe(new PaperItemCodecThreadGuard(() -> false));
        assertThrows(IllegalStateException.class, () -> probe.observe(player, UUID.randomUUID(), UUID.randomUUID()));
    }
}
