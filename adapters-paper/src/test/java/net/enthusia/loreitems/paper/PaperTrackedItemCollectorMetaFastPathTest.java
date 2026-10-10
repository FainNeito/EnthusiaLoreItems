package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.ItemIdentityFailure;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class PaperTrackedItemCollectorMetaFastPathTest {
    private final PaperTrackedItemCollector collector = new PaperTrackedItemCollector();
    private final PaperItemIdentityCodec codec = new PaperItemIdentityCodec();

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void metadataFreeOrdinaryItemsDoNotMaterializeItemMetaDuringEvidenceProbes() {
        // MockBukkit may report a default ItemMeta for ordinary items. This
        // test double explicitly models the Bukkit no-metadata contract.
        ItemStack ordinary = new ItemStack(Material.COBBLESTONE) {
            @Override
            public boolean hasItemMeta() {
                return false;
            }

            @Override
            public ItemMeta getItemMeta() {
                throw new AssertionError("Metadata-free item must not copy ItemMeta");
            }
        };
        assertFalse(ordinary.hasItemMeta());
        assertFalse(codec.hasIdentityEvidence(ordinary));
        assertInstanceOf(ItemIdentityReadResult.Untracked.class, codec.readIdentity(ordinary));
        assertNull(collector.trackedIdentity(ordinary));
        assertFalse(collector.hasIdentityEvidence(ordinary));
        assertFalse(collector.hasNestedIdentityEvidence(ordinary));
    }

    @Test
    void fullAndPartialIdentityEvidenceStillGetsInspected() {
        ItemStack tracked = trackedItem();
        assertTrue(codec.hasIdentityEvidence(tracked));
        assertTrue(collector.hasIdentityEvidence(tracked));
        assertFalse(collector.hasNestedIdentityEvidence(tracked));
    }

    @Test
    void malformedAndPartialMetadataEvidenceStillGetsDecoded() {
        ItemStack malformed = trackedItem();
        ItemMeta meta = malformed.getItemMeta();
        meta.getPersistentDataContainer().remove(
                NamespacedKey.fromString("enthusialoreitems:instance_id"));
        assertTrue(malformed.setItemMeta(meta));
        assertTrue(malformed.hasItemMeta());
        assertTrue(codec.hasIdentityEvidence(malformed));
        ItemIdentityReadResult.Invalid result = assertInstanceOf(
                ItemIdentityReadResult.Invalid.class, codec.readIdentity(malformed));
        assertEquals(ItemIdentityFailure.PARTIAL_DATA, result.failure());
    }

    @Test
    void metadataBackedNestedShulkerAndBundleRemainDiscoverable() {
        ItemStack shulker = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta meta = assertInstanceOf(BlockStateMeta.class, shulker.getItemMeta());
        ShulkerBox box = assertInstanceOf(ShulkerBox.class, meta.getBlockState());
        box.getInventory().setItem(0, trackedItem());
        meta.setBlockState(box);
        assertTrue(shulker.setItemMeta(meta));
        assertTrue(collector.hasNestedIdentityEvidence(shulker));
        assertTrue(collector.hasIdentityEvidence(shulker));

        ItemStack bundle = ItemStack.of(Material.BUNDLE);
        BundleMeta bundleMeta = assertInstanceOf(BundleMeta.class, bundle.getItemMeta());
        bundleMeta.setItems(List.of(ItemStack.of(Material.COBBLESTONE), trackedItem()));
        assertTrue(bundle.setItemMeta(bundleMeta));
        assertTrue(collector.hasNestedIdentityEvidence(bundle));
        assertTrue(collector.hasIdentityEvidence(bundle));
    }

    private ItemStack trackedItem() {
        return codec.writeIdentity(
                ItemStack.of(Material.DIAMOND),
                new LoreItemIdentity(
                        new LoreDefinitionId(UUID.fromString(
                                "11111111-1111-1111-1111-111111111111")),
                        new LoreInstanceId(UUID.fromString(
                                "22222222-2222-2222-2222-222222222222")),
                        new TemplateRevision(3)));
    }
}
