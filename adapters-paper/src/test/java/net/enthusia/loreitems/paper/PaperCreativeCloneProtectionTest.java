package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.event.player.PlayerPickBlockEvent;
import io.papermc.paper.event.player.PlayerPickEntityEvent;
import java.lang.reflect.Proxy;
import java.util.UUID;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperCreativeCloneProtectionTest {
    private static final LoreItemIdentity IDENTITY = new LoreItemIdentity(
            new LoreDefinitionId(UUID.fromString(
                    "11111111-1111-1111-1111-111111111111")),
            new LoreInstanceId(UUID.fromString(
                    "22222222-2222-2222-2222-222222222222")),
            new TemplateRevision(3));

    private ServerMock server;
    private PlayerMock player;
    private PaperTrackedItemProtectionListener listener;
    private PaperItemIdentityCodec identityCodec;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        player = server.addPlayer();
        player.setGameMode(GameMode.CREATIVE);
        Plugin plugin = MockBukkit.createMockPlugin();
        listener = new PaperTrackedItemProtectionListener(
                plugin,
                () -> {
                    throw new AssertionError("Creative clone protection must not invoke void loss");
                },
                4);
        identityCodec = new PaperItemIdentityCodec();
    }

    @AfterEach
    void tearDown() {
        listener.close();
        MockBukkit.unmock();
    }

    @Test
    void creativeCloneRejectsDirectAndNestedIdentityEvidenceButAllowsOrdinaryItems() {
        Inventory inventory = server.createInventory(null, 9);
        inventory.setItem(0, trackedItem());
        inventory.setItem(1, shulkerContaining(trackedItem()));
        inventory.setItem(2, ItemStack.of(Material.DIAMOND));
        player.openInventory(inventory);

        InventoryClickEvent direct = cloneEvent(0);
        listener.onCreativeClone(direct);
        assertTrue(direct.isCancelled());

        InventoryClickEvent nested = cloneEvent(1);
        listener.onCreativeClone(nested);
        assertTrue(nested.isCancelled());

        InventoryClickEvent ordinary = cloneEvent(2);
        listener.onCreativeClone(ordinary);
        assertFalse(ordinary.isCancelled());
    }

    @Test
    void creativeInventoryPacketRejectsInjectionButAllowsTrackedMovement() {
        Inventory inventory = server.createInventory(null, 9);
        player.openInventory(inventory);

        InventoryCreativeEvent injection = new InventoryCreativeEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                0,
                trackedItem());
        listener.onCreativeInventoryMutation(injection);
        assertTrue(injection.isCancelled());

        ItemStack original = trackedItem();
        inventory.setItem(0, original);
        InventoryCreativeEvent removal = new InventoryCreativeEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                0,
                ItemStack.empty());
        assertTrue(removal.getCurrentItem() != null
                && new PaperTrackedItemCollector().hasIdentityEvidence(removal.getCurrentItem()),
                "Removal event must contain a tracked source");
        assertTrue(new PaperItemIdentityCodec().readIdentity(trackedItem()) instanceof net.enthusia.loreitems.application.ItemIdentityReadResult.Tracked,
                "Tracked source must remain readable");
        listener.onCreativeInventoryMutation(removal);
        assertFalse(removal.isCancelled());

        // A real relocation carries the actual source item and all its
        // metadata; a newly forged stack with just the UUID is not equivalent.
        inventory.setItem(0, ItemStack.empty());
        InventoryCreativeEvent placement = new InventoryCreativeEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                1,
                original.clone());
        listener.onCreativeInventoryMutation(placement);
        assertFalse(placement.isCancelled());

        InventoryCreativeEvent replay = new InventoryCreativeEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                2,
                original.clone());
        listener.onCreativeInventoryMutation(replay);
        assertTrue(replay.isCancelled());

        // A tracked source already on the cursor does NOT authorize cloning.
        player.setItemOnCursor(trackedItem());
        InventoryCreativeEvent cursorInjection = new InventoryCreativeEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                3,
                trackedItem());
        listener.onCreativeInventoryMutation(cursorInjection);
        assertTrue(cursorInjection.isCancelled());
    }

    @Test
    void creativePacketCannotAlterMetadataWhileKeepingTrackedIdentity() {
        Inventory inventory = server.createInventory(null, 9);
        player.openInventory(inventory);
        ItemStack original = trackedItem();
        ItemStack modified = original.clone();
        var modifiedMeta = modified.getItemMeta();
        modifiedMeta.setUnbreakable(true);
        assertTrue(modified.setItemMeta(modifiedMeta));

        // Same-slot replacement cannot bypass protection using a matching UUID.
        inventory.setItem(0, original);
        InventoryCreativeEvent sameSlotEdit = new InventoryCreativeEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                0,
                modified);
        listener.onCreativeInventoryMutation(sameSlotEdit);
        assertTrue(sameSlotEdit.isCancelled());

        // Nor may a valid movement credit authorize rewriting item metadata.
        InventoryCreativeEvent removal = new InventoryCreativeEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                0,
                ItemStack.empty());
        listener.onCreativeInventoryMutation(removal);
        assertFalse(removal.isCancelled());
        inventory.setItem(0, ItemStack.empty());

        InventoryCreativeEvent modifiedPlacement = new InventoryCreativeEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                1,
                modified);
        listener.onCreativeInventoryMutation(modifiedPlacement);
        assertTrue(modifiedPlacement.isCancelled());

        InventoryCreativeEvent replay = new InventoryCreativeEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                2,
                original);
        listener.onCreativeInventoryMutation(replay);
        assertTrue(replay.isCancelled());
    }

    @Test
    void creativePacketCannotIncreaseTrackedStackAmountWithSameIdentity() {
        Inventory inventory = server.createInventory(null, 9);
        player.openInventory(inventory);
        ItemStack original = trackedItem();
        inventory.setItem(0, original);

        ItemStack inflated = original.clone();
        inflated.setAmount(2);
        InventoryCreativeEvent sameSlot = new InventoryCreativeEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                0,
                inflated);
        listener.onCreativeInventoryMutation(sameSlot);
        assertTrue(sameSlot.isCancelled());

        InventoryCreativeEvent removal = new InventoryCreativeEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                0,
                ItemStack.empty());
        listener.onCreativeInventoryMutation(removal);
        assertFalse(removal.isCancelled());
        inventory.setItem(0, ItemStack.empty());

        InventoryCreativeEvent inflatedPlacement = new InventoryCreativeEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                1,
                inflated);
        listener.onCreativeInventoryMutation(inflatedPlacement);
        assertTrue(inflatedPlacement.isCancelled());

        // A failed placement must consume the credit; the real item cannot
        // be replayed later to disguise the attempted creative duplication.
        InventoryCreativeEvent replay = new InventoryCreativeEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                2,
                original.clone());
        listener.onCreativeInventoryMutation(replay);
        assertTrue(replay.isCancelled());
    }

    @Test
    void creativePacketCannotIncreaseAmountOfContainerWithNestedTrackedIdentity() {
        Inventory inventory = server.createInventory(null, 9);
        player.openInventory(inventory);
        ItemStack original = shulkerContaining(trackedItem());
        inventory.setItem(0, original);

        ItemStack inflated = original.clone();
        inflated.setAmount(2);
        InventoryCreativeEvent sameSlot = new InventoryCreativeEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                0,
                inflated);
        listener.onCreativeInventoryMutation(sameSlot);
        assertTrue(sameSlot.isCancelled());

        InventoryCreativeEvent removal = new InventoryCreativeEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                0,
                ItemStack.empty());
        listener.onCreativeInventoryMutation(removal);
        assertFalse(removal.isCancelled());
        inventory.setItem(0, ItemStack.empty());

        InventoryCreativeEvent inflatedPlacement = new InventoryCreativeEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                1,
                inflated);
        listener.onCreativeInventoryMutation(inflatedPlacement);
        assertTrue(inflatedPlacement.isCancelled());
    }

    @Test
    void creativePickBlockWithDataRejectsTrackedContainerCopy() {
        Inventory inventory = server.createInventory(null, InventoryType.SHULKER_BOX);
        inventory.setItem(0, trackedItem());
        Block block = inventoryBlock(inventory);
        PlayerPickBlockEvent event = new PlayerPickBlockEvent(player, block, true, 0, -1);

        listener.onCreativePickBlock(event);

        assertTrue(event.isCancelled());
    }

    @Test
    void creativePickEntityRejectsTrackedItemFrameCopy() {
        World world = server.addSimpleWorld("world");
        ItemFrame frame = (ItemFrame) world.spawnEntity(
                new Location(world, 3, 64, 3), EntityType.ITEM_FRAME);
        frame.setItem(trackedItem());
        PlayerPickEntityEvent event = new PlayerPickEntityEvent(player, frame, false, 0, -1);

        listener.onCreativePickEntity(event);

        assertTrue(event.isCancelled());
    }

    private InventoryClickEvent cloneEvent(int rawSlot) {
        return new InventoryClickEvent(
                player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER,
                rawSlot,
                ClickType.MIDDLE,
                InventoryAction.CLONE_STACK);
    }

    private ItemStack trackedItem() {
        return identityCodec.writeIdentity(ItemStack.of(Material.NETHER_STAR), IDENTITY);
    }

    private static Block inventoryBlock(Inventory inventory) {
        BlockState state = (BlockState) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {BlockState.class, InventoryHolder.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getInventory")) {
                        return inventory;
                    }
                    return objectMethodOrFail(proxy, method.getName(), args);
                });
        return (Block) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Block.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getState")) {
                        return state;
                    }
                    return objectMethodOrFail(proxy, method.getName(), args);
                });
    }

    private static Object objectMethodOrFail(Object proxy, String methodName, Object[] args) {
        return switch (methodName) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "inventory-block-test-double";
            case "equals" -> proxy == args[0]; // NOPMD - proxy identity
            default -> throw new AssertionError("Unexpected test-double call: " + methodName);
        };
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
}
