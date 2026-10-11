package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

class PaperPhysicalInventoryScannerNestedMoveTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString(
                    "11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString(
                    "22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    private ServerMock server;
    private PluginMock plugin;
    private PaperTrackingCoordinator coordinator;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        if (coordinator != null) {
            coordinator.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void uniqueNestedDestinationMatchIsAuthoritativeForHopperStyleMove() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        PaperPhysicalInventoryScanner scanner = scanner(observed);
        Inventory destination = server.createInventory(null, 9);
        destination.setItem(4, shulkerContaining(trackedItem()));

        scanner.submitMatchingIdentity(
                destination,
                LocationDescriptor.Type.BLOCK_CONTAINER,
                "minecraft:overworld:10:64:10",
                IDENTITY,
                "inventory-move-destination");

        assertEquals(1, observed.size());
        TrackingObservationUseCase.Request request = observed.getFirst();
        assertEquals(IDENTITY, request.identity());
        assertEquals(
                TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION,
                request.mode());
        assertEquals(LocationDescriptor.Type.NESTED_CONTAINER, request.location().type());
        assertEquals("slot:4/shulker:0", request.location().containerPath());
        assertTrue(request.location().locationKey().startsWith("root:BLOCK_CONTAINER:"));
    }

    @Test
    void denseNestedDestinationFindsDuplicateBeyondFormerLeafBudget() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        PaperPhysicalInventoryScanner scanner = scanner(observed);
        Inventory destination = server.createInventory(null, 18);
        destination.setItem(0, filledShulker(0));
        for (int slot = 1; slot < 9; slot++) {
            destination.setItem(slot, filledShulker(-1));
        }
        destination.setItem(9, filledShulker(26));

        scanner.submitMatchingIdentity(
                destination,
                LocationDescriptor.Type.BLOCK_CONTAINER,
                "minecraft:overworld:10:64:10",
                IDENTITY,
                "inventory-move-destination");

        assertEquals(2, observed.size());
        assertTrue(observed.stream().allMatch(request ->
                request.mode() == TrackingObservationUseCase.EvidenceMode.RECONCILIATION));
        assertEquals(
                List.of("slot:0/shulker:0", "slot:9/shulker:26"),
                observed.stream().map(request -> request.location().containerPath()).toList());
    }

    @Test
    void nestedCollectorReusesIdentityConfirmedMetadataSnapshot() {
        CountingShulkerItem item = countingShulkerContaining(trackedItem());
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new ConcurrentHashMap<>();

        new PaperTrackedItemCollector().collectItem(
                item,
                LocationDescriptor.Type.BLOCK_CONTAINER,
                "minecraft:overworld:10:64:10",
                "slot:0",
                observations,
                0,
                new PaperScanLimit(256));

        // Once for root identity decoding and once for nested evidence.
        // Previously a third snapshot was taken during nested collection.
        assertEquals(2, item.metadataReads());
        assertEquals(1, item.blockStateReads(), "Nested collection must reuse evidence snapshot");
        assertEquals(1, observations.get(IDENTITY).size());
        assertEquals(
                "slot:0/shulker:0",
                observations.get(IDENTITY).getFirst().containerPath());
    }

    @Test
    void physicalNestedScanReusesIdentityConfirmedMetadataSnapshot() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        PaperPhysicalInventoryScanner scanner = scanner(observed);
        CountingShulkerItem item = countingShulkerContaining(trackedItem());
        scanner.scanItemTree(
                item,
                new LocationDescriptor(
                        LocationDescriptor.Type.BLOCK_CONTAINER,
                        "minecraft:overworld:10:64:10",
                        "slot:0"),
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "nested-snapshot-regression",
                new PaperScanLimit(256));

        assertEquals(2, item.metadataReads());
        assertEquals(1, item.blockStateReads(), "Physical scanning must reuse evidence snapshot");
        assertEquals(1, observed.size());
        assertEquals(IDENTITY, observed.getFirst().identity());
        assertEquals("slot:0/shulker:0", observed.getFirst().location().containerPath());
    }

    @Test
    void negativeShulkerEvidenceDoesNotTriggerSecondDecode() {
        CountingShulkerItem item = countingShulkerContaining(ItemStack.of(Material.STONE));
        Map<LoreItemIdentity, List<LocationDescriptor>> observations = new ConcurrentHashMap<>();

        new PaperTrackedItemCollector().collectItem(
                item, LocationDescriptor.Type.BLOCK_CONTAINER,
                "minecraft:overworld:10:64:10", "slot:0",
                observations, 0, new PaperScanLimit(256));

        assertEquals(1, item.blockStateReads());
        assertTrue(observations.isEmpty());
    }

    private static CountingShulkerItem countingShulkerContaining(ItemStack nested) {
        CountingShulkerItem item = new CountingShulkerItem();
        BlockStateMeta meta = assertInstanceOf(BlockStateMeta.class, item.getItemMeta());
        ShulkerBox box = assertInstanceOf(ShulkerBox.class, meta.getBlockState());
        box.getInventory().setItem(0, nested);
        meta.setBlockState(box);
        assertTrue(item.setItemMeta(meta));
        item.clearMetadataReads();
        return item;
    }

    private static final class CountingShulkerItem extends ItemStack {
        private int metadataReadCount;
        private int blockStateReadCount;
        private boolean countBlockStateReads;

        CountingShulkerItem() {
            super(Material.SHULKER_BOX);
        }

        @Override
        public ItemMeta getItemMeta() {
            metadataReadCount++;
            ItemMeta meta = super.getItemMeta();
            if (!countBlockStateReads) {
                return meta;
            }
            return (ItemMeta) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(),
                    new Class<?>[] {BlockStateMeta.class},
                    (proxy, method, args) -> {
                        if ("getBlockState".equals(method.getName())) {
                            blockStateReadCount++;
                        }
                        try {
                            return method.invoke(meta, args);
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
        }

        int metadataReads() {
            return metadataReadCount;
        }

        int blockStateReads() {
            return blockStateReadCount;
        }

        void clearMetadataReads() {
            metadataReadCount = 0;
            blockStateReadCount = 0;
            countBlockStateReads = true;
        }
    }

    private PaperPhysicalInventoryScanner scanner(
            List<TrackingObservationUseCase.Request> observed) {
        TrackingObservationUseCase useCase = request -> {
            observed.add(request);
            return CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                    TrackingObservationUseCase.Status.RECORDED,
                    "ok"));
        };
        coordinator = new PaperTrackingCoordinator(
                plugin, () -> useCase, () -> 4, MetricsPort.noOp());
        return new PaperPhysicalInventoryScanner(coordinator);
    }

    private static ItemStack trackedItem() {
        return new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), IDENTITY);
    }

    private static ItemStack shulkerContaining(ItemStack nested) {
        ItemStack item = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta meta = assertInstanceOf(BlockStateMeta.class, item.getItemMeta());
        ShulkerBox shulker = assertInstanceOf(ShulkerBox.class, meta.getBlockState());
        shulker.getInventory().setItem(0, nested);
        meta.setBlockState(shulker);
        assertTrue(item.setItemMeta(meta));
        return item;
    }

    private static ItemStack filledShulker(int trackedSlot) {
        ItemStack item = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta meta = assertInstanceOf(BlockStateMeta.class, item.getItemMeta());
        ShulkerBox shulker = assertInstanceOf(ShulkerBox.class, meta.getBlockState());
        for (int slot = 0; slot < shulker.getInventory().getSize(); slot++) {
            shulker.getInventory().setItem(slot, ItemStack.of(Material.STONE));
        }
        if (trackedSlot >= 0) {
            shulker.getInventory().setItem(trackedSlot, trackedItem());
        }
        meta.setBlockState(shulker);
        assertTrue(item.setItemMeta(meta));
        return item;
    }
}
