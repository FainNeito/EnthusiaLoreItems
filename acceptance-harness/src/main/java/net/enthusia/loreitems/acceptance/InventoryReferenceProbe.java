package net.enthusia.loreitems.acceptance;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** Disposable real-Paper reference probe, excluded from the production plugin JAR. */
@SuppressWarnings({"PMD.UseProperClassLoader", "PMD.AvoidAccessibilityAlteration"})
final class InventoryReferenceProbe {
    private InventoryReferenceProbe() { }

    static void start(JavaPlugin harness) {
        try {
            run(harness);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            harness.getLogger().log(java.util.logging.Level.SEVERE, "REFERENCE_PROBE FAIL", exception);
        }
    }

    private static void run(JavaPlugin harness) throws ReflectiveOperationException {
        Plugin plugin = Objects.requireNonNull(Bukkit.getPluginManager().getPlugin("EnthusiaLoreItems"));
        Class<?> snapshot = plugin.getClass().getClassLoader()
                .loadClass("net.enthusia.loreitems.paper.PaperPhysicalInventorySnapshot");
        Method capture = snapshot.getDeclaredMethod("capture", Inventory.class);
        Method resolve = snapshot.getDeclaredMethod("resolve", Plugin.class);
        capture.setAccessible(true);
        resolve.setAccessible(true);
        World world = Objects.requireNonNull(Bukkit.getWorld("world"));
        for (Material material : List.of(Material.CHEST, Material.HOPPER)) {
            org.bukkit.block.Block block = world.getBlockAt(4, 200, material == Material.CHEST ? 4 : 6);
            block.setType(material);
            Inventory inventory = ((Container) block.getState(false)).getInventory();
            ItemStack shulker = shulker();
            for (int slot = 0; slot < inventory.getSize(); slot++) {
                inventory.setItem(slot, shulker);
            }
            List<Long> before = new ArrayList<>();
            List<Long> after = new ArrayList<>();
            for (int iteration = 0; iteration < 100; iteration++) {
                long start = System.nanoTime();
                inventory.getHolder();
                inventory.getHolder();
                ((Container) block.getState()).getInventory().getSize();
                before.add(System.nanoTime() - start);
                start = System.nanoTime();
                Object reference = ((Optional<?>) capture.invoke(null, inventory)).orElseThrow();
                Inventory resolved = (Inventory) ((Optional<?>) resolve.invoke(reference, plugin)).orElseThrow();
                after.add(System.nanoTime() - start);
                if (resolved.getSize() != inventory.getSize()) {
                    throw new IllegalStateException("Inventory size changed");
                }
            }
            Object reference = ((Optional<?>) capture.invoke(null, inventory)).orElseThrow();
            inventory.setItem(0, ItemStack.of(Material.DIAMOND, 7));
            Inventory fresh = (Inventory) ((Optional<?>) resolve.invoke(reference, plugin)).orElseThrow();
            if (Objects.requireNonNull(fresh.getItem(0)).getAmount() != 7) {
                throw new IllegalStateException("Reference retained stale contents");
            }
            report(harness, material, before, after);
            inventory.clear();
            block.setType(Material.AIR);
        }
    }

    private static void report(JavaPlugin harness, Material material, List<Long> before, List<Long> after) {
        before.sort(Long::compareTo);
        after.sort(Long::compareTo);
        harness.getLogger().info("REFERENCE_PROBE PASS material=" + material
                + " samples=100 baseline_p50_ms=" + before.get(49) / 1_000_000.0
                + " candidate_p50_ms=" + after.get(49) / 1_000_000.0
                + " baseline_p95_ms=" + before.get(94) / 1_000_000.0
                + " candidate_p95_ms=" + after.get(94) / 1_000_000.0
                + " fresh_contents=true server=" + Bukkit.getVersion());
    }

    private static ItemStack shulker() {
        ItemStack item = ItemStack.of(Material.SHULKER_BOX);
        BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
        ShulkerBox box = (ShulkerBox) meta.getBlockState();
        for (int slot = 0; slot < 27; slot++) {
            box.getInventory().setItem(slot, ItemStack.of(Material.DIAMOND));
        }
        meta.setBlockState(box);
        if (!item.setItemMeta(meta)) {
            throw new IllegalStateException("Could not create shulker fixture");
        }
        return item;
    }
}
