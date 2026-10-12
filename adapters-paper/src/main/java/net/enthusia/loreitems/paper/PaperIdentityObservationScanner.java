package net.enthusia.loreitems.paper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

// Scan maps are method-local and confined to the Paper thread; concurrent maps add no safety.
@SuppressWarnings("PMD.UseConcurrentHashMap")
final class PaperIdentityObservationScanner implements AutoCloseable {
    private static final int MAX_CONFLICT_PATH_LENGTH =
            LocationDescriptor.MAX_CONTAINER_PATH_LENGTH;
    private static final int MAX_NESTING_DEPTH = 8;
    private static final int MAX_NESTED_ITEMS_PER_SCAN = 256;
    private static final int NO_SKIPPED_SLOT = -1;
    private static final String SLOT_PREFIX = "slot:";

    private final PaperItemAnomalyReporter anomalyReporter;

    PaperIdentityObservationScanner(Plugin plugin, int maxInFlight) {
        anomalyReporter = new PaperItemAnomalyReporter(plugin, maxInFlight);
    }

    void scanPlayerInventory(Player player, String source) {
        Map<UUID, ObservedCopy> firstCopies = new HashMap<>();
        PaperScanLimit nestedLimit = new PaperScanLimit(MAX_NESTED_ITEMS_PER_SCAN);
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ObservedCopy copy = observe(
                    item,
                    playerLocation(player, SLOT_PREFIX + slot),
                    source,
                    nestedLimit);
            recordIfDuplicate(firstCopies, copy, source);
        }
    }

    void scanStorageInventory(Inventory inventory, Player player, String source) {
        Map<UUID, ObservedCopy> firstCopies = new HashMap<>();
        PaperScanLimit nestedLimit = new PaperScanLimit(MAX_NESTED_ITEMS_PER_SCAN);
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            LocationDescriptor location = inventoryLocation(inventory, SLOT_PREFIX + slot);
            if (location == null) {
                continue;
            }
            ObservedCopy copy = observe(item, location, source, nestedLimit);
            recordIfDuplicate(firstCopies, copy, source);
            compareWithInventory(player, copy, source);
        }
    }

    ObservedCopy observe(ItemStack item, LocationDescriptor location, String source) {
        return observe(
                item,
                location,
                source,
                new PaperScanLimit(MAX_NESTED_ITEMS_PER_SCAN));
    }

    private ObservedCopy observe(
            ItemStack item,
            LocationDescriptor location,
            String source,
            PaperScanLimit nestedLimit) {
        ItemIdentityReadResult result = anomalyReporter.inspect(item, location, source);
        inspectNestedAnomalies(item, location, source, 0, nestedLimit);
        if (result instanceof ItemIdentityReadResult.Tracked tracked) {
            return new ObservedCopy(tracked.identity(), location);
        }
        return null;
    }

    private void inspectNestedAnomalies(
            ItemStack item,
            LocationDescriptor parent,
            String source,
            int depth,
            PaperScanLimit limit) {
        if (depth >= MAX_NESTING_DEPTH || item == null || item.getType().isAir()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta instanceof BlockStateMeta blockMeta) {
            BlockState state = blockMeta.getBlockState();
            if (state instanceof ShulkerBox shulker) {
                inspectNestedArray(
                        shulker.getInventory().getContents(),
                        parent,
                        "shulker:",
                        source,
                        depth,
                        limit);
            }
        }
        if (meta instanceof BundleMeta bundle) {
            inspectNestedList(
                    bundle.getItems(),
                    parent,
                    "bundle:",
                    source,
                    depth,
                    limit);
        }
    }

    private void inspectNestedArray(
            ItemStack[] contents,
            LocationDescriptor parent,
            String segment,
            String source,
            int depth,
            PaperScanLimit limit) {
        if (contents == null) {
            return;
        }
        for (int index = 0; index < contents.length; index++) {
            inspectNestedItem(contents[index], parent, segment + index, source, depth, limit);
        }
    }

    private void inspectNestedList(
            List<ItemStack> contents,
            LocationDescriptor parent,
            String segment,
            String source,
            int depth,
            PaperScanLimit limit) {
        for (int index = 0; index < contents.size(); index++) {
            inspectNestedItem(contents.get(index), parent, segment + index, source, depth, limit);
        }
    }

    private void inspectNestedItem(
            ItemStack nested,
            LocationDescriptor parent,
            String segment,
            String source,
            int depth,
            PaperScanLimit limit) {
        if (nested == null || nested.getType().isAir() || !limit.tryConsume()) {
            return;
        }
        LocationDescriptor location = nestedLocation(parent, segment);
        anomalyReporter.inspect(nested, location, source);
        inspectNestedAnomalies(nested, location, source, depth + 1, limit);
    }

    private static LocationDescriptor nestedLocation(
            LocationDescriptor parent,
            String segment) {
        String parentPath = parent.containerPath() == null ? "root" : parent.containerPath();
        return new LocationDescriptor(
                LocationDescriptor.Type.NESTED_CONTAINER,
                PaperTrackedItemCollector.nestedLocationKey(
                        parent.type(), parent.locationKey()),
                parentPath + '/' + segment);
    }

    void compareWithInventory(Player player, ObservedCopy external, String source) {
        compareWithInventory(player, external, NO_SKIPPED_SLOT, source);
    }

    void compareWithInventory(
            Player player,
            ObservedCopy external,
            int skippedSlot,
            String source) {
        if (external == null) {
            return;
        }
        PaperScanLimit nestedLimit = new PaperScanLimit(MAX_NESTED_ITEMS_PER_SCAN);
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (slot == skippedSlot) {
                continue;
            }
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            ObservedCopy inventoryCopy = observe(
                    item,
                    playerLocation(player, SLOT_PREFIX + slot),
                    source,
                    nestedLimit);
            if (sameIdentity(inventoryCopy, external)) {
                recordDuplicate(external, inventoryCopy, source);
            }
        }
    }

    void compareWithInventory(Inventory inventory, ObservedCopy external, String source) {
        if (external == null) {
            return;
        }
        PaperScanLimit nestedLimit = new PaperScanLimit(MAX_NESTED_ITEMS_PER_SCAN);
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item == null || item.getType().isAir()) {
                continue;
            }
            LocationDescriptor location = inventoryLocation(inventory, SLOT_PREFIX + slot);
            if (location == null) {
                continue;
            }
            ObservedCopy inventoryCopy = observe(item, location, source, nestedLimit);
            if (sameIdentity(inventoryCopy, external)) {
                recordDuplicate(external, inventoryCopy, source);
            }
        }
    }

    static LocationDescriptor playerLocation(HumanEntity player, String path) {
        return new LocationDescriptor(
                LocationDescriptor.Type.PLAYER_INVENTORY,
                "player:" + player.getUniqueId(),
                path);
    }

    static LocationDescriptor droppedLocation(Item item) {
        return new LocationDescriptor(
                LocationDescriptor.Type.DROPPED_ITEM,
                "entity:" + item.getUniqueId() + ':' + blockKey(item),
                "item-entity");
    }

    static LocationDescriptor blockLocation(Block block, String path) {
        return new LocationDescriptor(
                LocationDescriptor.Type.BLOCK_CONTAINER,
                block.getWorld().getKey() + ":" + block.getX() + ":" + block.getY()
                        + ":" + block.getZ(),
                path);
    }

    static LocationDescriptor inventoryLocation(Inventory inventory, String path) {
        InventoryHolder holder = inventory.getHolder(false);
        if (holder instanceof Player player) {
            LocationDescriptor.Type type = inventory.getType() == InventoryType.ENDER_CHEST
                    ? LocationDescriptor.Type.PLAYER_ENDER_CHEST
                    : LocationDescriptor.Type.PLAYER_INVENTORY;
            return new LocationDescriptor(type, "player:" + player.getUniqueId(), path);
        }
        Location location = inventory.getLocation();
        if (location != null && location.getWorld() != null) {
            return new LocationDescriptor(
                    LocationDescriptor.Type.BLOCK_CONTAINER,
                    location.getWorld().getKey() + ":"
                            + location.getBlockX() + ":"
                            + location.getBlockY() + ":"
                            + location.getBlockZ(),
                    path);
        }
        if (holder instanceof Entity entity) {
            return new LocationDescriptor(
                    LocationDescriptor.Type.DUPLICATE_CONFLICT,
                    "entity-inventory:" + entity.getUniqueId(),
                    path);
        }
        return null;
    }

    static LocationDescriptor displayLocation(
            LocationDescriptor.Type type,
            Entity entity,
            String path) {
        return new LocationDescriptor(
                type,
                entity.getWorld().getKey() + ":entity:" + entity.getUniqueId(),
                path);
    }

    static LocationDescriptor hangingSourceLocation(HangingPlaceEvent event) {
        Player player = event.getPlayer();
        if (player != null) {
            return playerLocation(player, "hanging-item");
        }
        return new LocationDescriptor(
                LocationDescriptor.Type.DUPLICATE_CONFLICT,
                "hanging:" + event.getEntity().getUniqueId(),
                "placement-source");
    }

    static LocationDescriptor shooterLocation(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player player) {
            return playerLocation(player, "bow-consumable");
        }
        return new LocationDescriptor(
                LocationDescriptor.Type.DUPLICATE_CONFLICT,
                "entity:" + event.getEntity().getUniqueId(),
                "held-consumable");
    }

    private void recordIfDuplicate(
            Map<UUID, ObservedCopy> firstCopies,
            ObservedCopy copy,
            String source) {
        if (copy == null) {
            return;
        }
        ObservedCopy previous = firstCopies.putIfAbsent(
                copy.identity().instanceId().value(), copy);
        if (previous != null) {
            recordDuplicate(previous, copy, source);
        }
    }

    private void recordDuplicate(ObservedCopy first, ObservedCopy second, String source) {
        if (first.location().equals(second.location())) {
            return;
        }
        String firstCandidate = describe(first.location());
        String secondCandidate = describe(second.location());
        boolean firstIsOrdered = firstCandidate.compareTo(secondCandidate) <= 0;
        String firstDescription = firstIsOrdered ? firstCandidate : secondCandidate;
        String secondDescription = firstIsOrdered ? secondCandidate : firstCandidate;
        LocationDescriptor firstLocation = firstIsOrdered
                ? first.location()
                : second.location();
        LocationDescriptor secondLocation = firstIsOrdered
                ? second.location()
                : first.location();
        String detail = "Same lore instance observed at copy1=" + firstDescription
                + " and copy2=" + secondDescription + '.';
        String path = truncate(
                "copy1=" + firstDescription + ";copy2=" + secondDescription,
                MAX_CONFLICT_PATH_LENGTH);
        LocationDescriptor conflict = new LocationDescriptor(
                LocationDescriptor.Type.DUPLICATE_CONFLICT,
                "instance:" + first.identity().instanceId().value()
                        + ":pair:" + Integer.toUnsignedString(path.hashCode()),
                path);
        anomalyReporter.recordDuplicate(
                first.identity(),
                conflict,
                List.of(firstLocation, secondLocation),
                source,
                detail);
    }

    private static boolean sameIdentity(ObservedCopy candidate, ObservedCopy external) {
        return candidate != null && candidate.identity().equals(external.identity());
    }

    private static String blockKey(Item item) {
        Location location = item.getLocation();
        return item.getWorld().getKey() + ":"
                + location.getBlockX() + ":"
                + location.getBlockY() + ":"
                + location.getBlockZ();
    }

    private static String describe(LocationDescriptor location) {
        return location.type().name() + ':' + location.locationKey()
                + (location.containerPath() == null
                        ? ""
                        : ":" + location.containerPath());
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    CompletionStage<Void> quiescence() {
        return anomalyReporter.quiescence();
    }

    @Override
    public void close() {
        anomalyReporter.close();
    }

    record ObservedCopy(LoreItemIdentity identity, LocationDescriptor location) {}
}
