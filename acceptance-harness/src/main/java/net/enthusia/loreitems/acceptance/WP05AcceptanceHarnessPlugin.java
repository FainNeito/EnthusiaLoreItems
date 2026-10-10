package net.enthusia.loreitems.acceptance;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.logging.Level;
import net.enthusia.loreitems.api.v1.LoreItemsServiceV1;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.GlowItemFrame;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Test-only helper used by the WP-05 GitHub Actions acceptance server.
 *
 * <p>This module is intentionally separate from the production plugin and is never shaded into the
 * release artifact. It only drives or observes real Bukkit state; durable acceptance assertions live
 * outside the helper so they remain independent of LoreItems internals.
 */
@SuppressWarnings("PMD.AvoidLiteralsInIfCondition")
public final class WP05AcceptanceHarnessPlugin extends JavaPlugin implements Listener {
    private static final String PERMISSION = "wp05.acceptance";
    private static final String ENTITY_MARKER = "wp05-acceptance";
    private static final NamespacedKey VERSION_KEY = key("identity_version");
    private static final NamespacedKey DEFINITION_KEY = key("definition_id");
    private static final NamespacedKey INSTANCE_KEY = key("instance_id");
    private static final NamespacedKey REVISION_KEY = key("applied_revision");
    private static final int UUID_BYTES = 16;

    @FunctionalInterface
    private interface AcceptanceAction {
        void run(CommandSender sender, String[] arguments);
    }

    @Override
    public void onEnable() {
        Objects.requireNonNull(getCommand("wp05accept"), "wp05accept command").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("WP-05 deterministic acceptance helper is active; never ship this jar.");
    }

