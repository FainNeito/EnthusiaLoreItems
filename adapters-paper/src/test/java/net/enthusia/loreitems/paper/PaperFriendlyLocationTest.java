package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperFriendlyLocationTest {

    private PlayerMock player;
    private PaperFriendlyLocation friendly;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin();
        player = MockBukkit.getMock().addPlayer("P2wn");
        friendly = new PaperFriendlyLocation(plugin);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void playerInventoryAndEnderChestUsePlayerNameInsteadOfUuidOrSlot() {
        String key = "player:" + player.getUniqueId();
        String inventory = friendly.describe(new LocationDescriptor(
                LocationDescriptor.Type.PLAYER_INVENTORY, key, "slot:12"));
        assertTrue(inventory.contains("P2wn"));
        assertTrue(inventory.contains("inventory"));
        assertFalse(inventory.contains(player.getUniqueId().toString()));
        assertFalse(inventory.contains("slot:"));

        String ender = friendly.describe(new LocationDescriptor(
                LocationDescriptor.Type.PLAYER_ENDER_CHEST, key, "slot:1"));
        assertTrue(ender.contains("P2wn"));
        assertTrue(ender.contains("Ender Chest"));
    }

    @Test
    void containersAndDroppedItemsShowCoordinatesWithoutTechnicalEntityIds() {
        String chest = friendly.describe(new LocationDescriptor(
                LocationDescriptor.Type.BLOCK_CONTAINER,
                "minecraft:overworld:12:64:-3", "slot:10"));
        assertTrue(chest.contains("12, 64, -3"));
        assertTrue(chest.contains("(world)") || chest.contains("(overworld)"));
        assertFalse(chest.contains("slot:"));

        String drop = friendly.describe(new LocationDescriptor(
                LocationDescriptor.Type.DROPPED_ITEM,
                "minecraft:overworld:entity:44444444-4444-4444-4444-444444444444:9:65:11",
                "item-entity"));
        assertTrue(drop.contains("9, 65, 11"));
        assertTrue(drop.contains("(world)") || drop.contains("(overworld)"));
        assertFalse(drop.contains("44444444"));
    }
}
