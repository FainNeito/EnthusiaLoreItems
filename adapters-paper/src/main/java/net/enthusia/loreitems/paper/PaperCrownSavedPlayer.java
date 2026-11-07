package net.enthusia.loreitems.paper;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import net.kyori.adventure.nbt.*;
import org.bukkit.entity.Player;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;

/** Bounded readback of Paper's saved player file, with no player-data writes. */
final class PaperCrownSavedPlayer {
    private static final String PREFIX = "enthusialoreitems:";
    private static final NamespacedKey ESCROW = new NamespacedKey("enthusialoreitems","crown_exchange");

    /** Rotate the already verified absent primary into Paper's fallback before releasing custody. */
    boolean confirmAbsenceIncludingFallback(Player player, UUID instance, UUID definition) {
        if (!agrees(player,instance,definition,false)) return false;
        player.saveData();
        if (!agrees(player,instance,definition,false)) return false;
        try {
            Path directory=playerDirectory(player.getServer().getWorlds().getFirst().getWorldFolder().toPath(),
                    player.getServer().getMinecraftVersion());
            return fallbackAgrees(read(directory.resolve(player.getUniqueId()+".dat")),
                    read(directory.resolve(player.getUniqueId()+".dat_old")),instance,definition);
        } catch (java.io.IOException | RuntimeException uncertain) { return false; }
    }

    static boolean fallbackAgrees(CompoundBinaryTag primary, CompoundBinaryTag backup, UUID instance, UUID definition) {
        return Arrays.equals(primary.getIntArray("UUID"),backup.getIntArray("UUID"))
                && backup.contains("Inventory") && count(backup,instance,definition,0)==0
                && primary.getCompound("BukkitValues").equals(backup.getCompound("BukkitValues"));
    }

    boolean agrees(Player player, UUID instance, UUID definition, boolean equipped) {
        try {
            var version = player.getServer().getMinecraftVersion();
            Path root = player.getServer().getWorlds().getFirst().getWorldFolder().toPath();
            Path file = playerDirectory(root, version).resolve(player.getUniqueId() + ".dat");
            var saved = read(file);
            if (!ownedBy(player.getUniqueId(), saved)) return false;
            var persisted = saved.getCompound("BukkitValues");
            var live = player.getPersistentDataContainer();
            var escrow = live.get(ESCROW,PersistentDataType.TAG_CONTAINER);
            if (escrow == null) return false;
            // Placement is checked last: its identity count throws on conflicting identities.
            return escrowSaved(escrow, persisted.getCompound(PREFIX+"crown_exchange")) && markersSaved(live, persisted)
                    && placementSaved(saved, instance, definition, equipped);
        } catch (java.io.IOException | RuntimeException uncertain) {
            player.getServer().getLogger().warning("Crown saved inventory readback held: " + uncertain.getMessage());
            return false;
        }
    }

    private static boolean ownedBy(UUID owner, CompoundBinaryTag saved) {
        var expected = new int[]{(int)(owner.getMostSignificantBits() >>> 32), (int)owner.getMostSignificantBits(),
                (int)(owner.getLeastSignificantBits() >>> 32), (int)owner.getLeastSignificantBits()};
        return Arrays.equals(expected,saved.getIntArray("UUID")) && saved.contains("Inventory");
    }

    /** Every live escrow field, including an absent original or consumed digest, matches the saved escrow. */
    private static boolean escrowSaved(org.bukkit.persistence.PersistentDataContainer escrow, CompoundBinaryTag stored) {
        for (String name : java.util.List.of("operation","holder","instance")) {
            String value = escrow.get(new NamespacedKey("enthusialoreitems",name),PersistentDataType.STRING);
            if (value == null || !value.equals(stored.getString(PREFIX+name))) return false;
        }
        if (!java.util.Objects.equals(escrow.get(new NamespacedKey("enthusialoreitems","generation"),PersistentDataType.LONG),
                stored.getLong(PREFIX+"generation",-1))) return false;
        byte[] original = escrow.get(new NamespacedKey("enthusialoreitems","original"),PersistentDataType.BYTE_ARRAY);
        if (original == null ? stored.contains(PREFIX+"original") : !Arrays.equals(original,stored.getByteArray(PREFIX+"original"))) return false;
        String consumed = escrow.get(new NamespacedKey("enthusialoreitems","consumed"),PersistentDataType.STRING);
        return consumed == null ? !stored.contains(PREFIX+"consumed") : consumed.equals(stored.getString(PREFIX+"consumed"));
    }

    private static boolean markersSaved(org.bukkit.persistence.PersistentDataContainer live, CompoundBinaryTag persisted) {
        for (String name : java.util.List.of("crown_released","crown_incoming","crown_reclaimed")) {
            String value = live.get(new NamespacedKey("enthusialoreitems",name),PersistentDataType.STRING);
            if (value == null ? persisted.contains(PREFIX+name) : !value.equals(persisted.getString(PREFIX+name))) return false;
        }
        return true;
    }

    private static boolean placementSaved(CompoundBinaryTag saved, UUID instance, UUID definition, boolean equipped) {
        if (count(saved,instance,definition,0) != (equipped ? 1 : 0)) return false;
        var head = saved.getCompound("equipment").getCompound("head");
        return !equipped || head.getString("id").equals("minecraft:netherite_helmet")
                && head.getInt("count")==1 && count(head,instance,definition,0)==1;
    }

    static Path playerDirectory(Path worldFolder, String version) {
        Path root = worldFolder.toAbsolutePath().normalize();
        if (version.equals("1.21.11")) return root.resolve("playerdata");
        if (version.equals("26.2") && root.endsWith(Path.of("dimensions", "minecraft", "overworld"))) {
            return root.getParent().getParent().getParent().resolve("players/data");
        }
        throw new IllegalArgumentException("Unverified player save layout");
    }

    static CompoundBinaryTag read(Path file) throws java.io.IOException {
        if (!Files.isRegularFile(file) || Files.size(file)>8388608) throw new java.io.IOException("Player save unavailable");
        return BinaryTagIO.reader(8388608).read(file,BinaryTagIO.Compression.GZIP);
    }

    static int count(BinaryTag tag, UUID instance, UUID definition, int depth) {
        if (depth > 64) throw new IllegalArgumentException("Nested save exceeds limit");
        int count=0;
        if (tag instanceof CompoundBinaryTag compound) {
            boolean hasInstance = Arrays.equals(bytes(instance),compound.getByteArray(PREFIX+"instance_id"));
            boolean hasDefinition = Arrays.equals(bytes(definition),compound.getByteArray(PREFIX+"definition_id"));
            if (hasInstance != hasDefinition) throw new IllegalArgumentException("Conflicting Crown identity");
            if (hasInstance) count++;
            for (var entry : compound) count+=count(entry.getValue(),instance,definition,depth+1);
        } else if (tag instanceof ListBinaryTag list) {
            for (var entry : list) count+=count(entry,instance,definition,depth+1);
        }
        return count;
    }

    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
