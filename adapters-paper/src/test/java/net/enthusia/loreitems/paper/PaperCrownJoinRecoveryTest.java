package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.loreitems.application.CrownEquipmentRecovery;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperCrownJoinRecoveryTest {
    private ServerMock server;
    private Plugin plugin;
    private final AtomicInteger attempts = new AtomicInteger();
    private final CrownEquipmentRecovery recovery = new CrownEquipmentRecovery() {
        @Override public CompletionStage<Optional<Receipt>> findEquipped(UUID holder) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        @Override public CompletionStage<Optional<Delivery>> takeReleased(UUID killer) {
            attempts.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.empty());
        }
    };

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach void tearDown() { MockBukkit.unmock(); }

    /** Joined with a worn helmet and every other slot occupied; the join's own delivery attempt is not counted. */
    private PlayerMock fullWithHelmet() {
        PlayerMock player = server.addPlayer();
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) inventory.setItem(slot, new ItemStack(Material.STONE, 64));
        inventory.setHelmet(new ItemStack(Material.DIAMOND_HELMET));
        attempts.set(0);
        return player;
    }

    @Test void fullRecipientWearingAHelmetIsDeliveredAtOnce() {
        var joins = new PaperCrownJoinRecovery(plugin, recovery, () -> true);
        joins.register();
        PlayerMock player = fullWithHelmet();
        joins.deliver(player.getUniqueId());
        server.getScheduler().performOneTick();
        assertEquals(1, attempts.get());
    }

    @Test void deadRecipientIsNotDelivered() {
        var joins = new PaperCrownJoinRecovery(plugin, recovery, () -> true);
        joins.register();
        PlayerMock player = fullWithHelmet();
        player.setHealth(0);
        attempts.set(0);
        joins.deliver(player.getUniqueId());
        server.getScheduler().performOneTick();
        assertEquals(0, attempts.get());
    }
}