    @Override
    public boolean onCommand(
            CommandSender sender, Command command, String label, String[] arguments) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(arguments, "arguments");
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage("WP-05 acceptance permission denied.");
            return true;
        }
        if (arguments.length == 0) {
            sender.sendMessage("Usage: /wp05accept source|perform|place|duplicate|malform|damage|api|dump|view|cleanup ...");
            return true;
        }
        AcceptanceAction action = commandActions().get(arguments[0].toLowerCase(Locale.ROOT));
        if (action == null) {
            sender.sendMessage("Unknown WP-05 acceptance helper action.");
            return true;
        }
        try {
            action.run(sender, arguments);
        } catch (IllegalArgumentException | IllegalStateException exception) {
            sender.sendMessage("WP05_ACCEPT FAIL " + exception.getMessage());
            getLogger().log(Level.SEVERE, "Acceptance helper action failed", exception);
        }
        return true;
    }

    private Map<String, AcceptanceAction> commandActions() {
        return Map.ofEntries(
                Map.entry("source", this::source),
                Map.entry("perform", this::perform),
                Map.entry("place", this::place),
                Map.entry("duplicate", this::duplicate),
                Map.entry("malform", this::malform),
                Map.entry("damage", this::damage),
                Map.entry("api", this::api),
                Map.entry("dump", this::dump),
                Map.entry("chunkstatus", this::chunkStatus),
                Map.entry("shulkerprobe", (sender, ignored) -> ShulkerDiscoveryProbe.start(this)),
                Map.entry("view", this::view),
                Map.entry("cleanup", (sender, ignored) -> cleanup(sender)));
    }

    /** Read-only observation of the local-container chunks in the disposable rollout world. */
    private void chunkStatus(CommandSender sender, String[] arguments) {
        requireLength(arguments, 1, "chunkstatus");
        World world = Objects.requireNonNull(Bukkit.getWorld("world"), "acceptance world");
        getLogger().info("WP05_ACCEPT CHUNK_STATUS world=" + world.getName()
                + " chest_0_0=" + world.isChunkLoaded(0, 0)
                + " nested_1_0=" + world.isChunkLoaded(1, 0));
        for (int x = 0; x <= 1; x++) {
            if (world.isChunkLoaded(x, 0)) {
                org.bukkit.Chunk chunk = world.getChunkAt(x, 0);
                getLogger().info("WP05_ACCEPT CHUNK_TICKETS chunk_x=" + x
                        + " forced=" + chunk.isForceLoaded()
                        + " plugins=" + chunk.getPluginChunkTickets().stream()
                                .map(org.bukkit.plugin.Plugin::getName).toList());
            }
        }
        sender.sendMessage("WP05_ACCEPT CHUNK_STATUS recorded");
    }

    /** Observe actual unload events rather than assuming a teleport unloaded chunks. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        if (!"world".equals(event.getWorld().getName()) || event.getChunk().getZ() != 0) {
            return;
        }
        int x = event.getChunk().getX();
        if (x == 0 || x == 1) {
            getLogger().info("WP05_ACCEPT CHUNK_UNLOAD world=" + event.getWorld().getName()
                    + " chunk_x=" + x + " chunk_z=0");
        }
    }

    private void source(CommandSender sender, String[] arguments) {
        requireLength(arguments, 3, "source <player> <sword|helmet|food|uncommon>");
        Player player = requirePlayer(arguments[1]);
        ItemStack item = switch (arguments[2].toLowerCase(Locale.ROOT)) {
            case "sword" -> named(Material.DIAMOND_SWORD, "WP05 Sword Source");
            case "helmet" -> named(Material.LEATHER_HELMET, "WP05 Helmet Source");
            case "food" -> named(Material.COOKED_BEEF, "WP05 Food Source");
            case "uncommon" -> uncommonSource();
            default -> throw new IllegalArgumentException("unknown source kind " + arguments[2]);
        };
        player.getInventory().setItemInMainHand(item);
        log("SOURCE", player, arguments[2], describe(item));
        sender.sendMessage("WP05_ACCEPT SOURCE ok player=" + player.getName()
                + " kind=" + arguments[2]);
    }

    private void perform(CommandSender sender, String[] arguments) {
        if (arguments.length < 3) {
            throw new IllegalArgumentException("perform <player> <command...>");
        }
        Player player = requirePlayer(arguments[1]);
        String value = String.join(" ", Arrays.copyOfRange(arguments, 2, arguments.length));
        String normalized = value.startsWith("/") ? value.substring(1) : value;
        boolean accepted = player.performCommand(normalized);
        log("PERFORM", player, normalized, "accepted=" + accepted);
        if (!accepted) {
            throw new IllegalStateException("player command was not accepted: " + normalized);
        }
        sender.sendMessage("WP05_ACCEPT PERFORM ok player=" + player.getName()
                + " command=" + normalized);
    }

    private void place(CommandSender sender, String[] arguments) {
        requireLength(arguments, 3,
                "place <player> <storage|offhand|armor|cursor|ender|chest|drop|frame|glowframe|armorstand|shulker|bundle>");
        Player player = requirePlayer(arguments[1]);
        String destination = arguments[2].toLowerCase(Locale.ROOT);
        Consumer<ItemStack> placement = placementActions(player).get(destination);
        if (placement == null) {
            throw new IllegalArgumentException("unknown destination " + destination);
        }
        ItemStack tracked = takeTrackedStorageItem(player);
        placement.accept(tracked);
        player.closeInventory();
        log("PLACE", player, destination, describe(tracked));
        sender.sendMessage("WP05_ACCEPT PLACE ok player=" + player.getName()
                + " destination=" + destination);
    }

    private Map<String, Consumer<ItemStack>> placementActions(Player player) {
        return Map.ofEntries(
                Map.entry("storage", item -> player.getInventory().setItem(8, item)),
                Map.entry("offhand", item -> player.getInventory().setItemInOffHand(item)),
                Map.entry("armor", item -> player.getInventory().setHelmet(item)),
                Map.entry("cursor", player::setItemOnCursor),
                Map.entry("ender", item -> player.getEnderChest().setItem(0, item)),
                Map.entry("chest", item -> chest(player, 4).getBlockInventory().setItem(0, item)),
                Map.entry("drop", item -> drop(player, item)),
                Map.entry("frame", item -> frame(player, item, false)),
                Map.entry("glowframe", item -> frame(player, item, true)),
                Map.entry("armorstand", item -> armorStand(player, item)),
                Map.entry("shulker", item -> nestedShulker(player, item)),
                Map.entry("bundle", item -> nestedBundle(player, item)));
    }

    private void duplicate(CommandSender sender, String[] arguments) {
        requireLength(arguments, 2, "duplicate <player>");
        Player player = requirePlayer(arguments[1]);
        ItemStack tracked = requireTrackedStorageItem(player).clone();
        if (!player.getInventory().addItem(tracked).isEmpty()) {
            throw new IllegalStateException("no storage slot for duplicate copy");
        }
        player.closeInventory();
        log("DUPLICATE", player, "inventory", describe(tracked));
        sender.sendMessage("WP05_ACCEPT DUPLICATE ok player=" + player.getName());
    }

    private void malform(CommandSender sender, String[] arguments) {
        requireLength(arguments, 2, "malform <player>");
        Player player = requirePlayer(arguments[1]);
        ItemStack malformed = requireTrackedStorageItem(player).clone();
        ItemMeta meta = requireMeta(malformed);
        meta.getPersistentDataContainer().remove(VERSION_KEY);
        applyMeta(malformed, meta);
        if (!player.getInventory().addItem(malformed).isEmpty()) {
            throw new IllegalStateException("no storage slot for malformed copy");
        }
        player.closeInventory();
        log("MALFORM", player, "partial-identity", describe(malformed));
        sender.sendMessage("WP05_ACCEPT MALFORM ok player=" + player.getName());
    }

    private void damage(CommandSender sender, String[] arguments) {
        requireLength(arguments, 2, "damage <player>");
        Player player = requirePlayer(arguments[1]);
        ItemStack tracked = requireTrackedStorageItem(player);
        ItemMeta meta = requireMeta(tracked);
        if (!(meta instanceof Damageable damageable)) {
            throw new IllegalStateException("tracked item is not damageable");
        }
        int maximum = tracked.getType().getMaxDurability();
        if (maximum <= 1) {
            throw new IllegalStateException("tracked material has no durability boundary");
        }
        damageable.setDamage(maximum - 1);
        applyMeta(tracked, meta);
        log("DAMAGE_SETUP", player, "damage=" + (maximum - 1), describe(tracked));
        sender.sendMessage("WP05_ACCEPT DAMAGE ok player=" + player.getName()
                + " damage=" + (maximum - 1));
    }

    private void api(CommandSender sender, String[] arguments) {
        requireLength(arguments, 4, "api <definition-key> <player-uuid> <operation-id>");
        LoreItemsServiceV1 service = Bukkit.getServicesManager().load(LoreItemsServiceV1.class);
        if (service == null) {
            throw new IllegalStateException("LoreItemsServiceV1 is unavailable");
        }
        UUID playerId = UUID.fromString(arguments[2]);
        String definition = arguments[1];
        String operation = arguments[3];
        service.queueDelivery(definition, playerId, operation).whenComplete((result, failure) -> {
            if (failure != null) {
                getLogger().log(Level.SEVERE, "WP05_ACCEPT API failure operation=" + operation,
                        unwrap(failure));
                return;
            }
            getLogger().info("WP05_ACCEPT API result operation=" + operation
                    + " definition=" + definition + " player=" + playerId
                    + " status=" + result.status());
        });
        sender.sendMessage("WP05_ACCEPT API submitted operation=" + operation);
    }

    private void dump(CommandSender sender, String[] arguments) {
        requireLength(arguments, 2, "dump <player>");
        Player player = requirePlayer(arguments[1]);
        ItemStack[] inventoryContents = Objects.requireNonNull(
                player.getInventory().getContents(), "player inventory contents");
        ItemStack[] enderContents = Objects.requireNonNull(
                player.getEnderChest().getContents(), "player Ender Chest contents");
        int count = dumpContents(player, inventoryContents, "DUMP");
        count += dumpContents(player, enderContents, "DUMP_ENDER");
        sender.sendMessage("WP05_ACCEPT DUMP ok player=" + player.getName() + " count=" + count);
    }

    private void view(CommandSender sender, String[] arguments) {
        requireLength(arguments, 2, "view <player>");
        Player player = requirePlayer(arguments[1]);
        var view = player.getOpenInventory();
        var top = view.getTopInventory();
        getLogger().info("WP05_ACCEPT VIEW player=" + player.getName()
                + " uuid=" + player.getUniqueId()
                + " topType=" + top.getType()
                + " topSize=" + top.getSize());
        sender.sendMessage("WP05_ACCEPT VIEW ok player=" + player.getName()
                + " topType=" + top.getType()
                + " topSize=" + top.getSize());
    }

    private int dumpContents(Player player, ItemStack[] contents, String marker) {
        int count = 0;
        for (ItemStack item : contents) {
            if (item != null && hasIdentity(item)) {
                count++;
                getLogger().info("WP05_ACCEPT " + marker + " player=" + player.getName() + ' '
                        + describe(item));
            }
        }
        return count;
    }

    private void cleanup(CommandSender sender) {
        for (World world : Bukkit.getWorlds()) {
            removeMarked(world.getEntitiesByClass(Item.class));
            removeMarked(world.getEntitiesByClass(ItemFrame.class));
            removeMarked(world.getEntitiesByClass(GlowItemFrame.class));
            removeMarked(world.getEntitiesByClass(ArmorStand.class));
        }
        sender.sendMessage("WP05_ACCEPT CLEANUP ok");
    }

    private static <T extends org.bukkit.entity.Entity> void removeMarked(Iterable<T> entities) {
        for (T entity : entities) {
            if (entity.getScoreboardTags().contains(ENTITY_MARKER)) {
                entity.remove();
            }
        }
    }

    private void drop(Player player, ItemStack tracked) {
        Item item = player.getWorld().dropItem(player.getLocation().clone().add(2.0, 1.0, 0.0), tracked);
        item.addScoreboardTag(ENTITY_MARKER);
    }

    private void frame(Player player, ItemStack tracked, boolean glow) {
        Location base = testBase(player).add(glow ? 10.0 : 8.0, 1.0, 0.0);
        Block support = base.clone().add(0.0, 0.0, 1.0).getBlock();
        support.setType(Material.STONE);
        if (glow) {
            GlowItemFrame itemFrame = player.getWorld().spawn(base, GlowItemFrame.class);
            itemFrame.addScoreboardTag(ENTITY_MARKER);
            itemFrame.setItem(tracked, false);
            return;
        }
        ItemFrame itemFrame = player.getWorld().spawn(base, ItemFrame.class);
        itemFrame.addScoreboardTag(ENTITY_MARKER);
        itemFrame.setItem(tracked, false);
    }

    private void armorStand(Player player, ItemStack tracked) {
        ArmorStand stand = player.getWorld().spawn(testBase(player).add(12.0, 0.0, 0.0), ArmorStand.class);
        stand.addScoreboardTag(ENTITY_MARKER);
        stand.getEquipment().setItemInMainHand(tracked);
    }

    private void nestedShulker(Player player, ItemStack tracked) {
        ItemStack outer = new ItemStack(Material.SHULKER_BOX);
        ItemMeta generic = requireMeta(outer);
        if (!(generic instanceof BlockStateMeta meta)) {
            throw new IllegalStateException("shulker item did not expose BlockStateMeta");
        }
        if (!(meta.getBlockState() instanceof ShulkerBox shulker)) {
            throw new IllegalStateException("shulker BlockStateMeta did not expose ShulkerBox");
        }
        shulker.getInventory().setItem(0, tracked);
        meta.setBlockState(shulker);
        applyMeta(outer, meta);
        chest(player, 14).getBlockInventory().setItem(0, outer);
    }

    private void nestedBundle(Player player, ItemStack tracked) {
        ItemStack outer = new ItemStack(Material.BUNDLE);
        ItemMeta generic = requireMeta(outer);
        if (!(generic instanceof BundleMeta bundle)) {
            throw new IllegalStateException("bundle item did not expose BundleMeta");
        }
        bundle.addItem(tracked);
        applyMeta(outer, bundle);
        chest(player, 16).getBlockInventory().setItem(0, outer);
    }

    private Chest chest(Player player, int offset) {
        Location location = testBase(player).add(offset, 0.0, 0.0);
        Block block = location.getBlock();
        block.setType(Material.CHEST);
        if (!(block.getState() instanceof Chest chest)) {
            throw new IllegalStateException("acceptance chest did not initialize");
        }
        return chest;
    }

    private static Location testBase(Player player) {
        Location spawn = player.getWorld().getSpawnLocation().clone();
        return new Location(player.getWorld(), spawn.getBlockX(), Math.max(70, spawn.getBlockY()), spawn.getBlockZ());
    }

    private static ItemStack named(Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = requireMeta(item);
        meta.displayName(Component.text(name));
        meta.lore(List.of(Component.text("WP-05 acceptance source"), Component.text("preserve-components")));
        applyMeta(item, meta);
        return item;
    }

    private static ItemStack uncommonSource() {
        ItemStack item = named(Material.FIREWORK_ROCKET, "WP05 Uncommon Source");
        ItemMeta generic = requireMeta(item);
        if (!(generic instanceof FireworkMeta firework)) {
            throw new IllegalStateException("firework rocket did not expose FireworkMeta");
        }
        firework.setPower(2);
        firework.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.STAR)
                .withColor(Color.AQUA)
                .withFade(Color.FUCHSIA)
                .trail(true)
                .flicker(true)
                .build());
        applyMeta(item, firework);
        return item;
    }

    private Player requirePlayer(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            throw new IllegalArgumentException("player is not online: " + name);
        }
        return player;
    }

    private static ItemStack takeTrackedStorageItem(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storageContents = Objects.requireNonNull(
                inventory.getStorageContents(), "player storage contents");
        for (int slot = 0; slot < storageContents.length; slot++) {
            ItemStack item = storageContents[slot];
            if (item != null && hasIdentity(item)) {
                inventory.setItem(slot, null);
                return item;
            }
        }
        throw new IllegalStateException("no tracked item in player storage");
    }

    private static ItemStack requireTrackedStorageItem(Player player) {
        ItemStack[] storageContents = Objects.requireNonNull(
                player.getInventory().getStorageContents(), "player storage contents");
        for (ItemStack item : storageContents) {
            if (item != null && hasIdentity(item)) {
                return item;
            }
        }
        throw new IllegalStateException("no tracked item in player storage");
    }

    private static boolean hasIdentity(ItemStack item) {
        if (item.getType().isAir() || !item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(INSTANCE_KEY);
    }

    private static ItemMeta requireMeta(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            throw new IllegalStateException("item metadata unavailable for " + item.getType());
        }
        return meta;
    }

    private static void applyMeta(ItemStack item, ItemMeta meta) {
        if (!item.setItemMeta(meta)) {
            throw new IllegalStateException("item metadata was rejected for " + item.getType());
        }
    }

    private static String describe(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return "material=" + item.getType() + " meta=none";
        }
        Integer version = meta.getPersistentDataContainer().get(
                VERSION_KEY, org.bukkit.persistence.PersistentDataType.INTEGER);
        byte[] definition = meta.getPersistentDataContainer().get(
                DEFINITION_KEY, org.bukkit.persistence.PersistentDataType.BYTE_ARRAY);
        byte[] instance = meta.getPersistentDataContainer().get(
                INSTANCE_KEY, org.bukkit.persistence.PersistentDataType.BYTE_ARRAY);
        Long revision = meta.getPersistentDataContainer().get(
                REVISION_KEY, org.bukkit.persistence.PersistentDataType.LONG);
        return "material=" + item.getType()
                + " amount=" + item.getAmount()
                + " maxStack=" + item.getMaxStackSize()
                + " identityVersion=" + version
                + " definition=" + uuidText(definition)
                + " instance=" + uuidText(instance)
                + " revision=" + revision;
    }

    private void log(String action, Player player, String detail, String item) {
        getLogger().info("WP05_ACCEPT " + action + " player=" + player.getName()
                + " uuid=" + player.getUniqueId() + " detail=" + detail + ' ' + item);
    }

    private static String uuidText(byte[] value) {
        if (value == null) {
            return "none";
        }
        if (value.length != UUID_BYTES) {
            return "malformed-" + value.length;
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong()).toString();
    }

    private static NamespacedKey key(String value) {
        NamespacedKey parsed = NamespacedKey.fromString("enthusialoreitems:" + value);
        if (parsed == null) {
            throw new IllegalStateException("invalid static namespaced key " + value);
        }
        return parsed;
    }

    private static void requireLength(String[] arguments, int length, String usage) {
        if (arguments.length != length) {
            throw new IllegalArgumentException(usage);
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException exception && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }
}
