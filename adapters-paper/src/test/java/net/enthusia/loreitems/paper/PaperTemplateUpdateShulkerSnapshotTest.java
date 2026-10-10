package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
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
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class PaperTemplateUpdateShulkerSnapshotTest {
    private PlayerMock player;
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        player = MockBukkit.mock().addPlayer();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void decodesOneShulkerOnceForAllTwentySevenSiblingItems() {
        LoreItemIdentity identity = new LoreItemIdentity(
                new LoreDefinitionId(UUID.randomUUID()),
                new LoreInstanceId(UUID.randomUUID()), new TemplateRevision(1L));
        CountingShulker item = new CountingShulker();
        BlockStateMeta meta = assertInstanceOf(BlockStateMeta.class, item.getItemMeta());
        ShulkerBox box = assertInstanceOf(ShulkerBox.class, meta.getBlockState());
        ItemStack tracked = new PaperItemIdentityCodec().writeIdentity(
                ItemStack.of(Material.DIAMOND), identity);
        for (int slot = 0; slot < 27; slot++) {
            box.getInventory().setItem(slot, tracked);
        }
        meta.setBlockState(box);
        assertTrue(item.setItemMeta(meta));
        item.blockStateReads.set(0);
        item.countReads = true;
        Inventory inventory = countedInventory(item);
        List<PaperTemplateUpdateScanner.Candidate> found = new ArrayList<>();

        PaperTemplateUpdateScanner.ScanResult result = new PaperTemplateUpdateScanner()
                .scan(plugin, inventory, found::add);

        assertFalse(result.continuationRequired());
        assertFalse(result.abandoned());
        assertEquals(27, result.submitted());
        assertEquals(27, found.size(), "Duplicate evidence must not be deduplicated");
        assertTrue(found.stream().allMatch(candidate -> identity.equals(candidate.identity())));
        assertEquals(1, item.blockStateReads.get(), "One decode per parent per discovery pass");
    }

    private Inventory countedInventory(ItemStack item) {
        Inventory delegate = player.getInventory();
        return (Inventory) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(), new Class<?>[] {Inventory.class},
                (proxy, method, arguments) -> {
                    if ("getContents".equals(method.getName())) {
                        ItemStack[] contents = new ItemStack[delegate.getSize()];
                        contents[0] = item;
                        return contents;
                    }
                    if ("getItem".equals(method.getName())) {
                        return ((Integer) arguments[0]) == 0 ? item : null;
                    }
                    try {
                        return method.invoke(delegate, arguments);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private static final class CountingShulker extends ItemStack {
        private final AtomicInteger blockStateReads = new AtomicInteger();
        private boolean countReads;

        CountingShulker() {
            super(Material.SHULKER_BOX);
        }

        @Override
        public ItemMeta getItemMeta() {
            ItemMeta delegate = super.getItemMeta();
            if (!countReads) {
                return delegate;
            }
            return (ItemMeta) Proxy.newProxyInstance(
                    Thread.currentThread().getContextClassLoader(),
                    new Class<?>[] {BlockStateMeta.class},
                    (proxy, method, arguments) -> {
                        if ("getBlockState".equals(method.getName())) {
                            blockStateReads.incrementAndGet();
                        }
                        try {
                            return method.invoke(delegate, arguments);
                        } catch (InvocationTargetException exception) {
                            throw exception.getCause();
                        }
                    });
        }
    }
}
