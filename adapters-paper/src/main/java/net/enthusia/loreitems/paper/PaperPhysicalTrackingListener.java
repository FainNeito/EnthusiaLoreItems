package net.enthusia.loreitems.paper;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** Event-driven physical tracking with bounded natural-access reconciliation. */
public final class PaperPhysicalTrackingListener implements Listener, AutoCloseable {
    private static final int MIN_BUDGET = 1;
    private static final int QUEUE_MULTIPLIER = 32;
    private static final int MAX_ITEMS_PER_SCAN = 256;
    private static final long CHUNK_SEED_PERIOD_TICKS = 100L;
    private static final Chunk[] EMPTY_CHUNKS = new Chunk[0];
    private static final UUID NO_WORLD = new UUID(0L, 0L);

    private final Plugin plugin;
    private final IntSupplier budgetSupplier;
    private final MetricsPort metrics;
    private final PaperTrackingCoordinator coordinator;
    private final PaperPhysicalInventoryScanner scanner;
    private final PaperPhysicalEntityScanner entityScanner;
    private final PaperDeferredMainThreadActions deferredActions;
    private final PaperBlockInventoryTracking blockInventoryTracking;
    private final Queue<PaperTrackingScanRequest> scans = new ArrayDeque<>();
    private final PaperPeriodicChunkScanKeys periodicChunkKeys = new PaperPeriodicChunkScanKeys();
    private final Set<UUID> deathDrops = new HashSet<>();
    private final PaperPlayerScanCoalescer pendingPlayerScans;

    private BukkitTask scanTask;
    private BukkitTask seedTask;
    private int worldCursor;
    private int chunkCursor;
    private UUID seededWorldId = NO_WORLD;
    private Chunk[] seededChunks = EMPTY_CHUNKS;
    private boolean scanSaturated;
    private boolean closed;

