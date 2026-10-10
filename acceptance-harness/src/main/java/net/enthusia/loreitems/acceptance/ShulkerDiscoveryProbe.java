package net.enthusia.loreitems.acceptance;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable runtime probe; never included in the production plugin JAR. */
final class ShulkerDiscoveryProbe {
    private final JavaPlugin harness;
    private final Plugin loreItems;
    private final Object scanner;
    private final Method scan;
    private final Inventory inventory;
    private final List<Object> candidates = new ArrayList<>();
    private final List<Long> durations = new ArrayList<>();
    private int completed;
    private int passes;

    private ShulkerDiscoveryProbe(JavaPlugin harness) throws ReflectiveOperationException {
        this.harness = harness;
        loreItems = java.util.Objects.requireNonNull(
                Bukkit.getPluginManager().getPlugin("EnthusiaLoreItems"), "LoreItems plugin");
        ClassLoader loader = loreItems.getClass().getClassLoader();
        Class<?> scannerClass = loader.loadClass("net.enthusia.loreitems.paper.PaperTemplateUpdateScanner");
        Constructor<?> constructor = scannerClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        scanner = constructor.newInstance();
        scan = scannerClass.getDeclaredMethod("scan", Plugin.class, Inventory.class, Consumer.class);
        scan.setAccessible(true);
        inventory = fixture(trackedItem(loader));
    }

    static void start(JavaPlugin harness) {
        try {
            new ShulkerDiscoveryProbe(harness).runPass();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            harness.getLogger().log(java.util.logging.Level.SEVERE,
                    "SHULKER_PROBE FAIL initialization", exception);
        }
    }

    private void runPass() {
        try {
            long start = System.nanoTime();
            Object result = scan.invoke(scanner, loreItems, inventory, (Consumer<Object>) candidates::add);
            durations.add(System.nanoTime() - start);
            passes++;
            if ((Boolean) value(result, "abandoned")) {
                throw new IllegalStateException("Scan abandoned");
            }
            if (!(Boolean) value(result, "continuationRequired")) {
                if (candidates.size() != 729) {
                    throw new IllegalStateException("Expected 729 duplicate observations, got " + candidates.size());
                }
                candidates.clear();
                completed++;
            }
            if (completed == 20) {
                durations.sort(Long::compareTo);
                harness.getLogger().info("SHULKER_PROBE PASS windows=" + completed
                        + " passes=" + passes + " observations_per_window=729"
                        + " p50_ms=" + percentile(0.50) + " p95_ms=" + percentile(0.95)
                        + " max_ms=" + percentile(1.0) + " server=" + Bukkit.getVersion()
                        + " java=" + System.getProperty("java.version"));
            } else if (passes >= 1280) {
                throw new IllegalStateException("Probe exceeded continuation bound");
            } else {
                Bukkit.getScheduler().runTask(harness, this::runPass);
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            harness.getLogger().log(java.util.logging.Level.SEVERE, "SHULKER_PROBE FAIL scan", exception);
        }
    }

    private double percentile(double fraction) {
        return durations.get((int) Math.ceil(fraction * durations.size()) - 1) / 1_000_000.0;
    }

    private static Object value(Object record, String field) throws ReflectiveOperationException {
        Method method = record.getClass().getDeclaredMethod(field);
        method.setAccessible(true);
        return method.invoke(record);
    }

    private static ItemStack trackedItem(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> definition = loader.loadClass("net.enthusia.loreitems.domain.LoreDefinitionId");
        Class<?> instance = loader.loadClass("net.enthusia.loreitems.domain.LoreInstanceId");
        Class<?> revision = loader.loadClass("net.enthusia.loreitems.domain.TemplateRevision");
        Class<?> identity = loader.loadClass("net.enthusia.loreitems.application.LoreItemIdentity");
        Object value = identity.getConstructor(definition, instance, revision).newInstance(
                definition.getConstructor(UUID.class).newInstance(UUID.randomUUID()),
                instance.getConstructor(UUID.class).newInstance(UUID.randomUUID()),
                revision.getConstructor(long.class).newInstance(1L));
        Class<?> codec = loader.loadClass("net.enthusia.loreitems.paper.PaperItemIdentityCodec");
        Constructor<?> constructor = codec.getDeclaredConstructor();
        constructor.setAccessible(true);
        Method write = codec.getDeclaredMethod("writeIdentity", ItemStack.class, identity);
        write.setAccessible(true);
        return (ItemStack) write.invoke(constructor.newInstance(), ItemStack.of(Material.DIAMOND), value);
    }

    private static Inventory fixture(ItemStack tracked) {
        org.bukkit.World world = java.util.Objects.requireNonNull(Bukkit.getWorld("world"), "fixture world");
        world.getBlockAt(4, 200, 4).setType(Material.CHEST);
        Inventory inventory = ((Container) world.getBlockAt(4, 200, 4).getState()).getInventory();
        inventory.clear();
        ItemStack[] contents = new ItemStack[27];
        for (int slot = 0; slot < 27; slot++) {
            contents[slot] = shulker(tracked);
        }
        // Keep synthetic identities out of physical tracking and its durable queue.
        return (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                new Class<?>[] {Inventory.class}, (proxy, method, arguments) -> {
                    if ("getContents".equals(method.getName())) {
                        return contents.clone();
                    }
                    if ("getItem".equals(method.getName())) {
                        return contents[(Integer) arguments[0]];
                    }
                    try {
                        return method.invoke(inventory, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private static ItemStack shulker(ItemStack tracked) {
        ItemStack item = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
        ShulkerBox box = (ShulkerBox) meta.getBlockState();
        for (int slot = 0; slot < 27; slot++) {
            box.getInventory().setItem(slot, tracked);
        }
        meta.setBlockState(box);
        item.setItemMeta(meta);
        return item;
    }
}
