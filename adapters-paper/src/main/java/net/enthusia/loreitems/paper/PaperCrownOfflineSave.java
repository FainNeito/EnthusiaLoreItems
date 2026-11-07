package net.enthusia.loreitems.paper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.Arrays;
import java.util.UUID;
import net.enthusia.loreitems.application.CrownEquipmentRecovery;
import net.kyori.adventure.nbt.*;

/** Offline save exchange. The caller must hold login exclusion until both saved copies are verified. */
final class PaperCrownOfflineSave {
    private static final String PREFIX = "enthusialoreitems:";
    record Prepared(Path primary, byte[] primaryBefore, Path backup, byte[] backupBefore,
                    byte[] removed, byte[] crown, CrownEquipmentRecovery.Reclamation request) {}

    static Prepared prepare(Path directory, CrownEquipmentRecovery.Reclamation request) throws IOException {
        var source = request.source().reservation();
        Path primary = directory.resolve(source.holderId() + ".dat");
        Path backup = directory.resolve(source.holderId() + ".dat_old");
        byte[] before = bytes(primary);
        byte[] previous = Files.exists(backup) ? bytes(backup) : null;
        var saved = decode(before);
        verifyOwner(saved,source.holderId());
        var markers = saved.getCompound("BukkitValues");
        if (!escrowMatches(markers.getCompound(PREFIX+"crown_exchange"), request)
                || markers.contains(PREFIX+"crown_incoming") || markers.contains(PREFIX+"crown_released"))
            throw new IOException("Offline custody marker mismatch");
        byte[] crown = equippedCrown(saved, markers, request);
        if (previous != null) verifyBackup(decode(previous), request);
        var equipment = saved.getCompound("equipment");
        // A resumed removal must preserve a subsequently equipped unrelated helmet.
        if (crown != null) equipment=equipment.remove("head");
        var changed = saved.put("equipment",equipment).put("BukkitValues",
                markers.putString(PREFIX+"crown_reclaimed",request.operation().toString()));
        return new Prepared(primary,before,backup,previous,encode(changed),crown,request);
    }

    /** The saved escrow names this exact committed exchange and its sacrificed helmet is already consumed. */
    private static boolean escrowMatches(CompoundBinaryTag escrow, CrownEquipmentRecovery.Reclamation request) {
        var source = request.source().reservation();
        return source.operationId().toString().equals(escrow.getString(PREFIX+"operation"))
                && source.instanceId().toString().equals(escrow.getString(PREFIX+"instance"))
                && source.holderId().toString().equals(escrow.getString(PREFIX+"holder"))
                && source.generation()==escrow.getLong(PREFIX+"generation",-1)
                && request.source().originalDigest().equals(escrow.getString(PREFIX+"consumed"))
                && !escrow.contains(PREFIX+"original");
    }

    /** The encoded equipped Crown to remove, or null when an earlier attempt already removed it from this save. */
    private static byte[] equippedCrown(CompoundBinaryTag saved, CompoundBinaryTag markers, CrownEquipmentRecovery.Reclamation request)
            throws IOException {
        var source = request.source().reservation();
        int count = PaperCrownSavedPlayer.count(saved,source.instanceId(),request.source().definition(),0);
        boolean removed = request.operation().toString().equals(markers.getString(PREFIX+"crown_reclaimed"));
        if (count == 1 && !removed && !request.held()) {
            var head = saved.getCompound("equipment").getCompound("head");
            if (!head.getString("id").equals("minecraft:netherite_helmet") || head.getInt("count")!=1
                    || PaperCrownSavedPlayer.count(head,source.instanceId(),request.source().definition(),0)!=1)
                throw new IOException("Offline Crown is not uniquely equipped");
            return encode(CompoundBinaryTag.builder().put(head).putInt("DataVersion",saved.getInt("DataVersion")).build());
        }
        if (count != 0 || !removed) throw new IOException("Offline Crown location is uncertain");
        return null;
    }

    private static void verifyBackup(CompoundBinaryTag prior, CrownEquipmentRecovery.Reclamation request) throws IOException {
        var source = request.source().reservation();
        verifyOwner(prior,source.holderId());
        if (PaperCrownSavedPlayer.count(prior,source.instanceId(),request.source().definition(),0)>1)
            throw new IOException("Duplicate Crown in backup player save");
    }

    static void apply(Prepared prepared, Path evidence) throws IOException {
        if (!Arrays.equals(prepared.primaryBefore(),bytes(prepared.primary()))
                || !Arrays.equals(prepared.backupBefore(),Files.exists(prepared.backup()) ? bytes(prepared.backup()) : null))
            throw new IOException("Offline player save changed before exchange");
        Files.createDirectories(evidence);
        preserve(evidence,prepared.primaryBefore());
        if(prepared.backupBefore()!=null) preserve(evidence,prepared.backupBefore());
        // Replace fallback first. A restart between writes still has the original in the primary file and escrow.
        replace(prepared.backup(),prepared.removed());
        replace(prepared.primary(),prepared.removed());
        if (!Arrays.equals(prepared.removed(),bytes(prepared.primary()))
                || !Arrays.equals(prepared.removed(),bytes(prepared.backup())))
            throw new IOException("Offline save readback mismatch");
    }

    private static void preserve(Path directory, byte[] bytes) throws IOException {
        String hash;
        try { hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
        Path file=directory.resolve(hash+".dat.evidence");
        if(Files.exists(file)) {
            if(!Arrays.equals(bytes,Files.readAllBytes(file))) throw new IOException("Evidence collision");
        } else {
            Files.write(file,bytes,StandardOpenOption.CREATE_NEW);
            try(var channel=FileChannel.open(file,StandardOpenOption.WRITE)){channel.force(true);}
        }
    }

    private static void replace(Path target, byte[] bytes) throws IOException {
        Path temporary=target.resolveSibling(target.getFileName()+".crown-"+UUID.randomUUID()+".tmp");
        Files.write(temporary,bytes,StandardOpenOption.CREATE_NEW);
        try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE)){channel.force(true);}
        Files.move(temporary,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] bytes(Path file) throws IOException {
        if(Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file)>8388608)
            throw new IOException("Player save unavailable or unsafe");
        return Files.readAllBytes(file);
    }
    private static CompoundBinaryTag decode(byte[] bytes) throws IOException {
        return BinaryTagIO.reader(8388608).read(new ByteArrayInputStream(bytes),BinaryTagIO.Compression.GZIP);
    }
    private static byte[] encode(CompoundBinaryTag value) throws IOException {
        var output=new ByteArrayOutputStream();
        BinaryTagIO.writer().write(value,output,BinaryTagIO.Compression.GZIP);
        if(output.size()>8388608) throw new IOException("Player save exceeds limit");
        return output.toByteArray();
    }
    private static void verifyOwner(CompoundBinaryTag saved, UUID owner) throws IOException {
        int[] expected={(int)(owner.getMostSignificantBits()>>>32),(int)owner.getMostSignificantBits(),
                (int)(owner.getLeastSignificantBits()>>>32),(int)owner.getLeastSignificantBits()};
        if(!Arrays.equals(expected,saved.getIntArray("UUID")) || !saved.contains("Inventory") || saved.getInt("DataVersion")<=0)
            throw new IOException("Offline player identity mismatch");
    }
}
