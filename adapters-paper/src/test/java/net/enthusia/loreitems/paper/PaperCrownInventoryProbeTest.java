package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.*;

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

class PaperCrownInventoryProbeTest {
    @BeforeEach void start() { MockBukkit.mock(); }
    @AfterEach void stop() { MockBukkit.unmock(); }

    @Test void findsHeadStorageAndDuplicateWithoutMutatingItems() {
        var player = MockBukkit.getMock().addPlayer();
        var guard = new PaperItemCodecThreadGuard(() -> true);
        var probe = new PaperCrownInventoryProbe(guard);
        var playerId = player.getUniqueId(); var definition = UUID.randomUUID(); var instance = UUID.randomUUID();
        var identity = new LoreItemIdentity(new LoreDefinitionId(definition), new LoreInstanceId(instance), new TemplateRevision(1));
        var crown = new PaperItemIdentityCodec(guard).writeIdentity(ItemStack.of(Material.NETHERITE_HELMET), identity);
        assertEquals(PaperCrownInventoryProbe.Observation.OFFLINE, probe.observe(null, playerId, definition, instance));
        assertEquals(PaperCrownInventoryProbe.Observation.MISSING, probe.observe(player, playerId, definition, instance));
        player.getInventory().setHelmet(crown);
        assertEquals(PaperCrownInventoryProbe.Observation.HEAD_ONLY, probe.observe(player, playerId, definition, instance));
        player.getInventory().setItem(0, crown.clone());
        assertEquals(PaperCrownInventoryProbe.Observation.DUPLICATE_OR_INVALID, probe.observe(player, playerId, definition, instance));
        assertEquals(crown, player.getInventory().getHelmet());
        assertEquals(crown, player.getInventory().getItem(0));
        player.getInventory().setHelmet(null);
        assertEquals(PaperCrownInventoryProbe.Observation.ELSEWHERE, probe.observe(player, playerId, definition, instance));
        player.setItemOnCursor(crown.clone());
        assertEquals(PaperCrownInventoryProbe.Observation.DUPLICATE_OR_INVALID, probe.observe(player, playerId, definition, instance));
        player.setItemOnCursor(null);
        assertEquals(PaperCrownInventoryProbe.Observation.UNAVAILABLE, probe.observe(player, UUID.randomUUID(), definition, instance));
    }

    @Test void sameInstanceWithWrongDefinitionAndOffThreadAreHeld() {
        var player = MockBukkit.getMock().addPlayer();
        var guard = new PaperItemCodecThreadGuard(() -> true);
        var instance = UUID.randomUUID(); var playerId = player.getUniqueId();
        var wrong = new LoreItemIdentity(new LoreDefinitionId(UUID.randomUUID()), new LoreInstanceId(instance), new TemplateRevision(1));
        player.getInventory().setHelmet(new PaperItemIdentityCodec(guard).writeIdentity(ItemStack.of(Material.NETHERITE_HELMET), wrong));
        assertEquals(PaperCrownInventoryProbe.Observation.DUPLICATE_OR_INVALID,
                new PaperCrownInventoryProbe(guard).observe(player, playerId, UUID.randomUUID(), instance));
        assertThrows(IllegalStateException.class, () -> new PaperCrownInventoryProbe(new PaperItemCodecThreadGuard(() -> false))
                .observe(player, playerId, UUID.randomUUID(), instance));
    }

    @Test void incompleteIdentityAnywhereInInventoryHoldsTheObservation() {
        var player = MockBukkit.getMock().addPlayer();
        var malformed = ItemStack.of(Material.PAPER);
        var meta = malformed.getItemMeta();
        meta.getPersistentDataContainer().set(new NamespacedKey("enthusialoreitems", "identity_version"), PersistentDataType.INTEGER, 1);
        malformed.setItemMeta(meta);
        player.getInventory().setItem(0, malformed);
        assertEquals(PaperCrownInventoryProbe.Observation.DUPLICATE_OR_INVALID,
                new PaperCrownInventoryProbe(new PaperItemCodecThreadGuard(() -> true)).observe(
                        player, player.getUniqueId(), UUID.randomUUID(), UUID.randomUUID()));
        assertEquals(malformed, player.getInventory().getItem(0));
    }

    @Test void crownStackAndDifferentInstanceOfTheCrownDefinitionAreRejected() {
        var player = MockBukkit.getMock().addPlayer();
        var guard = new PaperItemCodecThreadGuard(() -> true);
        var codec = new PaperItemIdentityCodec(guard);
        var definition = UUID.randomUUID(); var instance = UUID.randomUUID();
        var expected = new LoreItemIdentity(new LoreDefinitionId(definition), new LoreInstanceId(instance), new TemplateRevision(1));
        var duplicate = new LoreItemIdentity(new LoreDefinitionId(definition), new LoreInstanceId(UUID.randomUUID()), new TemplateRevision(1));
        var crown = codec.writeIdentity(ItemStack.of(Material.NETHERITE_HELMET), expected);
        player.getInventory().setHelmet(codec.writeIdentity(ItemStack.of(Material.DIAMOND_HELMET), expected));
        assertEquals(PaperCrownInventoryProbe.Observation.DUPLICATE_OR_INVALID,
                new PaperCrownInventoryProbe(guard).observe(player, player.getUniqueId(), definition, instance));
        crown.setAmount(2); player.getInventory().setHelmet(crown);
        var probe = new PaperCrownInventoryProbe(guard);
        assertEquals(PaperCrownInventoryProbe.Observation.DUPLICATE_OR_INVALID,
                probe.observe(player, player.getUniqueId(), definition, instance));
        crown.setAmount(1); player.getInventory().setHelmet(crown);
        player.getInventory().setItem(0, codec.writeIdentity(ItemStack.of(Material.NETHERITE_HELMET), duplicate));
        assertEquals(PaperCrownInventoryProbe.Observation.DUPLICATE_OR_INVALID,
                probe.observe(player, player.getUniqueId(), definition, instance));
    }
}
