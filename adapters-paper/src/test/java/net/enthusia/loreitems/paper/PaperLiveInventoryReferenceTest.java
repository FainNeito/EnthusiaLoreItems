package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class PaperLiveInventoryReferenceTest {
    private static final UUID WORLD_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void capturesBlockIdentityWithoutSnapshottingContents() {
        World world = proxy(World.class, (method, arguments) -> switch (method) {
            case "getUID" -> WORLD_ID;
            case "getKey" -> NamespacedKey.minecraft("overworld");
            default -> null;
        });
        Inventory inventory = proxy(Inventory.class, (method, arguments) -> switch (method) {
            case "getHolder" -> {
                requireLiveAccess(arguments);
                yield null;
            }
            case "getLocation" -> new Location(world, -17, 64, 32);
            default -> null;
        });
        PaperPhysicalInventorySnapshot snapshot =
                PaperPhysicalInventorySnapshot.capture(inventory).orElseThrow();
        assertEquals(new PaperInventoryReference.Block(WORLD_ID, -17, 64, 32), snapshot.reference());
        assertEquals(LocationDescriptor.Type.BLOCK_CONTAINER, snapshot.type());
        assertEquals("minecraft:overworld:-17:64:32", snapshot.key());
        assertEquals(snapshot.key(), PaperIdentityObservationScanner.inventoryLocation(inventory, "slot:0").locationKey());
    }

    @Test
    void resolvesFreshContentsAndNeverLoadsUnloadedChunks() {
        Inventory first = proxy(Inventory.class, (method, arguments) -> null);
        Inventory second = proxy(Inventory.class, (method, arguments) -> null);
        AtomicReference<Inventory> current = new AtomicReference<>(first);
        AtomicBoolean loaded = new AtomicBoolean(true);
        Container container = proxy(Container.class, (method, arguments) -> switch (method) {
            case "getInventory" -> current.get();
            default -> null;
        });
        Block block = proxy(Block.class, (method, arguments) -> {
            assertEquals("getState", method);
            requireLiveAccess(arguments);
            return container;
        });
        World world = proxy(World.class, (method, arguments) -> switch (method) {
            case "isChunkLoaded" -> {
                assertEquals(-2, arguments[0]);
                assertEquals(2, arguments[1]);
                yield loaded.get();
            }
            case "getBlockAt" -> {
                assertTrue(loaded.get(), "Must not access an unloaded block");
                yield block;
            }
            default -> null;
        });
        Server server = proxy(Server.class, (method, arguments) -> world);
        Plugin plugin = proxy(Plugin.class, (method, arguments) -> server);
        PaperInventoryReference reference = new PaperInventoryReference.Block(WORLD_ID, -17, 64, 32);
        assertSame(first, reference.resolve(plugin).orElseThrow());
        current.set(second);
        assertSame(second, reference.resolve(plugin).orElseThrow());
        loaded.set(false);
        assertTrue(reference.resolve(plugin).isEmpty());
    }

    private static void requireLiveAccess(Object[] arguments) {
        assertEquals(1, arguments.length, "Snapshot overload must never be called");
        assertEquals(false, arguments[0], "Container snapshots serialize nested item contents");
    }

    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(), new Class<?>[] {type},
                (proxy, method, arguments) -> invocation.invoke(
                        method.getName(), arguments == null ? new Object[0] : arguments)));
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(String method, Object[] arguments);
    }
}
