package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.ItemIdentityFailure;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class PaperItemIdentityCodecTest {
    private static final NamespacedKey VERSION_KEY = key("identity_version");
    private static final NamespacedKey DEFINITION_KEY = key("definition_id");
    private static final NamespacedKey INSTANCE_KEY = key("instance_id");
    private static final NamespacedKey FOREIGN_KEY = key("foreign_test_value");
    private static final String PRESERVED_VALUE = "preserved";

    private PaperItemIdentityCodec codec;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        codec = new PaperItemIdentityCodec();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void roundTripPreservesVisibleAndForeignDataWithoutMutatingSource() {
        ItemStack source = ItemStack.of(Material.PAPER, 3);
        ItemMeta sourceMeta = Objects.requireNonNull(source.getItemMeta());
        sourceMeta.displayName(Component.text("Visible Lore Item"));
        sourceMeta.lore(List.of(Component.text("Visible lore remains unchanged")));
        sourceMeta.getPersistentDataContainer().set(
                FOREIGN_KEY, PersistentDataType.STRING, PRESERVED_VALUE);
        assertTrue(source.setItemMeta(sourceMeta));
        LoreItemIdentity identity = identity();

        ItemStack tracked = codec.writeIdentity(source, identity);

        assertNotSame(source, tracked);
        assertEquals(3, source.getAmount());
        assertEquals(64, source.getMaxStackSize());
        assertFalse(hasIdentityKey(source, VERSION_KEY));
        assertEquals(1, tracked.getAmount());
        assertEquals(1, tracked.getMaxStackSize());
        ItemMeta trackedMeta = Objects.requireNonNull(tracked.getItemMeta());
        assertEquals(Component.text("Visible Lore Item"), trackedMeta.displayName());
        assertEquals(List.of(Component.text("Visible lore remains unchanged")), trackedMeta.lore());
        assertEquals(
                PRESERVED_VALUE,
                trackedMeta.getPersistentDataContainer().get(
                        FOREIGN_KEY, PersistentDataType.STRING));

        ItemIdentityReadResult.Tracked result =
                assertInstanceOf(ItemIdentityReadResult.Tracked.class, codec.readIdentity(tracked));
        assertEquals(identity, result.identity());
    }

    @Test
    void clearIdentityRemovesOnlyLoreItemsFields() {
        ItemStack tracked = codec.writeIdentity(ItemStack.of(Material.COMPASS), identity());
        ItemMeta meta = Objects.requireNonNull(tracked.getItemMeta());
        meta.getPersistentDataContainer().set(
                FOREIGN_KEY, PersistentDataType.STRING, PRESERVED_VALUE);
        assertTrue(tracked.setItemMeta(meta));

        ItemStack cleared = codec.clearIdentity(tracked);

        assertInstanceOf(ItemIdentityReadResult.Untracked.class, codec.readIdentity(cleared));
        ItemMeta clearedMeta = Objects.requireNonNull(cleared.getItemMeta());
        assertEquals(
                PRESERVED_VALUE,
                clearedMeta.getPersistentDataContainer().get(
                        FOREIGN_KEY, PersistentDataType.STRING));
        assertInstanceOf(ItemIdentityReadResult.Tracked.class, codec.readIdentity(tracked));
    }

    @Test
    void partialIdentityFailsClosed() {
        ItemStack tracked = codec.writeIdentity(ItemStack.of(Material.DIAMOND), identity());
        ItemMeta meta = Objects.requireNonNull(tracked.getItemMeta());
        meta.getPersistentDataContainer().remove(INSTANCE_KEY);
        assertTrue(tracked.setItemMeta(meta));

        ItemIdentityReadResult.Invalid result =
                assertInstanceOf(ItemIdentityReadResult.Invalid.class, codec.readIdentity(tracked));
        assertEquals(ItemIdentityFailure.PARTIAL_DATA, result.failure());
        assertNull(result.identityEvidence());
    }

    @Test
    void missingVersionRetainsRecoverableIdentityEvidence() {
        LoreItemIdentity expectedIdentity = identity();
        ItemStack tracked = codec.writeIdentity(ItemStack.of(Material.DIAMOND), expectedIdentity);
        ItemMeta meta = Objects.requireNonNull(tracked.getItemMeta());
        meta.getPersistentDataContainer().remove(VERSION_KEY);
        assertTrue(tracked.setItemMeta(meta));

        ItemIdentityReadResult.Invalid result =
                assertInstanceOf(ItemIdentityReadResult.Invalid.class, codec.readIdentity(tracked));
        assertEquals(ItemIdentityFailure.PARTIAL_DATA, result.failure());
        assertEquals(expectedIdentity, result.identityEvidence());
    }

    @Test
    void unsupportedAndMalformedIdentityFailClosed() {
        LoreItemIdentity expectedIdentity = identity();
        ItemStack unsupported = codec.writeIdentity(ItemStack.of(Material.DIAMOND), expectedIdentity);
        setInteger(unsupported, VERSION_KEY, PaperItemIdentityCodec.CURRENT_VERSION + 1);
        ItemIdentityReadResult.Invalid unsupportedResult =
                assertInstanceOf(ItemIdentityReadResult.Invalid.class, codec.readIdentity(unsupported));
        assertEquals(ItemIdentityFailure.UNSUPPORTED_VERSION, unsupportedResult.failure());
        assertEquals(expectedIdentity, unsupportedResult.identityEvidence());

        ItemStack malformed = codec.writeIdentity(ItemStack.of(Material.DIAMOND), expectedIdentity);
        ItemMeta malformedMeta = Objects.requireNonNull(malformed.getItemMeta());
        malformedMeta.getPersistentDataContainer().set(
                DEFINITION_KEY, PersistentDataType.BYTE_ARRAY, new byte[] {1});
        assertTrue(malformed.setItemMeta(malformedMeta));
        ItemIdentityReadResult.Invalid malformedResult =
                assertInstanceOf(ItemIdentityReadResult.Invalid.class, codec.readIdentity(malformed));
        assertEquals(ItemIdentityFailure.MALFORMED_DATA, malformedResult.failure());
        assertNull(malformedResult.identityEvidence());
    }

    @Test
    void stackingViolationIsPreservedAsInvalidEvidence() {
        LoreItemIdentity expectedIdentity = identity();
        ItemStack tracked = codec.writeIdentity(ItemStack.of(Material.PAPER), expectedIdentity);
        tracked.setAmount(2);

        ItemIdentityReadResult.Invalid result =
                assertInstanceOf(ItemIdentityReadResult.Invalid.class, codec.readIdentity(tracked));
        assertEquals(ItemIdentityFailure.STACKING_VIOLATION, result.failure());
        assertEquals(expectedIdentity, result.identityEvidence());
        assertEquals(2, tracked.getAmount());
    }

    @Test
    void rawEvidenceCheckDistinguishesAirAndOrdinaryItemsFromPartialIdentity() {
        assertFalse(codec.hasIdentityEvidence(null));
        assertFalse(codec.hasIdentityEvidence(ItemStack.empty()));
        assertFalse(codec.hasIdentityEvidence(ItemStack.of(Material.PAPER)));

        ItemStack tracked = codec.writeIdentity(ItemStack.of(Material.PAPER), identity());
        assertTrue(codec.hasIdentityEvidence(tracked));
        ItemMeta meta = Objects.requireNonNull(tracked.getItemMeta());
        meta.getPersistentDataContainer().remove(INSTANCE_KEY);
        assertTrue(tracked.setItemMeta(meta));
        assertTrue(codec.hasIdentityEvidence(tracked));
    }

    @Test
    void foreignMetadataDoesNotProduceIdentityEvidence() {
        ItemStack item = ItemStack.of(Material.DIAMOND);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta());
        meta.getPersistentDataContainer().set(
                FOREIGN_KEY, PersistentDataType.STRING, "unrelated");
        assertTrue(item.setItemMeta(meta));

        assertFalse(codec.hasIdentityEvidence(item));
        assertInstanceOf(ItemIdentityReadResult.Untracked.class, codec.readIdentity(item));
    }

    @Test
    void typeInvalidIdentityKeysRemainEvidenceAndFailClosed() {
        LoreItemIdentity expected = identity();
        ItemStack tracked = codec.writeIdentity(ItemStack.of(Material.COMPASS), expected);
        ItemMeta meta = Objects.requireNonNull(tracked.getItemMeta());
        meta.getPersistentDataContainer().set(
                VERSION_KEY, PersistentDataType.STRING, "not-a-version");
        assertTrue(tracked.setItemMeta(meta));

        assertTrue(codec.hasIdentityEvidence(tracked));
        ItemIdentityReadResult.Invalid badVersion =
                assertInstanceOf(ItemIdentityReadResult.Invalid.class, codec.readIdentity(tracked));
        assertEquals(ItemIdentityFailure.MALFORMED_DATA, badVersion.failure());
        assertEquals(expected, badVersion.identityEvidence());

        ItemMeta missingMeta = Objects.requireNonNull(tracked.getItemMeta());
        missingMeta.getPersistentDataContainer().remove(INSTANCE_KEY);
        assertTrue(tracked.setItemMeta(missingMeta));

        ItemIdentityReadResult.Invalid partial =
                assertInstanceOf(ItemIdentityReadResult.Invalid.class, codec.readIdentity(tracked));
        assertEquals(ItemIdentityFailure.PARTIAL_DATA, partial.failure());
        assertTrue(codec.hasIdentityEvidence(tracked));
    }

    @Test
    void hotIdentityReadsUseItemStackViewWithoutCreatingMetaSnapshots() {
        ItemStack original = codec.writeIdentity(ItemStack.of(Material.COMPASS), identity());
        CountingItemStack tracked = new CountingItemStack(Material.COMPASS);
        tracked.setAmount(original.getAmount());
        assertTrue(tracked.setItemMeta(Objects.requireNonNull(original.getItemMeta())));
        tracked.resetReads();

        assertTrue(codec.hasIdentityEvidence(tracked));
        ItemIdentityReadResult.Tracked result =
                assertInstanceOf(ItemIdentityReadResult.Tracked.class, codec.readIdentity(tracked));
        assertEquals(identity(), result.identity());
        assertEquals(0, tracked.metadataReads());

        CountingItemStack ordinary = new CountingItemStack(Material.COMPASS);
        ItemMeta ordinaryMeta = Objects.requireNonNull(ordinary.getItemMeta());
        ordinaryMeta.getPersistentDataContainer().set(
                FOREIGN_KEY, PersistentDataType.STRING, "third-party");
        assertTrue(ordinary.setItemMeta(ordinaryMeta));
        ordinary.resetReads();

        assertFalse(codec.hasIdentityEvidence(ordinary));
        assertInstanceOf(ItemIdentityReadResult.Untracked.class, codec.readIdentity(ordinary));
        assertEquals(0, ordinary.metadataReads());
    }

    @Test
    void rejectsPaperAccessWhenNotOnPrimaryThread() {
        PaperItemIdentityCodec guarded =
                new PaperItemIdentityCodec(new PaperItemCodecThreadGuard(() -> false));

        assertThrows(
                IllegalStateException.class,
                () -> guarded.readIdentity(ItemStack.of(Material.PAPER)));
        assertThrows(
                IllegalStateException.class,
                () -> guarded.writeIdentity(ItemStack.of(Material.PAPER), identity()));
        assertThrows(
                IllegalStateException.class,
                () -> guarded.hasIdentityEvidence(ItemStack.of(Material.PAPER)));
    }

    private static final class CountingItemStack extends ItemStack {
        private int metadataReadCount;

        private CountingItemStack(Material type) {
            super(type);
        }

        @Override
        public ItemMeta getItemMeta() {
            metadataReadCount++;
            return super.getItemMeta();
        }

        private void resetReads() {
            metadataReadCount = 0;
        }

        private int metadataReads() {
            return metadataReadCount;
        }
    }

    private static LoreItemIdentity identity() {
        return new LoreItemIdentity(
                new LoreDefinitionId(UUID.fromString(
                        "11111111-1111-1111-1111-111111111111")),
                new LoreInstanceId(UUID.fromString(
                        "22222222-2222-2222-2222-222222222222")),
                new TemplateRevision(7));
    }

    private static boolean hasIdentityKey(ItemStack item, NamespacedKey key) {
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta());
        return meta.getPersistentDataContainer().getKeys().contains(key);
    }

    private static void setInteger(ItemStack item, NamespacedKey key, int value) {
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta());
        meta.getPersistentDataContainer().set(key, PersistentDataType.INTEGER, value);
        assertTrue(item.setItemMeta(meta));
    }

    private static NamespacedKey key(String value) {
        return Objects.requireNonNull(
                NamespacedKey.fromString("enthusialoreitems:" + value));
    }
}
