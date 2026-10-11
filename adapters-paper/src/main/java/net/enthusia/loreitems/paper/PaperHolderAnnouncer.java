package net.enthusia.loreitems.paper;

import io.papermc.paper.event.player.PlayerInventorySlotChangeEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Broadcasts "The <item> has a new bearer: <player>" when a different player comes to hold an
 * item whose template carries the {@code enthusialoreitems:announce_holder} marker. Holder changes
 * inside the cooldown collapse into one later message about whoever holds it then; the last
 * announced holder survives restarts. Creative, spectator and vanished players never announce.
 */
public final class PaperHolderAnnouncer implements Listener, AutoCloseable {
    public static final NamespacedKey KEY = Objects.requireNonNull(
            NamespacedKey.fromString("enthusialoreitems:announce_holder"),
            "Static LoreItems namespaced key must be valid");
    static final String MESSAGE =
            "<#FF9B00>The <item> has a new bearer: <#FFE08A><player></#FFE08A>";
    static final Duration COOLDOWN = Duration.ofMinutes(2);
    private static final long MILLIS_PER_TICK = 50L;

    private final Plugin plugin;
    private final Path stateFile;
    private final LongSupplier nowMillis;
    private final PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();
    // Main-thread only: events, scheduled checks and cooldown flushes all run on the server thread.
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private final Map<UUID, Holder> holders = new HashMap<>();
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private final Set<UUID> pendingChecks = new HashSet<>();
    private boolean closed;

    public PaperHolderAnnouncer(Plugin plugin, Path stateFile) {
        this(plugin, stateFile, System::currentTimeMillis);
    }

    PaperHolderAnnouncer(Plugin plugin, Path stateFile, LongSupplier nowMillis) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile");
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");
    }

    public void start() {
        load();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSlotChange(PlayerInventorySlotChangeEvent event) {
        if (!closed && isAnnounced(event.getNewItemStack())
                && pendingChecks.add(event.getPlayer().getUniqueId())) {
            UUID playerId = event.getPlayer().getUniqueId();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                pendingChecks.remove(playerId);
                Player player = plugin.getServer().getPlayer(playerId);
                if (player != null) {
                    checkHolder(player);
                }
            });
        }
    }

    /** Called once the tick ends, so swaps and moves have settled. */
    void checkHolder(Player player) {
        if (closed || isStaffHidden(player)) {
            return;
        }
        for (ItemStack item : player.getInventory().getContents()) {
            if (isAnnounced(item)
                    && identityCodec.readIdentity(item) instanceof ItemIdentityReadResult.Tracked tracked) {
                observe(tracked.identity().instanceId().value(), player, itemName(item));
            }
        }
    }

    private void observe(UUID instanceId, Player player, String itemName) {
        Holder holder = holders.computeIfAbsent(instanceId, ignored -> new Holder());
        UUID playerId = player.getUniqueId();
        if (holder.task != null) {
            holder.pendingId = playerId;
            holder.pendingName = player.getName();
            holder.itemName = itemName;
            if (playerId.equals(holder.announcedId)) {
                holder.cancelPending();
            }
            return;
        }
        if (playerId.equals(holder.announcedId)) {
            return;
        }
        long remaining = holder.announcedAt + COOLDOWN.toMillis() - nowMillis.getAsLong();
        if (holder.announcedId == null || remaining <= 0) {
            announce(holder, playerId, player.getName(), itemName);
            return;
        }
        holder.pendingId = playerId;
        holder.pendingName = player.getName();
        holder.itemName = itemName;
        holder.task = plugin.getServer().getScheduler().runTaskLater(
                plugin, () -> flush(holder), remaining / MILLIS_PER_TICK + 1);
    }

    private void flush(Holder holder) {
        UUID pendingId = holder.pendingId;
        String pendingName = holder.pendingName;
        holder.task = null;
        holder.pendingId = null;
        if (!closed && pendingId != null && !pendingId.equals(holder.announcedId)) {
            announce(holder, pendingId, pendingName, holder.itemName);
        }
    }

    private void announce(Holder holder, UUID playerId, String playerName, String itemName) {
        holder.announcedId = playerId;
        holder.announcedAt = nowMillis.getAsLong();
        Component message = MiniMessage.miniMessage().deserialize(
                MESSAGE,
                Placeholder.unparsed("item", itemName),
                Placeholder.unparsed("player", playerName));
        plugin.getServer().broadcast(message);
        save();
    }

    private static boolean isAnnounced(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        var meta = item.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(KEY);
    }

    // Vanish plugins (SuperVanish, staff tools) still flag players with the "vanished" metadata.
    @SuppressWarnings("deprecation")
    private static boolean isStaffHidden(Player player) {
        GameMode mode = player.getGameMode();
        return mode == GameMode.CREATIVE
                || mode == GameMode.SPECTATOR
                || player.getMetadata("vanished").stream().anyMatch(MetadataValue::asBoolean);
    }

    private static String itemName(ItemStack item) {
        var meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        }
        if (meta != null && meta.hasItemName()) {
            return PlainTextComponentSerializer.plainText().serialize(meta.itemName());
        }
        String material = item.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(material.charAt(0)) + material.substring(1);
    }

    private void load() {
        if (!Files.isRegularFile(stateFile)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(stateFile, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException exception) {
            plugin.getLogger().log(Level.WARNING, "Could not read holder announcements; starting fresh.", exception);
            return;
        }
        for (String key : yaml.getKeys(false)) {
            String holderId = yaml.getString(key + ".holder");
            if (holderId == null) {
                continue;
            }
            try {
                Holder holder = new Holder();
                holder.announcedId = UUID.fromString(holderId);
                holder.announcedAt = yaml.getLong(key + ".at");
                holders.put(UUID.fromString(key), holder);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipping malformed holder announcement entry " + key);
            }
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        holders.forEach((id, entry) -> {
            if (entry.announcedId != null) {
                yaml.set(id + ".holder", entry.announcedId.toString());
                yaml.set(id + ".at", entry.announcedAt);
            }
        });
        String snapshot = yaml.saveToString();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                Files.createDirectories(stateFile.getParent());
                Files.writeString(stateFile, snapshot, StandardCharsets.UTF_8);
            } catch (IOException exception) {
                plugin.getLogger().log(Level.WARNING, "Could not save holder announcements.", exception);
            }
        });
    }

    @Override
    public void close() {
        closed = true;
        holders.values().forEach(Holder::cancelPending);
        pendingChecks.clear();
        HandlerList.unregisterAll(this);
    }

    private static final class Holder {
        private UUID announcedId;
        private long announcedAt;
        private UUID pendingId;
        private String pendingName;
        private String itemName;
        private BukkitTask task;

        private void cancelPending() {
            if (task != null) {
                task.cancel();
                task = null;
            }
            pendingId = null;
        }
    }
}
