package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.ItemAnomalyObservationUseCase;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LocationDescriptor;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import org.bukkit.Material;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperIdentityAnomalyListenerTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString(
                    "11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString(
                    "22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(1));

    private static final int HOTBAR_SLOT = 3;

    private ServerMock server;
    private PlayerMock player;
    private Plugin plugin;
    private RecordingUseCase useCase;
    private PaperIdentityAnomalyListener listener;
    private PaperItemIdentityCodec identityCodec;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        plugin = MockBukkit.createMockPlugin();
        useCase = new RecordingUseCase();
        server.getServicesManager().register(
                ItemAnomalyObservationUseCase.class,
                useCase,
                plugin,
                ServicePriority.Normal);
        listener = new PaperIdentityAnomalyListener(plugin, 8);
        identityCodec = new PaperItemIdentityCodec();
    }

    @AfterEach
    void tearDown() {
        listener.close();
        MockBukkit.unmock();
    }

    @Test
    void startupScanRecordsDuplicateInventoryCopiesWithoutDeletingEither() {
        ItemStack first = trackedItem();
        ItemStack second = trackedItem();
        player.getInventory().setItem(0, first);
        player.getInventory().setItem(1, second);

        listener.start();

        assertEquals(1, useCase.requests.size());
        ItemAnomalyObservationUseCase.Request request = useCase.requests.getFirst();
        assertEquals(ItemAnomalyObservationUseCase.Kind.DUPLICATE_INSTANCE, request.kind());
        assertEquals(2, request.evidenceLocations().size());
        assertEquals(first, player.getInventory().getItem(0));
        assertEquals(second, player.getInventory().getItem(1));
    }

    @Test
    void swapDoesNotRecordADuplicateWhileBothSlotsBrieflyHoldTheItem() {
        player.getInventory().setItem(27, trackedItem());
        player.openInventory(server.createInventory(null, 9));
        listener.start();
        // Mid-swap (number key or offhand): the new slot is filled before slot 27 is emptied.
        ItemStack moved = trackedItem();
        player.getInventory().setItem(HOTBAR_SLOT, moved);
        listener.onInventorySlotChange(slotChange(moved));
        player.getInventory().setItem(27, ItemStack.empty());

        server.getScheduler().performOneTick();

        assertTrue(useCase.requests.isEmpty(), () -> "unexpected anomaly: " + useCase.requests);
    }

    @Test
    void slotChangeStillRecordsADuplicateThatOutlivesTheTick() {
        player.getInventory().setItem(27, trackedItem());
        player.openInventory(server.createInventory(null, 9));
        listener.start();
        ItemStack copy = trackedItem();
        player.getInventory().setItem(HOTBAR_SLOT, copy);
        listener.onInventorySlotChange(slotChange(copy));

        server.getScheduler().performOneTick();

        assertTrue(!useCase.requests.isEmpty() && useCase.requests.stream().allMatch(request ->
                request.kind() == ItemAnomalyObservationUseCase.Kind.DUPLICATE_INSTANCE));
    }

    @Test
    void enderChestCopyAtSameSlotAsPlayerInventoryIsNotCollapsedIntoOneLocation() {
        player.getInventory().setItem(0, trackedItem());
        player.getEnderChest().setItem(0, trackedItem());

        try (PaperIdentityObservationScanner scanner =
                new PaperIdentityObservationScanner(plugin, 8)) {
            scanner.scanStorageInventory(player.getEnderChest(), player, "ender-chest-test");
        }

        assertEquals(1, useCase.requests.size());
        ItemAnomalyObservationUseCase.Request request = useCase.requests.getFirst();
        assertEquals(ItemAnomalyObservationUseCase.Kind.DUPLICATE_INSTANCE, request.kind());
        assertEquals(2, request.evidenceLocations().size());
        assertTrue(request.evidenceLocations().stream().anyMatch(location ->
                location.type() == LocationDescriptor.Type.PLAYER_INVENTORY));
        assertTrue(request.evidenceLocations().stream().anyMatch(location ->
                location.type() == LocationDescriptor.Type.PLAYER_ENDER_CHEST));
    }

    @Test
    void startupScanRecordsRecoverableMalformedStackEvidence() {
        ItemStack malformed = trackedItem();
        malformed.setAmount(2);
        player.getInventory().setItem(0, malformed);

        listener.start();

        assertEquals(1, useCase.requests.size());
        ItemAnomalyObservationUseCase.Request request = useCase.requests.getFirst();
        assertEquals(ItemAnomalyObservationUseCase.Kind.MALFORMED_STACK, request.kind());
        assertTrue(request.detail().contains("STACKING_VIOLATION"));
        assertEquals(2, player.getInventory().getItem(0).getAmount());
    }

    @Test
    void startupScanRecordsMalformedNestedShulkerEvidenceWithoutMutatingIt() {
        ItemStack malformed = trackedItem();
        malformed.setAmount(2);
        ItemStack outer = shulkerContaining(malformed);
        player.getInventory().setItem(0, outer);

        listener.start();

        assertEquals(1, useCase.requests.size());
        ItemAnomalyObservationUseCase.Request request = useCase.requests.getFirst();
        assertEquals(ItemAnomalyObservationUseCase.Kind.MALFORMED_STACK, request.kind());
        assertEquals(LocationDescriptor.Type.NESTED_CONTAINER, request.location().type());
        assertTrue(request.location().containerPath().contains("shulker:0"));
        assertTrue(request.detail().contains("STACKING_VIOLATION"));

        ItemStack storedOuter = player.getInventory().getItem(0);
        BlockStateMeta storedMeta = assertInstanceOf(
                BlockStateMeta.class, storedOuter.getItemMeta());
        ShulkerBox storedShulker = assertInstanceOf(
                ShulkerBox.class, storedMeta.getBlockState());
        assertEquals(2, storedShulker.getInventory().getItem(0).getAmount());
    }

    private PlayerInventorySlotChangeEvent slotChange(ItemStack newItem) {
        // The event converts a raw view slot; with a 9-slot chest open, hotbar slot 3 is raw 39.
        PlayerInventorySlotChangeEvent event = new PlayerInventorySlotChangeEvent(
                player, 9 + 27 + HOTBAR_SLOT, ItemStack.empty(), newItem);
        assertEquals(HOTBAR_SLOT, event.getSlot());
        return event;
    }

    private ItemStack trackedItem() {
        return identityCodec.writeIdentity(ItemStack.of(Material.DIAMOND), IDENTITY);
    }

    private static ItemStack shulkerContaining(ItemStack nested) {
        ItemStack outer = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta meta = assertInstanceOf(BlockStateMeta.class, outer.getItemMeta());
        ShulkerBox shulker = assertInstanceOf(ShulkerBox.class, meta.getBlockState());
        shulker.getInventory().setItem(0, nested);
        meta.setBlockState(shulker);
        assertTrue(outer.setItemMeta(meta));
        return outer;
    }

    private static final class RecordingUseCase implements ItemAnomalyObservationUseCase {
        private final List<Request> requests = new ArrayList<>();

        @Override
        public CompletionStage<Result> record(Request request) {
            requests.add(request);
            return CompletableFuture.completedFuture(Result.of(
                    Status.RECORDED,
                    "recorded"));
        }
    }
}
