package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

/** Checks that batched move detection preserves per-instance duplicate safety. */
class PaperPhysicalInventoryScannerBatchTest {
    private static final LoreItemIdentity FIRST = identity("22222222-2222-2222-2222-222222222222");
    private static final LoreItemIdentity SECOND = identity("33333333-3333-3333-3333-333333333333");
    private static final LoreItemIdentity UNRELATED = identity("44444444-4444-4444-4444-444444444444");
    private static final LoreItemIdentity MISSING = identity("55555555-5555-5555-5555-555555555555");
    private static final String KEY = "minecraft:overworld:10:64:10";

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
    void multipleIdentitiesUseOneSnapshotWithoutDroppingDuplicateEvidence() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        PaperPhysicalInventoryScanner scanner = scanner(observed);
        Inventory destination = server.createInventory(null, 9);
        destination.setItem(0, tracked(FIRST));
        destination.setItem(1, tracked(FIRST));
        destination.setItem(2, tracked(SECOND));
        destination.setItem(3, tracked(UNRELATED));
        AtomicInteger reads = new AtomicInteger();

        scanner.submitMatchingIdentities(
                countedInventory(destination, reads), LocationDescriptor.Type.BLOCK_CONTAINER,
                KEY, Set.of(FIRST, SECOND, MISSING), "inventory-move-destination");

        assertEquals(1, reads.get(), "Destination contents must be read once per move");
        assertEquals(3, observed.size());
        assertEquals(List.of("slot:0", "slot:1"), observed.stream()
                .filter(request -> FIRST.equals(request.identity()))
                .map(request -> request.location().containerPath()).toList());
        assertTrue(observed.stream().filter(request -> FIRST.equals(request.identity()))
                .allMatch(request -> request.mode()
                        == TrackingObservationUseCase.EvidenceMode.RECONCILIATION));
        assertTrue(observed.stream().filter(request -> SECOND.equals(request.identity()))
                .allMatch(request -> request.mode()
                        == TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION));
        assertTrue(observed.stream().noneMatch(request -> UNRELATED.equals(request.identity())
                || MISSING.equals(request.identity())));
    }

    @Test
    void nestedShulkerMatchesRemainDistinctFromRootCopies() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        PaperPhysicalInventoryScanner scanner = scanner(observed);
        Inventory destination = server.createInventory(null, 9);
        destination.setItem(4, shulkerWith(FIRST, SECOND));
        destination.setItem(7, tracked(SECOND));
        AtomicInteger reads = new AtomicInteger();

        scanner.submitMatchingIdentities(
                countedInventory(destination, reads), LocationDescriptor.Type.BLOCK_CONTAINER,
                KEY, Set.of(FIRST, SECOND), "hopper-pickup-destination");

        assertEquals(1, reads.get());
        assertEquals(3, observed.size());
        assertTrue(observed.stream().anyMatch(request -> FIRST.equals(request.identity())
                && request.location().containerPath().equals("slot:4/shulker:0")
                && request.mode() == TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION));
        assertEquals(List.of("slot:4/shulker:1", "slot:7"), observed.stream()
                .filter(request -> SECOND.equals(request.identity()))
                .map(request -> request.location().containerPath()).toList());
        assertTrue(observed.stream().filter(request -> SECOND.equals(request.identity()))
                .allMatch(request -> request.mode()
                        == TrackingObservationUseCase.EvidenceMode.RECONCILIATION));
    }

    @Test
    void emptyBatchDoesNotReadInventoryAndFutureMovesReadFreshContents() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        PaperPhysicalInventoryScanner scanner = scanner(observed);
        Inventory destination = server.createInventory(null, 9);
        destination.setItem(1, tracked(FIRST));
        AtomicInteger reads = new AtomicInteger();
        Inventory counted = countedInventory(destination, reads);

        scanner.submitMatchingIdentities(
                counted, LocationDescriptor.Type.BLOCK_CONTAINER, KEY,
                Set.of(), "empty-move");
        assertEquals(0, reads.get());

        scanner.submitMatchingIdentities(
                counted, LocationDescriptor.Type.BLOCK_CONTAINER, KEY,
                Set.of(FIRST), "first-move");
        destination.setItem(1, null);
        destination.setItem(6, tracked(FIRST));
        scanner.submitMatchingIdentities(
                counted, LocationDescriptor.Type.BLOCK_CONTAINER, KEY,
                Set.of(FIRST), "second-move");

        assertEquals(2, reads.get(), "No cross-event inventory snapshot may survive");
        assertEquals(List.of("slot:1", "slot:6"), observed.stream()
                .map(request -> request.location().containerPath()).toList());
    }

    private PaperPhysicalInventoryScanner scanner(List<TrackingObservationUseCase.Request> observed) {
        TrackingObservationUseCase useCase = request -> {
            observed.add(request);
            return CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                    TrackingObservationUseCase.Status.RECORDED, "ok"));
        };
        coordinator = new PaperTrackingCoordinator(
                plugin, () -> useCase, () -> 4, MetricsPort.noOp());
        return new PaperPhysicalInventoryScanner(coordinator);
    }

    private static Inventory countedInventory(Inventory delegate, AtomicInteger reads) {
        return (Inventory) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[] {Inventory.class},
                (proxy, method, args) -> {
                    if ("getContents".equals(method.getName())) {
                        reads.incrementAndGet();
                    }
                    try {
                        return method.invoke(delegate, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private static ItemStack shulkerWith(LoreItemIdentity first, LoreItemIdentity second) {
        ItemStack item = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta meta = assertInstanceOf(BlockStateMeta.class, item.getItemMeta());
        ShulkerBox box = assertInstanceOf(ShulkerBox.class, meta.getBlockState());
        box.getInventory().setItem(0, tracked(first));
        box.getInventory().setItem(1, tracked(second));
        meta.setBlockState(box);
        assertTrue(item.setItemMeta(meta));
        return item;
    }

    private static ItemStack tracked(LoreItemIdentity identity) {
        return new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), identity);
    }

    private static LoreItemIdentity identity(String instance) {
        return new LoreItemIdentity(
                new LoreDefinitionId(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                new LoreInstanceId(UUID.fromString(instance)),
                new TemplateRevision(1));
    }
}
