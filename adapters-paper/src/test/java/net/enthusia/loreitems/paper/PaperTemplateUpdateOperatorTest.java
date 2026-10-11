package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.PreparedTemplateUpdate;
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
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperTemplateUpdateOperatorTest {
    private static final String NEW_BLADE = "New Blade";
    private static final Component SIGNATURE_LINE =
            Component.text("Signed by Alice").insertion("itemsignature:managed");
    private static final org.bukkit.NamespacedKey SIGNER =
            new org.bukkit.NamespacedKey("itemsignature", "signer_name");
    private static final org.bukkit.NamespacedKey SIGNED_AT =
            new org.bukkit.NamespacedKey("itemsignature", "signed_at");
    private static final LoreDefinitionId DEFINITION_ID = new LoreDefinitionId(
            UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final LoreInstanceId INSTANCE_ID = new LoreInstanceId(
            UUID.fromString("22222222-2222-2222-2222-222222222222"));
    private static final TemplateRevision REVISION_ONE = new TemplateRevision(1L);
    private static final TemplateRevision REVISION_TWO = new TemplateRevision(2L);

    private PlayerMock player;
    private Plugin plugin;
    private PaperItemTemplateCodec templateCodec;
    private PaperItemIdentityCodec identityCodec;
    private PaperTemplateUpdateOperator operator;

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        player = server.addPlayer();
        plugin = MockBukkit.createMockPlugin();
        templateCodec = new PaperItemTemplateCodec();
        identityCodec = new PaperItemIdentityCodec();
        operator = new PaperTemplateUpdateOperator();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void replacesVisibleTemplateWhilePreservingTheHiddenInstanceIdentity() {
        ItemStack original = tracked(named(Material.DIAMOND_SWORD, "Old Blade"), REVISION_ONE);
        player.getInventory().setItem(0, original);
        PreparedTemplateUpdate update = prepared(named(Material.DIAMOND_SWORD, NEW_BLADE));

        PaperTemplateUpdateOperator.ApplyResult result = operator.apply(
                plugin,
                PaperTemplateUpdateItemReference.root(
                        new PaperInventoryReference.PlayerMain(player.getUniqueId()), 0),
                update);

        assertEquals(PaperTemplateUpdateOperator.ApplyResult.Status.APPLIED, result.status());
        ItemStack stored = Objects.requireNonNull(player.getInventory().getItem(0));
        assertEquals(Component.text(NEW_BLADE), stored.getItemMeta().displayName());
        assertEquals(targetIdentity(), trackedIdentity(stored));
        assertEquals(64, result.beforeFingerprint().length());
        assertEquals(64, result.afterFingerprint().length());
    }

    @Test
    void verifiesAnAlreadyAppliedTargetDuringCrashRecovery() {
        ItemStack target = tracked(named(Material.DIAMOND_SWORD, NEW_BLADE), REVISION_TWO);
        player.getInventory().setItem(0, target);

        PaperTemplateUpdateOperator.ApplyResult result = operator.apply(
                plugin,
                PaperTemplateUpdateItemReference.root(
                        new PaperInventoryReference.PlayerMain(player.getUniqueId()), 0),
                prepared(named(Material.DIAMOND_SWORD, NEW_BLADE)));

        assertEquals(
                PaperTemplateUpdateOperator.ApplyResult.Status.ALREADY_APPLIED,
                result.status());
        assertEquals(result.beforeFingerprint(), result.afterFingerprint());
    }

    @Test
    void identityMismatchRequiresReviewWithoutReplacingTheItem() {
        LoreItemIdentity otherIdentity = new LoreItemIdentity(
                DEFINITION_ID,
                new LoreInstanceId(UUID.randomUUID()),
                REVISION_ONE);
        ItemStack other = identityCodec.writeIdentity(
                named(Material.DIAMOND_SWORD, "Other Blade"), otherIdentity);
        player.getInventory().setItem(0, other);
        String before = PaperItemFingerprint.of(other);

        PaperTemplateUpdateOperator.ApplyResult result = operator.apply(
                plugin,
                PaperTemplateUpdateItemReference.root(
                        new PaperInventoryReference.PlayerMain(player.getUniqueId()), 0),
                prepared(named(Material.DIAMOND_SWORD, NEW_BLADE)));

        assertEquals(
                PaperTemplateUpdateOperator.ApplyResult.Status.REVIEW_REQUIRED,
                result.status());
        assertEquals(before, PaperItemFingerprint.of(
                Objects.requireNonNull(player.getInventory().getItem(0))));
    }

    @Test
    void updatesALoreItemNestedInsideAShulkerWithoutReplacingItsParent() {
        ItemStack parent = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta meta = assertInstanceOf(BlockStateMeta.class, parent.getItemMeta());
        ShulkerBox shulker = assertInstanceOf(ShulkerBox.class, meta.getBlockState());
        shulker.getInventory().setItem(
                3,
                tracked(named(Material.DIAMOND_SWORD, "Old Blade"), REVISION_ONE));
        shulker.getInventory().setItem(4, ItemStack.of(Material.EMERALD, 7));
        meta.setBlockState(shulker);
        assertTrue(parent.setItemMeta(meta));
        player.getInventory().setItem(0, parent);
        PaperTemplateUpdateItemReference reference = PaperTemplateUpdateItemReference.root(
                        new PaperInventoryReference.PlayerMain(player.getUniqueId()), 0)
                .nested(PaperTemplateUpdateItemReference.NestedStep.shulker(3));

        PaperTemplateUpdateOperator.ApplyResult result = operator.apply(
                plugin, reference, prepared(named(Material.DIAMOND_SWORD, NEW_BLADE)));

        assertEquals(PaperTemplateUpdateOperator.ApplyResult.Status.APPLIED, result.status());
        ItemStack storedParent = Objects.requireNonNull(player.getInventory().getItem(0));
        BlockStateMeta storedMeta = assertInstanceOf(
                BlockStateMeta.class, storedParent.getItemMeta());
        ShulkerBox storedShulker = assertInstanceOf(
                ShulkerBox.class, storedMeta.getBlockState());
        ItemStack storedLoreItem = Objects.requireNonNull(
                storedShulker.getInventory().getItem(3));
        assertEquals(Component.text(NEW_BLADE), storedLoreItem.getItemMeta().displayName());
        assertEquals(targetIdentity(), trackedIdentity(storedLoreItem));
        assertEquals(7, Objects.requireNonNull(
                storedShulker.getInventory().getItem(4)).getAmount());
    }

    @Test
    void preservesNonEmptyShulkerContentsWhenTheLoreItemItselfIsUpdated() {
        ItemStack current = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta currentMeta = assertInstanceOf(
                BlockStateMeta.class, current.getItemMeta());
        currentMeta.displayName(Component.text("Old Box"));
        ShulkerBox currentBox = assertInstanceOf(
                ShulkerBox.class, currentMeta.getBlockState());
        currentBox.getInventory().setItem(5, ItemStack.of(Material.DIAMOND, 3));
        currentMeta.setBlockState(currentBox);
        assertTrue(current.setItemMeta(currentMeta));
        player.getInventory().setItem(0, tracked(current, REVISION_ONE));

        ItemStack desired = ItemStack.of(Material.SHULKER_BOX);
        ItemMeta desiredMeta = Objects.requireNonNull(desired.getItemMeta());
        desiredMeta.displayName(Component.text("New Box"));
        assertTrue(desired.setItemMeta(desiredMeta));

        PaperTemplateUpdateOperator.ApplyResult result = operator.apply(
                plugin,
                PaperTemplateUpdateItemReference.root(
                        new PaperInventoryReference.PlayerMain(player.getUniqueId()), 0),
                prepared(desired));

        assertEquals(PaperTemplateUpdateOperator.ApplyResult.Status.APPLIED, result.status());
        ItemStack stored = Objects.requireNonNull(player.getInventory().getItem(0));
        BlockStateMeta storedMeta = assertInstanceOf(BlockStateMeta.class, stored.getItemMeta());
        ShulkerBox storedBox = assertInstanceOf(ShulkerBox.class, storedMeta.getBlockState());
        assertEquals(3, Objects.requireNonNull(
                storedBox.getInventory().getItem(5)).getAmount());
        assertEquals(Component.text("New Box"), storedMeta.displayName());
    }

    @Test
    void updatesALoreItemNestedInsideABundle() {
        ItemStack bundle = ItemStack.of(Material.BUNDLE);
        BundleMeta bundleMeta = assertInstanceOf(BundleMeta.class, bundle.getItemMeta());
        bundleMeta.setItems(List.of(
                ItemStack.of(Material.GOLD_INGOT),
                tracked(named(Material.IRON_SWORD, "Old Blade"), REVISION_ONE)));
        assertTrue(bundle.setItemMeta(bundleMeta));
        player.getInventory().setItem(0, bundle);
        PaperTemplateUpdateItemReference reference = PaperTemplateUpdateItemReference.root(
                        new PaperInventoryReference.PlayerMain(player.getUniqueId()), 0)
                .nested(PaperTemplateUpdateItemReference.NestedStep.bundle(1));

        PaperTemplateUpdateOperator.ApplyResult result = operator.apply(
                plugin, reference, prepared(named(Material.IRON_SWORD, NEW_BLADE)));

        assertEquals(
                PaperTemplateUpdateOperator.ApplyResult.Status.APPLIED,
                result.status(),
                result.detail());
        BundleMeta storedMeta = assertInstanceOf(
                BundleMeta.class,
                Objects.requireNonNull(player.getInventory().getItem(0)).getItemMeta());
        assertEquals(Material.GOLD_INGOT, storedMeta.getItems().getFirst().getType());
        ItemStack storedLoreItem = storedMeta.getItems().get(1);
        assertEquals(Component.text(NEW_BLADE), storedLoreItem.getItemMeta().displayName());
        assertEquals(targetIdentity(), trackedIdentity(storedLoreItem));
    }

    @Test
    void templateUpdateKeepsAnEnthusiaSignature() {
        ItemStack original = tracked(signed(withLore(named(Material.DIAMOND_SWORD, "Old Blade"), "Old lore")), REVISION_ONE);
        player.getInventory().setItem(0, original);

        PaperTemplateUpdateOperator.ApplyResult result = operator.apply(
                plugin,
                PaperTemplateUpdateItemReference.root(
                        new PaperInventoryReference.PlayerMain(player.getUniqueId()), 0),
                prepared(withLore(named(Material.DIAMOND_SWORD, NEW_BLADE), "New lore")));

        assertEquals(PaperTemplateUpdateOperator.ApplyResult.Status.APPLIED, result.status());
        ItemMeta stored = Objects.requireNonNull(player.getInventory().getItem(0)).getItemMeta();
        assertEquals(Component.text(NEW_BLADE), stored.displayName());
        assertEquals(List.of(Component.text("New lore"), SIGNATURE_LINE), stored.lore());
        assertEquals("Alice", stored.getPersistentDataContainer().get(SIGNER, PersistentDataType.STRING));
        assertEquals(1_700_000_000_000L, stored.getPersistentDataContainer().get(SIGNED_AT, PersistentDataType.LONG));
    }

    @Test
    void signedItemAlreadyAtTheTargetRevisionIsRecognisedAsApplied() {
        ItemStack target = tracked(signed(withLore(named(Material.DIAMOND_SWORD, NEW_BLADE), "New lore")), REVISION_TWO);
        player.getInventory().setItem(0, target);

        PaperTemplateUpdateOperator.ApplyResult result = operator.apply(
                plugin,
                PaperTemplateUpdateItemReference.root(
                        new PaperInventoryReference.PlayerMain(player.getUniqueId()), 0),
                prepared(withLore(named(Material.DIAMOND_SWORD, NEW_BLADE), "New lore")));

        assertEquals(PaperTemplateUpdateOperator.ApplyResult.Status.ALREADY_APPLIED, result.status());
    }

    private static ItemStack withLore(ItemStack item, String line) {
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta());
        meta.lore(List.of(Component.text(line)));
        assertTrue(item.setItemMeta(meta));
        return item;
    }

    private static ItemStack signed(ItemStack item) {
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta());
        List<Component> lore = new java.util.ArrayList<>(Objects.requireNonNull(meta.lore()));
        lore.add(SIGNATURE_LINE);
        meta.lore(lore);
        meta.getPersistentDataContainer().set(SIGNER, PersistentDataType.STRING, "Alice");
        meta.getPersistentDataContainer().set(SIGNED_AT, PersistentDataType.LONG, 1_700_000_000_000L);
        assertTrue(item.setItemMeta(meta));
        return item;
    }

    private PreparedTemplateUpdate prepared(ItemStack desiredTemplate) {
        return new PreparedTemplateUpdate(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "claim-token",
                observedIdentity(),
                targetIdentity(),
                templateCodec.encode(desiredTemplate),
                31_000L);
    }

    private ItemStack tracked(ItemStack item, TemplateRevision revision) {
        return identityCodec.writeIdentity(
                item,
                new LoreItemIdentity(DEFINITION_ID, INSTANCE_ID, revision));
    }

    private LoreItemIdentity trackedIdentity(ItemStack item) {
        ItemIdentityReadResult.Tracked tracked = assertInstanceOf(
                ItemIdentityReadResult.Tracked.class,
                identityCodec.readIdentity(item));
        return tracked.identity();
    }

    private static ItemStack named(Material material, String name) {
        ItemStack item = ItemStack.of(material);
        ItemMeta meta = Objects.requireNonNull(item.getItemMeta());
        meta.displayName(Component.text(name));
        assertTrue(item.setItemMeta(meta));
        return item;
    }

    private static LoreItemIdentity observedIdentity() {
        return new LoreItemIdentity(DEFINITION_ID, INSTANCE_ID, REVISION_ONE);
    }

    private static LoreItemIdentity targetIdentity() {
        return new LoreItemIdentity(DEFINITION_ID, INSTANCE_ID, REVISION_TWO);
    }
}