    public PaperPhysicalTrackingListener(
            Plugin plugin,
            Supplier<TrackingObservationUseCase> useCaseSupplier,
            IntSupplier budgetSupplier,
            MetricsPort metrics) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.budgetSupplier = Objects.requireNonNull(budgetSupplier, "budgetSupplier");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.coordinator = new PaperTrackingCoordinator(
                plugin, useCaseSupplier, budgetSupplier, metrics);
        this.scanner = new PaperPhysicalInventoryScanner(coordinator);
        this.entityScanner = new PaperPhysicalEntityScanner(scanner);
        this.deferredActions = new PaperDeferredMainThreadActions(
                plugin,
                "Could not schedule lore-item tracking during shutdown.");
        this.pendingPlayerScans = new PaperPlayerScanCoalescer(
                deferredActions, metrics, (id, source) -> scanPlayer(id, true, source));
        this.blockInventoryTracking = new PaperBlockInventoryTracking(
                plugin, scanner, deferredActions, MAX_ITEMS_PER_SCAN);
        currentBudget();
    }

    public void start() {
        if (closed) {
            throw new IllegalStateException("Physical tracking listener is closed");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getOnlinePlayers().forEach(player ->
                enqueue(PaperTrackingScanRequest.player(
                        player.getUniqueId(), true, "tracking-start")));
        seedLoadedChunks();
        scanTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::drain, 1L, 1L);
        seedTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin, this::seedLoadedChunks, 1L, CHUNK_SEED_PERIOD_TICKS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        enqueue(PaperTrackingScanRequest.player(
                event.getPlayer().getUniqueId(), true, "player-join"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        schedulePlayerUnique(event.getPlayer().getUniqueId(), "player-respawn");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        schedulePlayerUnique(event.getPlayer().getUniqueId(), "inventory-slot-change");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        Optional<PaperPhysicalInventorySnapshot> source =
                PaperPhysicalInventorySnapshot.capture(event.getSource());
        Optional<PaperPhysicalInventorySnapshot> destination =
                PaperPhysicalInventorySnapshot.capture(event.getDestination());
        Set<LoreItemIdentity> identities = scanner.trackedIdentities(event.getItem());
        scheduleNextTick(() -> {
            scanReference(source, "inventory-move-source");
            submitMatchingIdentities(destination, identities, "inventory-move-destination");
            scanReference(destination, "inventory-move-destination");
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        Item item = event.getItem();
        ItemStack stack = item.getItemStack();
        scanner.scanItemTree(
                stack,
                PaperPhysicalEntityScanner.droppedLocation(item),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "hopper-pickup-source",
                new PaperScanLimit(MAX_ITEMS_PER_SCAN));
        Optional<PaperPhysicalInventorySnapshot> destination =
                PaperPhysicalInventorySnapshot.capture(event.getInventory());
        Set<LoreItemIdentity> identities = scanner.trackedIdentities(stack);
        scheduleNextTick(() -> {
            submitMatchingIdentities(destination, identities, "hopper-pickup-destination");
            scanReference(destination, "hopper-pickup-destination");
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        scanner.scanItemTree(
                item.getItemStack(),
                PaperPhysicalEntityScanner.droppedLocation(item),
                TrackingObservationUseCase.Presence.PRESENT,
                TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION,
                "player-drop",
                new PaperScanLimit(MAX_ITEMS_PER_SCAN));
        schedulePlayerUnique(event.getPlayer().getUniqueId(), "player-drop-player");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Item item = event.getItem();
        scanner.scanItemTree(
                item.getItemStack(),
                PaperPhysicalEntityScanner.droppedLocation(item),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "player-pickup-source",
                new PaperScanLimit(MAX_ITEMS_PER_SCAN));
        schedulePlayerUnique(player.getUniqueId(), "player-pickup-destination");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        Item item = event.getEntity();
        Set<LoreItemIdentity> identities = scanner.trackedIdentities(item.getItemStack());
        boolean deathDrop = false;
        for (LoreItemIdentity identity : identities) {
            if (deathDrops.remove(identity.instanceId().value())) {
                deathDrop = true;
            }
        }
        TrackingObservationUseCase.EvidenceMode mode = deathDrop
                ? TrackingObservationUseCase.EvidenceMode.AUTHORITATIVE_TRANSITION
                : TrackingObservationUseCase.EvidenceMode.RECONCILIATION;
        scanner.scanItemTree(
                item.getItemStack(),
                PaperPhysicalEntityScanner.droppedLocation(item),
                TrackingObservationUseCase.Presence.PRESENT,
                mode,
                "item-spawn",
                new PaperScanLimit(MAX_ITEMS_PER_SCAN));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        int remaining = MAX_ITEMS_PER_SCAN;
        for (ItemStack item : event.getDrops()) {
            if (remaining <= 0) {
                break;
            }
            remaining--;
            for (LoreItemIdentity identity : scanner.trackedIdentities(item)) {
                deathDrops.add(identity.instanceId().value());
            }
        }
        scanner.scanPlayer(
                event.getEntity(),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                "player-death");
        scheduleNextTick(deathDrops::clear);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        blockInventoryTracking.onBlockBreak(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        blockInventoryTracking.onBlockPlace(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpecialBlockInventoryInteract(PlayerInteractEvent event) {
        blockInventoryTracking.onSpecialBlockInventoryInteract(event);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        enqueue(PaperTrackingScanRequest.chunk(
                event.getChunk(),
                TrackingObservationUseCase.Presence.PRESENT,
                "chunk-load"));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        scanChunk(
                event.getChunk(),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                "chunk-unload");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        scanLifecycleEntities(
                event.getEntities(),
                TrackingObservationUseCase.Presence.PRESENT,
                "chunk-load-entities");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntitiesUnload(EntitiesUnloadEvent event) {
        scanLifecycleEntities(
                event.getEntities(),
                TrackingObservationUseCase.Presence.LAST_CONFIRMED,
                "chunk-unload-entities");
    }

    private void scanLifecycleEntities(
            List<Entity> entities,
            TrackingObservationUseCase.Presence presence,
            String source) {
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        entityScanner.scan(entities, presence, source, limit);
        if (!limit.hasRemaining()) {
            metrics.increment("tracking.scan_truncated");
            plugin.getLogger().fine(
                    "Lore-item entity lifecycle scan reached its bounded item limit for " + source + '.');
        }
    }

    void schedulePlayerUnique(UUID playerId, String source) {
        if (!closed) {
            pendingPlayerScans.schedule(playerId, source);
        }
    }

    int pendingUniquePlayerScans() {
        return pendingPlayerScans.size();
    }

    private void drain() {
        int budget = currentBudget();
        for (int count = 0; count < budget; count++) {
            PaperTrackingScanRequest request = scans.poll();
            if (request == null) {
                break;
            }
            periodicChunkKeys.release(request);
            request.run(plugin, this);
        }
        if (scans.isEmpty() && scanSaturated) {
            scanSaturated = false;
            plugin.getLogger().fine("Lore-item scan backlog has drained.");
        }
        metrics.setGauge("tracking.scan_backlog", scans.size());
    }

    private void seedLoadedChunks() {
        List<World> worlds = plugin.getServer().getWorlds();
        if (worlds.isEmpty()) {
            return;
        }
        int budget = currentBudget();
        int enqueued = 0;
        int attempts = 0;
        int maximumAttempts = Math.addExact(budget, worlds.size());
        while (enqueued < budget && attempts < maximumAttempts) {
            if (worldCursor >= worlds.size()) {
                advanceSeedWorld(worlds.size());
            }
            World world = worlds.get(worldCursor);
            Chunk[] snapshot = loadedChunkSnapshot(world);
            if (chunkCursor >= snapshot.length) {
                advanceSeedWorld(worlds.size());
                attempts++;
                continue;
            }
            enqueue(PaperTrackingScanRequest.chunk(
                    snapshot[chunkCursor],
                    TrackingObservationUseCase.Presence.PRESENT,
                    "periodic-loaded-chunk"));
            chunkCursor++;
            enqueued++;
            attempts++;
        }
    }

    private Chunk[] loadedChunkSnapshot(World world) {
        UUID worldId = world.getUID();
        if (!worldId.equals(seededWorldId)) {
            seededWorldId = worldId;
            seededChunks = world.getLoadedChunks();
        }
        return seededChunks;
    }

    private void advanceSeedWorld(int worldCount) {
        worldCursor = (worldCursor + 1) % worldCount;
        chunkCursor = 0;
        seededWorldId = NO_WORLD;
        seededChunks = EMPTY_CHUNKS;
    }

    void enqueue(PaperTrackingScanRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed || !periodicChunkKeys.tryRegister(request, metrics)) {
            return;
        }
        int maximum = maxQueuedScans();
        if (scans.size() >= maximum) {
            periodicChunkKeys.release(request);
            metrics.increment("tracking.rejected");
            reportScanSaturation();
            return;
        }
        scans.add(request);
        if (scans.size() == maximum) {
            reportScanSaturation();
        }
    }

    int queuedScanCount() {
        return scans.size();
    }

    int queuedPeriodicChunkCount() {
        return periodicChunkKeys.size();
    }

    private void reportScanSaturation() {
        if (!scanSaturated) {
            scanSaturated = true;
            plugin.getLogger().warning(
                    "Lore-item scan backlog is full; previous durable evidence was preserved.");
        }
    }

    private void scanReference(
            Optional<PaperPhysicalInventorySnapshot> snapshot, String source) {
        snapshot.ifPresent(reference -> reference.resolve(plugin).ifPresent(inventory ->
                scanner.scanInventory(
                        inventory,
                        reference.type(),
                        reference.key(),
                        TrackingObservationUseCase.Presence.PRESENT,
                        TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                        source)));
    }

    private void submitMatchingIdentities(
            Optional<PaperPhysicalInventorySnapshot> snapshot,
            Set<LoreItemIdentity> identities,
            String source) {
        if (identities.isEmpty()) {
            return;
        }
        snapshot.ifPresent(reference -> reference.resolve(plugin).ifPresent(inventory ->
                identities.forEach(identity -> scanner.submitMatchingIdentity(
                        inventory,
                        reference.type(),
                        reference.key(),
                        identity,
                        source))));
    }

    void scanPlayer(UUID playerId, boolean unique, String source) {
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null) {
            return;
        }
        if (unique) {
            scanner.scanPlayerUnique(player, source);
        } else {
            scanner.scanPlayer(
                    player,
                    TrackingObservationUseCase.Presence.PRESENT,
                    TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                    source);
        }
    }

    void scanChunk(
            Chunk chunk,
            TrackingObservationUseCase.Presence presence,
            String source) {
        PaperScanLimit limit = new PaperScanLimit(MAX_ITEMS_PER_SCAN);
        scanChunkEntities(chunk, presence, source, limit);
        scanChunkContainers(chunk, presence, source, limit);
        if (!limit.hasRemaining()) {
            metrics.increment("tracking.scan_truncated");
            plugin.getLogger().fine(
                    "Lore-item chunk scan reached its bounded item limit for " + source + '.');
        }
    }

    private void scanChunkEntities(
            Chunk chunk,
            TrackingObservationUseCase.Presence presence,
            String source,
            PaperScanLimit limit) {
        entityScanner.scan(List.of(chunk.getEntities()), presence, source, limit);
    }

    private void scanChunkContainers(
            Chunk chunk,
            TrackingObservationUseCase.Presence presence,
            String source,
            PaperScanLimit limit) {
        Set<String> scannedInventories = new HashSet<>();
        for (BlockState state : chunk.getTileEntities()) {
            if (!limit.hasRemaining()) {
                return;
            }
            if (!(state instanceof InventoryHolder holder)) {
                continue;
            }
            Optional<PaperPhysicalInventorySnapshot> snapshot =
                    PaperPhysicalInventorySnapshot.capture(holder.getInventory());
            if (snapshot.isEmpty()) {
                continue;
            }
            PaperPhysicalInventorySnapshot reference = snapshot.orElseThrow();
            String scanKey = reference.type().name() + ':' + reference.key();
            if (!scannedInventories.add(scanKey)) {
                continue;
            }
            if (!limit.tryConsume()) {
                return;
            }
            scanner.scanInventory(
                    holder.getInventory(),
                    reference.type(),
                    reference.key(),
                    presence,
                    TrackingObservationUseCase.EvidenceMode.RECONCILIATION,
                    source + "-container",
                    limit);
        }
    }

    private void scheduleNextTick(Runnable action) {
        deferredActions.schedule(action);
    }

    private int currentBudget() {
        int value = budgetSupplier.getAsInt();
        if (value < MIN_BUDGET) {
            throw new IllegalStateException("Configured tracking budget must be positive");
        }
        return value;
    }

    private int maxQueuedScans() {
        return Math.multiplyExact(currentBudget(), QUEUE_MULTIPLIER);
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        HandlerList.unregisterAll(this);
        if (scanTask != null) {
            scanTask.cancel();
        }
        if (seedTask != null) {
            seedTask.cancel();
        }
        deferredActions.close();
        pendingPlayerScans.clear();
        scans.clear();
        periodicChunkKeys.clear();
        deathDrops.clear();
        scanSaturated = false;
        coordinator.close();
    }
}
