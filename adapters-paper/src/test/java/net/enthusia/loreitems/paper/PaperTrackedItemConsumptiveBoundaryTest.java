package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.event.player.PlayerChangeBeaconEffectEvent;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Piglin;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperTrackedItemConsumptiveBoundaryTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString(
                    "11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString(
                    "22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(3));

    private ServerMock server;
    private PlayerMock player;
    private PaperTrackedItemProtectionListener listener;
    private PaperItemIdentityCodec identityCodec;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        Plugin plugin = MockBukkit.createMockPlugin();
        listener = new PaperTrackedItemProtectionListener(
                plugin,
                () -> {
                    throw new AssertionError("Void-loss use case must not run in this test");
                },
                4);
        identityCodec = new PaperItemIdentityCodec();
    }

    @AfterEach
    void tearDown() {
        listener.close();
        MockBukkit.unmock();
    }

    @Test
    void beaconPaymentKeepsTrackedIdentityWithoutBlockingEffectChange() {
        Inventory inventory = server.createInventory(null, 9);
        inventory.setItem(0, tracked(Material.GOLD_INGOT));
        player.openInventory(inventory);
        Block beacon = player.getWorld().getBlockAt(0, 64, 0);
        beacon.setType(Material.BEACON);
        PlayerChangeBeaconEffectEvent event =
                new PlayerChangeBeaconEffectEvent(player, null, null, beacon);
        event.setConsumeItem(true);

        listener.onBeaconEffectChange(event);

        assertFalse(event.willConsumeItem());
        assertFalse(event.isCancelled());

        inventory.setItem(0, ItemStack.of(Material.GOLD_INGOT));
        PlayerChangeBeaconEffectEvent ordinary =
                new PlayerChangeBeaconEffectEvent(player, null, null, beacon);
        ordinary.setConsumeItem(true);
        listener.onBeaconEffectChange(ordinary);
        assertTrue(ordinary.willConsumeItem());
    }

    @Test
    void ordinaryMobsCannotRetainTrackedDroppedItemsButPlayersCanPickThemUp() {
        Item dropped = player.getWorld().dropItem(player.getLocation(), tracked(Material.DIAMOND));
        EntityPickupItemEvent mobPickup = new EntityPickupItemEvent(
                proxy(LivingEntity.class), dropped, 0);

        listener.onEntityPickup(mobPickup);

        assertTrue(mobPickup.isCancelled());

        EntityPickupItemEvent playerPickup = new EntityPickupItemEvent(player, dropped, 0);
        listener.onEntityPickup(playerPickup);
        assertFalse(playerPickup.isCancelled());
    }

    @Test
    void trackedGoldCannotBeHandedDirectlyToPiglinForBarter() {
        player.getInventory().setItemInMainHand(tracked(Material.GOLD_INGOT));
        Piglin piglin = proxy(Piglin.class);
        PlayerInteractEntityEvent trackedBarter =
                new PlayerInteractEntityEvent(player, piglin, EquipmentSlot.HAND);

        listener.onEntityInteraction(trackedBarter);

        assertTrue(trackedBarter.isCancelled());

        player.getInventory().setItemInMainHand(ItemStack.of(Material.GOLD_INGOT));
        PlayerInteractEntityEvent ordinaryBarter =
                new PlayerInteractEntityEvent(player, piglin, EquipmentSlot.HAND);
        listener.onEntityInteraction(ordinaryBarter);
        assertFalse(ordinaryBarter.isCancelled());
    }

    @Test
    void edibleTrackedItemsAreTreatedAsConsumptiveBlockInteractions() throws Exception {
        Method method = PaperTrackedItemInteractionRules.class.getDeclaredMethod(
                "losesIdentityOnInteraction", Material.class);
        assertTrue(method.trySetAccessible());

        assertTrue((Boolean) method.invoke(null, Material.CHICKEN));
    }

    private ItemStack tracked(Material material) {
        return identityCodec.writeIdentity(ItemStack.of(material), IDENTITY);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {type},
                (instance, method, args) -> defaultValue(method.getReturnType()));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        return Array.get(Array.newInstance(type, 1), 0);
    }
}
