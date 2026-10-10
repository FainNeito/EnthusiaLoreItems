package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperTemplateUpdateScannerTest {
    private static final LoreItemIdentity TARGET_IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(
                    UUID.fromString("11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(
                    UUID.fromString("22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1L));

    private PlayerMock player;
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        ServerMock server = MockBukkit.mock();
        player = server.addPlayer();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void continuationFindsALateNestedItemWithoutSubmittingAPartialScan() {
        populateShulkers(false);
        PaperTemplateUpdateScanner scanner = new PaperTemplateUpdateScanner();
        List<PaperTemplateUpdateScanner.Candidate> candidates = new ArrayList<>();

        PaperTemplateUpdateScanner.ScanResult first = scanner.scan(
                plugin, player.getInventory(), candidates::add);
        assertTrue(first.continuationRequired());
        assertFalse(first.abandoned());
        assertTrue(candidates.isEmpty());

        PaperTemplateUpdateScanner.ScanResult second = scanner.scan(
                plugin, player.getInventory(), candidates::add);
        assertFalse(second.continuationRequired());
        assertFalse(second.abandoned());
        assertEquals(1, second.submitted());
        assertEquals(List.of(TARGET_IDENTITY), candidates.stream()
                .map(PaperTemplateUpdateScanner.Candidate::identity)
                .toList());
    }

    @Test
    void duplicateIdentitiesAcrossContinuationPassesAreReportedToTheAccessFence() {
        populateShulkers(true);
        PaperTemplateUpdateScanner scanner = new PaperTemplateUpdateScanner();
        List<PaperTemplateUpdateScanner.Candidate> candidates = new ArrayList<>();

        PaperTemplateUpdateScanner.ScanResult first = scanner.scan(
                plugin, player.getInventory(), candidates::add);
        PaperTemplateUpdateScanner.ScanResult second = scanner.scan(
                plugin, player.getInventory(), candidates::add);

        assertTrue(first.continuationRequired());
        assertFalse(second.continuationRequired());
        assertEquals(2, second.submitted());
        assertEquals(2, candidates.size());
        assertTrue(candidates.stream().allMatch(candidate ->
                TARGET_IDENTITY.equals(candidate.identity())));
    }

    @Test
    void discoveryAvoidsThirtyPerItemPlayerInventoryResolutions() {
        int itemCount = 30;
        PaperItemIdentityCodec codec = new PaperItemIdentityCodec();
        for (int slot = 0; slot < itemCount; slot++) {
            ItemStack item = ItemStack.of(Material.COBBLESTONE);
            if (slot == itemCount - 1) {
                item = codec.writeIdentity(item, TARGET_IDENTITY);
            }
            player.getInventory().setItem(slot, item);
        }

        AtomicInteger serverLookups = new AtomicInteger();
        Plugin countedPlugin = (Plugin) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Plugin.class},
                (proxy, method, arguments) -> {
                    if ("getServer".equals(method.getName())) {
                        serverLookups.incrementAndGet();
                        return plugin.getServer();
                    }
                    throw new AssertionError("Unexpected plugin method: " + method.getName());
                });

        PaperInventoryReference.PlayerMain reference =
                new PaperInventoryReference.PlayerMain(player.getUniqueId());
        // Exercise exactly the per-item resolution operation formerly called
        // by processPass, to establish its deterministic lookup count.
        for (int slot = 0; slot < itemCount; slot++) {
            assertTrue(PaperTemplateUpdateItemReference.root(reference, slot)
                    .resolve(countedPlugin).isPresent());
        }
        assertEquals(itemCount, serverLookups.get());

        serverLookups.set(0);
        List<PaperTemplateUpdateScanner.Candidate> found = new ArrayList<>();
        PaperTemplateUpdateScanner.ScanResult result =
                new PaperTemplateUpdateScanner().scan(
                        countedPlugin, player.getInventory(), found::add);

        assertFalse(result.abandoned());
        assertFalse(result.continuationRequired());
        assertEquals(1, result.submitted());
        assertEquals(TARGET_IDENTITY, found.getFirst().identity());
        // The current pass uses its supplied inventory, not Plugin.getServer()
        // for every node. These are API-lookup counts, NOT a TPS benchmark.
        assertEquals(0, serverLookups.get());
    }

    @Test
    void discoversBlockContainerItemsWithoutReopeningTheBlockForEveryChild() {
        World world = ((ServerMock) plugin.getServer()).addSimpleWorld("scan-world");
        world.getChunkAt(0, 0);
        world.getBlockAt(4, 64, 4).setType(Material.CHEST);
        Inventory chest = assertInstanceOf(
                Container.class, world.getBlockAt(4, 64, 4).getState()).getInventory();
        assertInstanceOf(
                PaperInventoryReference.Block.class,
                PaperInventoryReference.capture(chest).orElseThrow());
        chest.setItem(0, new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.DIAMOND), TARGET_IDENTITY));
        chest.setItem(1, ItemStack.of(Material.COBBLESTONE));

        // An inventory is supplied by the controller for this pass. Scanning its
        // children must not resolve the block through Plugin.getServer() again.
        Plugin forbidAdditionalResolution = (Plugin) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Plugin.class},
                (proxy, method, arguments) -> {
                    throw new AssertionError("Unexpected plugin lookup: " + method.getName());
                });
        PaperTemplateUpdateScanner scanner = new PaperTemplateUpdateScanner();
        List<PaperTemplateUpdateScanner.Candidate> candidates = new ArrayList<>();

        PaperTemplateUpdateScanner.ScanResult result =
                scanner.scan(forbidAdditionalResolution, chest, candidates::add);

        assertFalse(result.continuationRequired());
        assertFalse(result.abandoned());
        assertEquals(1, result.submitted());
        assertEquals(TARGET_IDENTITY, candidates.getFirst().identity());
        // Discovery retains a reload-safe block reference rather than a captured
        // Inventory. Mutation-time code will independently resolve the live world.
        assertInstanceOf(
                PaperTemplateUpdateItemReference.class, candidates.getFirst().reference());
        assertEquals(
                "LOADED_BLOCK_INVENTORY",
                candidates.getFirst().reference().destructiveLocation().locationType());
        assertEquals(
                "slot=0", candidates.getFirst().reference().destructiveLocation().containerPath());
    }

    @Test
    void continuationUsesCurrentInventoryContentsNotStaleShulkerSnapshots() {
        populateShulkers(false);
        PaperTemplateUpdateScanner scanner = new PaperTemplateUpdateScanner();
        List<PaperTemplateUpdateScanner.Candidate> candidates = new ArrayList<>();

        PaperTemplateUpdateScanner.ScanResult first =
                scanner.scan(plugin, player.getInventory(), candidates::add);
        assertTrue(first.continuationRequired());
        assertTrue(candidates.isEmpty());

        ItemStack replacement = player.getInventory().getItem(9).clone();
        BlockStateMeta meta = assertInstanceOf(
                BlockStateMeta.class, replacement.getItemMeta());
        ShulkerBox shulker = assertInstanceOf(
                ShulkerBox.class, meta.getBlockState());
        shulker.getInventory().setItem(26, ItemStack.of(Material.COBBLESTONE));
        meta.setBlockState(shulker);
        assertTrue(replacement.setItemMeta(meta));
        player.getInventory().setItem(9, replacement);

        PaperTemplateUpdateScanner.ScanResult second =
                scanner.scan(plugin, player.getInventory(), candidates::add);
        assertFalse(second.continuationRequired());
        assertFalse(second.abandoned());
        assertEquals(0, second.submitted());
        assertTrue(candidates.isEmpty());
    }

    @Test
    void discoversTrackedItemsInsideBundlesWithResolvableReferences() {
        ItemStack bundle = ItemStack.of(Material.BUNDLE);
        BundleMeta meta = assertInstanceOf(BundleMeta.class, bundle.getItemMeta());
        meta.setItems(List.of(
                ItemStack.of(Material.COBBLESTONE),
                new PaperItemIdentityCodec().writeIdentity(
                        ItemStack.of(Material.DIAMOND), TARGET_IDENTITY)));
        assertTrue(bundle.setItemMeta(meta));
        player.getInventory().setItem(0, bundle);

        List<PaperTemplateUpdateScanner.Candidate> candidates = new ArrayList<>();
        PaperTemplateUpdateScanner.ScanResult result = new PaperTemplateUpdateScanner()
                .scan(plugin, player.getInventory(), candidates::add);

        assertFalse(result.continuationRequired());
        assertFalse(result.abandoned());
        assertEquals(1, result.submitted());
        assertEquals(TARGET_IDENTITY, candidates.getFirst().identity());
        assertTrue(candidates.getFirst().reference().resolve(plugin).isPresent());
    }

    private void populateShulkers(boolean includeEarlyDuplicate) {
        PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();
        // Ten roots plus 270 nested items intentionally exceed the 256-item pass limit.
        for (int rootSlot = 0; rootSlot < 10; rootSlot++) {
            ItemStack shulkerItem = ItemStack.of(Material.SHULKER_BOX);
            BlockStateMeta meta = assertInstanceOf(
                    BlockStateMeta.class, shulkerItem.getItemMeta());
            ShulkerBox shulker = assertInstanceOf(
                    ShulkerBox.class, meta.getBlockState());
            for (int nestedSlot = 0; nestedSlot < shulker.getInventory().getSize(); nestedSlot++) {
                ItemStack nested = ItemStack.of(Material.COBBLESTONE);
                boolean lateTarget = rootSlot == 9 && nestedSlot == 26;
                boolean earlyDuplicate = includeEarlyDuplicate
                        && rootSlot == 0
                        && nestedSlot == 0;
                if (lateTarget || earlyDuplicate) {
                    nested = identityCodec.writeIdentity(nested, TARGET_IDENTITY);
                }
                shulker.getInventory().setItem(nestedSlot, nested);
            }
            meta.setBlockState(shulker);
            assertTrue(shulkerItem.setItemMeta(meta));
            player.getInventory().setItem(rootSlot, shulkerItem);
        }
    }
}
