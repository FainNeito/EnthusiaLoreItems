package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class PaperTrackedItemCollectorContainerGateTest {
    private static final String ROOT = "minecraft:overworld:10:64:10";
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString(
                    "11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString(
                    "22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void regularTrackedAndForeignItemsRequireNoNestedMetadataSnapshot() {
        ItemStack tracked = tracked();
        CountingOrdinaryItem item = new CountingOrdinaryItem(Material.NETHER_STAR);
        assertTrue(item.setItemMeta(tracked.getItemMeta()));
        item.resetReads();
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new ConcurrentHashMap<>();
        collect(item, observations);
        assertEquals(0, item.metadataReads());
        assertEquals(List.of("slot:0"), paths(observations));

        CountingOrdinaryItem foreign = new CountingOrdinaryItem(Material.DIAMOND_SWORD);
        ItemMeta meta = foreign.getItemMeta();
        meta.displayName(Component.text("Ordinary custom item"));
        assertTrue(foreign.setItemMeta(meta));
        foreign.resetReads();
        Map<LoreItemIdentity, List<LocationDescriptor>> absent = new ConcurrentHashMap<>();
        collect(foreign, absent);
        assertEquals(0, foreign.metadataReads());
        assertTrue(absent.isEmpty());
    }

    @Test
    void coloredShulkerPreservesNestedIdentity() {
        ItemStack item = ItemStack.of(Material.BLUE_SHULKER_BOX);
        BlockStateMeta meta = assertInstanceOf(BlockStateMeta.class, item.getItemMeta());
        ShulkerBox box = assertInstanceOf(ShulkerBox.class, meta.getBlockState());
        box.getInventory().setItem(0, tracked());
        meta.setBlockState(box);
        assertTrue(item.setItemMeta(meta));

        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new ConcurrentHashMap<>();
        collect(item, observations);
        assertEquals(List.of("slot:0/shulker:0"), paths(observations));
    }

    @Test
    void coloredBundlePreservesNestedIdentity() {
        ItemStack item = ItemStack.of(Material.WHITE_BUNDLE);
        BundleMeta meta = assertInstanceOf(BundleMeta.class, item.getItemMeta());
        meta.addItem(tracked());
        assertTrue(item.setItemMeta(meta));

        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new ConcurrentHashMap<>();
        collect(item, observations);
        assertEquals(List.of("slot:0/bundle:0"), paths(observations));
    }

    private static void collect(
            ItemStack item,
            Map<LoreItemIdentity, List<LocationDescriptor>> observations) {
        new PaperTrackedItemCollector().collectItem(
                item, LocationDescriptor.Type.BLOCK_CONTAINER,
                ROOT, "slot:0", observations, 0, new PaperScanLimit(256));
    }

    private static List<String> paths(
            Map<LoreItemIdentity, List<LocationDescriptor>> observations) {
        return observations.get(IDENTITY).stream()
                .map(LocationDescriptor::containerPath).toList();
    }

    private static ItemStack tracked() {
        return new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), IDENTITY);
    }

    private static final class CountingOrdinaryItem extends ItemStack {
        private int metadataReadCount;

        private CountingOrdinaryItem(Material type) {
            super(type);
        }

        @Override
        public ItemMeta getItemMeta() {
            metadataReadCount++;
            return super.getItemMeta();
        }

        int metadataReads() {
            return metadataReadCount;
        }

        void resetReads() {
            metadataReadCount = 0;
        }
    }
}
