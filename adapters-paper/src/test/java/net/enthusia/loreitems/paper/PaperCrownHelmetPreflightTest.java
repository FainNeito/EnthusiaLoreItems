package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class PaperCrownHelmetPreflightTest {
    @BeforeEach void start() { MockBukkit.mock(); }
    @AfterEach void stop() { MockBukkit.unmock(); }

    @Test void detectsHelmetAndMetadataChangesWithoutRetainingOrMutatingTheItem() {
        var player = MockBukkit.getMock().addPlayer();
        var guard = new PaperItemCodecThreadGuard(() -> true);
        var preflight = new PaperCrownHelmetPreflight(guard);
        var id = player.getUniqueId();
        var empty = preflight.observe(player, id);
        assertEquals(PaperCrownHelmetPreflight.Status.EMPTY, empty.status());
        var helmet = ItemStack.of(Material.NETHERITE_HELMET);
        player.getInventory().setHelmet(helmet);
        var first = preflight.observe(player, id);
        assertEquals(PaperCrownHelmetPreflight.Status.UNTRACKED, first.status());
        assertEquals(PaperCrownHelmetPreflight.Comparison.CHANGED, preflight.compare(empty, first));
        assertEquals(PaperCrownHelmetPreflight.Comparison.UNCHANGED, preflight.compare(first, preflight.observe(player, id)));
        var edited = player.getInventory().getHelmet();
        var meta = edited.getItemMeta(); meta.displayName(Component.text("Changed")); edited.setItemMeta(meta);
        player.getInventory().setHelmet(edited);
        assertEquals(PaperCrownHelmetPreflight.Comparison.CHANGED, preflight.compare(first, preflight.observe(player, id)));
        assertEquals(edited, player.getInventory().getHelmet());
    }

    @Test void refusesTrackedWrongPlayerOfflineAndOffThreadObservations() {
        var player = MockBukkit.getMock().addPlayer();
        var guard = new PaperItemCodecThreadGuard(() -> true);
        var preflight = new PaperCrownHelmetPreflight(guard);
        var id = player.getUniqueId();
        var identity = new LoreItemIdentity(new LoreDefinitionId(UUID.randomUUID()), new LoreInstanceId(UUID.randomUUID()), new TemplateRevision(1));
        player.getInventory().setHelmet(new PaperItemIdentityCodec(guard).writeIdentity(ItemStack.of(Material.NETHERITE_HELMET), identity));
        var tracked = preflight.observe(player, id);
        assertEquals(PaperCrownHelmetPreflight.Status.TRACKED_OR_INVALID, tracked.status());
        assertEquals(PaperCrownHelmetPreflight.Comparison.HOLD, preflight.compare(tracked, tracked));
        assertEquals(PaperCrownHelmetPreflight.Status.UNAVAILABLE, preflight.observe(player, UUID.randomUUID()).status());
        assertEquals(PaperCrownHelmetPreflight.Status.OFFLINE, preflight.observe(null, id).status());
        assertThrows(IllegalStateException.class,
                () -> new PaperCrownHelmetPreflight(new PaperItemCodecThreadGuard(() -> false)).observe(player, id));
    }

    @Test void unavailablePlayerAndTrackedReplacementKeepExchangeOnHold() {
        var player = MockBukkit.getMock().addPlayer();
        var guard = new PaperItemCodecThreadGuard(() -> true);
        var preflight = new PaperCrownHelmetPreflight(guard);
        var id = player.getUniqueId();
        player.getInventory().setHelmet(ItemStack.of(Material.NETHERITE_HELMET));
        var observed = preflight.observe(player, id);
        assertEquals(PaperCrownHelmetPreflight.Status.UNTRACKED, observed.status());
        assertEquals(PaperCrownHelmetPreflight.Comparison.HOLD, preflight.compare(observed, preflight.observe(null, id)));
        var tracked = new LoreItemIdentity(new LoreDefinitionId(UUID.randomUUID()), new LoreInstanceId(UUID.randomUUID()), new TemplateRevision(1));
        player.getInventory().setHelmet(new PaperItemIdentityCodec(guard).writeIdentity(ItemStack.of(Material.NETHERITE_HELMET), tracked));
        assertEquals(PaperCrownHelmetPreflight.Comparison.HOLD, preflight.compare(observed, preflight.observe(player, id)));
    }
}
