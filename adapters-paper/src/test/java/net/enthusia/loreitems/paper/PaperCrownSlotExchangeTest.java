package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.util.UUID;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.*;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class PaperCrownSlotExchangeTest {
    @Test void timedOutWindowReleasesOnlyTheHolderAndRejectsLateAdmission() {
        verifyInterruptedExchange(true);
    }

    @Test void directSourceReplacementWithoutAnEventHoldsTheExchange() {
        verifyInterruptedExchange(false);
    }

    private void verifyInterruptedExchange(boolean timeout) {
        var server = MockBukkit.mock();
        try {
            var holder = server.addPlayer();
            var other = server.addPlayer();
            var plugin = MockBukkit.createMockPlugin();
            var guard = new PaperItemCodecThreadGuard(() -> true);
            var request = new ExclusiveCustodyReservation(UUID.randomUUID(), UUID.randomUUID(), holder.getUniqueId(),
                    1, ExclusiveCustodyReservation.State.RESERVED);
            var definition = UUID.randomUUID();
            var identity = new LoreItemIdentity(new LoreDefinitionId(definition), new LoreInstanceId(request.instanceId()),
                    new TemplateRevision(1));
            var crown = new PaperItemIdentityCodec(guard).writeIdentity(ItemStack.of(Material.NETHERITE_HELMET), identity);
            var original = ItemStack.of(Material.DIAMOND_HELMET);
            holder.getInventory().setHelmet(original);
            holder.getInventory().setItem(3, crown);
            var admission = new java.util.concurrent.CompletableFuture<Boolean>();
            var held = new java.util.concurrent.atomic.AtomicInteger();
            var store = new net.enthusia.loreitems.application.CrownExchangeAdmission() {
                public java.util.concurrent.CompletionStage<Boolean> admit(ExclusiveCustodyReservation r, UUID definitionId, boolean empty, byte[] bytes) {
                    return admission;
                }
                public java.util.concurrent.CompletionStage<Boolean> hold(ExclusiveCustodyReservation r) {
                    assertEquals(request, r);
                    held.incrementAndGet();
                    return java.util.concurrent.CompletableFuture.completedFuture(true);
                }
            };
            var fence = new PaperCrownPreparationFence(guard);
            var result = new PaperCrownSlotExchange(guard).stage(holder, request, definition, 3, store,
                    fence, plugin, Runnable::run).toCompletableFuture();
            assertTrue(swapCancelled(server, holder));
            assertFalse(swapCancelled(server, other));
            if (timeout) {
                server.getScheduler().performTicks(101);
                assertEquals(PaperCrownSlotExchange.Result.HELD, result.join());
                assertFalse(swapCancelled(server, holder));
            } else {
                var changed = crown.clone();
                changed.editMeta(meta -> meta.setUnbreakable(true));
                holder.getInventory().setItem(3, changed);
            }
            admission.complete(true);
            assertEquals(PaperCrownSlotExchange.Result.HELD, result.join());
            assertEquals(1, held.get());
            assertEquals(original, holder.getInventory().getHelmet());
            assertFalse(holder.getPersistentDataContainer().has(new NamespacedKey("enthusialoreitems","crown_exchange")));
            assertFalse(swapCancelled(server, holder));
            assertNotNull(fence.begin(holder.getUniqueId()));
        } finally { MockBukkit.unmock(); }
    }

    private static boolean swapCancelled(org.mockbukkit.mockbukkit.ServerMock server, org.bukkit.entity.Player player) {
        var event = new org.bukkit.event.player.PlayerSwapHandItemsEvent(player, null, null);
        server.getPluginManager().callEvent(event);
        return event.isCancelled();
    }
    @Test void stagesExistingCrownAndSealsOriginalWithoutReplaying(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        verifyStaging(temp, false);
    }

    @Test void stagesFirstCrownFromTheProviderTemplateWithFullStorage(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        verifyStaging(temp, true);
    }

    private void verifyStaging(java.nio.file.Path temp, boolean first) throws Exception {
        var server = MockBukkit.mock();
        var factory = PaperCrownHelmetSqliteIntegrationTest.fixture(temp, null);
        var metrics = net.enthusia.loreitems.application.MetricsPort.noOp();
        var storage = new net.enthusia.loreitems.sqlite.SQLiteStorageRuntime(factory,
                new net.enthusia.loreitems.sqlite.MigrationRunner(),
                new net.enthusia.loreitems.sqlite.BoundedDatabaseExecutor("crown-exchange-test", 16, metrics), metrics);
        try {
            storage.start().toCompletableFuture().join();
            var admission = new net.enthusia.loreitems.sqlite.SQLiteCrownHelmetEvidenceStore(storage);
            var player = server.addPlayer();
            var guard = new PaperItemCodecThreadGuard(() -> true);
            var request = new ExclusiveCustodyReservation(UUID.randomUUID(), UUID.randomUUID(), player.getUniqueId(),
                    1, ExclusiveCustodyReservation.State.RESERVED);
            var definition = UUID.randomUUID();
            var identity = new LoreItemIdentity(new LoreDefinitionId(definition),
                    new LoreInstanceId(request.instanceId()), new TemplateRevision(1));
            var crown = new PaperItemIdentityCodec(guard).writeIdentity(ItemStack.of(Material.NETHERITE_HELMET), identity);
            var original = ItemStack.of(Material.DIAMOND_HELMET);
            player.getInventory().setHelmet(original);
            var template = new PaperItemTemplateCodec(guard).encode(ItemStack.of(Material.NETHERITE_HELMET));
            if (first) {
                for (int slot=0; slot<player.getInventory().getStorageContents().length; slot++) {
                    player.getInventory().setItem(slot, ItemStack.of(Material.STONE));
                }
            } else player.getInventory().setItem(3, crown);
            var exchange = new PaperCrownSlotExchange(guard);
            // Register the provider's existing instance and its last confirmed player location.
            try (var c = factory.open(); var s = c.createStatement()) {
                s.execute("INSERT INTO lore_definitions VALUES ('" + definition + "','crown','Crown',1,1,NULL)");
                try (var insert = c.prepareStatement("INSERT INTO lore_definition_revisions VALUES (?,1,1,?,1)")) {
                    insert.setString(1, definition.toString()); insert.setBytes(2, template.payload()); insert.executeUpdate();
                }
                if (!first) {
                s.execute("INSERT INTO lore_instances VALUES ('" + request.instanceId() + "','" + definition + "',1,1,'ACTIVE',1,NULL)");
                String location = "player:" + player.getUniqueId();
                s.execute("INSERT INTO instance_observations (observation_id,instance_id,definition_id,location_type,location_key,"
                        + "confidence,source,observed_at) VALUES (1,'" + request.instanceId() + "','" + definition
                        + "','PLAYER_INVENTORY','" + location + "','CONFIRMED_NOW','fixture',1)");
                s.execute("INSERT INTO instance_current_state (instance_id,state,location_type,location_key,last_observation_id,"
                        + "state_revision,updated_at) VALUES ('" + request.instanceId() + "','CONFIRMED_NOW','PLAYER_INVENTORY','" + location + "',1,0,1)");
            }
            }
            var callbacks = new java.util.concurrent.LinkedBlockingQueue<Runnable>();
            var fence = new PaperCrownPreparationFence(guard);
            var plugin = MockBukkit.createMockPlugin();
            var result = first ? exchange.stageFirst(player, request, definition, 1, template, admission, fence, plugin, callbacks::add)
                    : exchange.stage(player, request, definition, 3, admission, fence, plugin, callbacks::add);
            var callback = callbacks.poll(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(callback);
            assertEquals(original, player.getInventory().getHelmet());
            assertFalse(admission.admit(request, definition, false, original.serializeAsBytes()).toCompletableFuture().join());
            callback.run();
            assertEquals(PaperCrownSlotExchange.Result.STAGED, result.toCompletableFuture().join());
            assertEquals(new net.enthusia.loreitems.application.ItemIdentityReadResult.Tracked(identity),
                    new PaperItemIdentityCodec(guard).readIdentity(player.getInventory().getHelmet()));
            if (first) assertEquals(Material.STONE, player.getInventory().getItem(3).getType());
            else assertNull(player.getInventory().getItem(3));
            var escrow = player.getPersistentDataContainer().get(new NamespacedKey("enthusialoreitems","crown_exchange"),
                    PersistentDataType.TAG_CONTAINER);
            assertNotNull(escrow);
            assertArrayEquals(original.serializeAsBytes(), escrow.get(new NamespacedKey("enthusialoreitems","original"),
                    PersistentDataType.BYTE_ARRAY));
        } finally {
            storage.close(java.time.Duration.ofSeconds(5));
            MockBukkit.unmock();
        }
    }
}
