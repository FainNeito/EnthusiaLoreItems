package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.plugin.PluginMock;

class PaperPhysicalTrackingListenerSlotChangeTest {
    private static final String SLOT_CHANGE_SOURCE = "inventory-slot-change";
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString(
                    "11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString(
                    "22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    private ServerMock server;
    private PluginMock plugin;
    private PaperPhysicalTrackingListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        if (listener != null) {
            listener.close();
        }
        MockBukkit.unmock();
    }

    @Test
    void slotChangeRescansCanonicalEquipmentLocationInsteadOfTrustingEventSlot() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        TrackingObservationUseCase useCase = recordingUseCase(observed);
        listener = new PaperPhysicalTrackingListener(
                plugin, () -> useCase, () -> 4, MetricsPort.noOp());
        PlayerMock player = server.addPlayer();
        ItemStack tracked = trackedItem();
        player.getInventory().setHelmet(tracked);
        Inventory chest = server.createInventory(null, 9);
        player.openInventory(chest);
        PlayerInventorySlotChangeEvent event = new PlayerInventorySlotChangeEvent(
                player,
                0,
                ItemStack.empty(),
                tracked);

        listener.onSlotChange(event);
        server.getScheduler().performOneTick();

        assertCanonicalHelmetObservation(observed);
    }

    @Test
    void closeDrainsAcceptedSlotChangeBeforeScheduledTickAndDoesNotDuplicateIt() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        listener = new PaperPhysicalTrackingListener(
                plugin, () -> recordingUseCase(observed), () -> 4, MetricsPort.noOp());
        PlayerMock player = server.addPlayer();
        ItemStack tracked = trackedItem();
        player.getInventory().setHelmet(tracked);
        Inventory chest = server.createInventory(null, 9);
        player.openInventory(chest);
        PlayerInventorySlotChangeEvent event = new PlayerInventorySlotChangeEvent(
                player,
                0,
                ItemStack.empty(),
                tracked);

        listener.onSlotChange(event);
        listener.close();

        assertCanonicalHelmetObservation(observed);
        server.getScheduler().performOneTick();
        assertEquals(1, observed.size());
    }

    @Test
    void repeatedSlotChangesScheduleOnlyOneScanPerPlayerAndTick() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        listener = new PaperPhysicalTrackingListener(
                plugin, () -> recordingUseCase(observed), () -> 4, MetricsPort.noOp());
        PlayerMock player = server.addPlayer();
        ItemStack tracked = trackedItem();
        player.getInventory().setHelmet(tracked);
        PlayerInventorySlotChangeEvent event = new PlayerInventorySlotChangeEvent(
                player, 0, ItemStack.empty(), tracked);

        for (int count = 0; count < 50; count++) {
            listener.onSlotChange(event);
        }

        assertEquals(1, listener.pendingUniquePlayerScans());
        server.getScheduler().performOneTick();
        assertEquals(0, listener.pendingUniquePlayerScans());
        assertCanonicalHelmetObservation(observed);

        // The deduplication window ends when the scheduled action executes.
        listener.onSlotChange(event);
        assertEquals(1, listener.pendingUniquePlayerScans());
        server.getScheduler().performOneTick();
        assertEquals(0, listener.pendingUniquePlayerScans());
    }

    @Test
    void differentPlayersAndEventSourcesAreNeverCoalescedTogether() {
        listener = new PaperPhysicalTrackingListener(
                plugin, () -> recordingUseCase(new CopyOnWriteArrayList<>()),
                () -> 4, MetricsPort.noOp());
        PlayerMock first = server.addPlayer();
        PlayerMock second = server.addPlayer();

        listener.schedulePlayerUnique(first.getUniqueId(), SLOT_CHANGE_SOURCE);
        listener.schedulePlayerUnique(first.getUniqueId(), "player-respawn");
        listener.schedulePlayerUnique(second.getUniqueId(), SLOT_CHANGE_SOURCE);
        listener.schedulePlayerUnique(second.getUniqueId(), SLOT_CHANGE_SOURCE);

        assertEquals(3, listener.pendingUniquePlayerScans());
        server.getScheduler().performOneTick();
        assertEquals(0, listener.pendingUniquePlayerScans());
    }

    @Test
    void deferredSchedulerRejectsNewWorkAfterClose() {
        PaperDeferredMainThreadActions actions = new PaperDeferredMainThreadActions(
                plugin, "test scheduling refusal");
        assertTrue(actions.schedule(() -> {}));
        actions.close();
        assertFalse(actions.schedule(() -> {}));
    }

    @Test
    void burstCoalescingStillReportsBothCopiesOfTheSameTrackedIdentity() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        listener = new PaperPhysicalTrackingListener(
                plugin, () -> recordingUseCase(observed), () -> 4, MetricsPort.noOp());
        PlayerMock player = server.addPlayer();
        ItemStack item = trackedItem();
        player.getInventory().setItem(5, item.clone());
        player.getInventory().setHelmet(item.clone());
        PlayerInventorySlotChangeEvent event = new PlayerInventorySlotChangeEvent(
                player, 0, ItemStack.empty(), item);

        for (int count = 0; count < 25; count++) {
            listener.onSlotChange(event);
        }
        assertEquals(1, listener.pendingUniquePlayerScans());
        server.getScheduler().performOneTick();

        assertEquals(2, observed.size());
        assertEquals(
                Set.of("slot:5", "armor:3"),
                observed.stream()
                        .map(request -> request.location().containerPath())
                        .collect(Collectors.toSet()));
        assertTrue(observed.stream().allMatch(request ->
                request.mode() == TrackingObservationUseCase.EvidenceMode.RECONCILIATION));
    }

    @Test
    void shutdownDrainsCoalescedScansExactlyOnce() {
        List<TrackingObservationUseCase.Request> observed = new CopyOnWriteArrayList<>();
        listener = new PaperPhysicalTrackingListener(
                plugin, () -> recordingUseCase(observed), () -> 4, MetricsPort.noOp());
        PlayerMock player = server.addPlayer();
        ItemStack tracked = trackedItem();
        player.getInventory().setHelmet(tracked);
        PlayerInventorySlotChangeEvent event = new PlayerInventorySlotChangeEvent(
                player, 0, ItemStack.empty(), tracked);

        for (int count = 0; count < 40; count++) {
            listener.onSlotChange(event);
        }
        assertEquals(1, listener.pendingUniquePlayerScans());
        listener.close();

        assertEquals(0, listener.pendingUniquePlayerScans());
        assertCanonicalHelmetObservation(observed);
        server.getScheduler().performOneTick();
        assertEquals(1, observed.size());
    }

    private static TrackingObservationUseCase recordingUseCase(
            List<TrackingObservationUseCase.Request> observed) {
        return request -> {
            observed.add(request);
            return CompletableFuture.completedFuture(TrackingObservationUseCase.Result.of(
                    TrackingObservationUseCase.Status.RECORDED,
                    "ok"));
        };
    }

    private static ItemStack trackedItem() {
        return new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.NETHER_STAR), IDENTITY);
    }

    private static void assertCanonicalHelmetObservation(
            List<TrackingObservationUseCase.Request> observed) {
        assertEquals(1, observed.size());
        TrackingObservationUseCase.Request request = observed.getFirst();
        assertEquals(IDENTITY, request.identity());
        assertEquals(LocationDescriptor.Type.PLAYER_INVENTORY, request.location().type());
        assertEquals("armor:3", request.location().containerPath());
        assertEquals(
                TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION,
                request.mode());
        assertEquals(SLOT_CHANGE_SOURCE, request.source());
    }
}
