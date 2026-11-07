package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import net.enthusia.loreitems.application.CrownEquipmentRecovery;
import net.enthusia.loreitems.domain.ExclusiveCustodyReservation;
import net.kyori.adventure.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperCrownOfflineSaveTest {
    @TempDir Path directory;
    final UUID holder=UUID.randomUUID();
    final UUID instance=UUID.randomUUID();
    final UUID definition=UUID.randomUUID();
    final UUID source=UUID.randomUUID();
    final UUID operation=UUID.randomUUID();
    final String prefix="enthusialoreitems:";
    CrownEquipmentRecovery.Reclamation request() {
        return new CrownEquipmentRecovery.Reclamation(operation,new CrownEquipmentRecovery.Receipt(
                new ExclusiveCustodyReservation(source,instance,holder,2,ExclusiveCustodyReservation.State.RESERVED),
                definition,"consumed-digest"),false);
    }
    CompoundBinaryTag save() {
        var identity=CompoundBinaryTag.builder().putByteArray(prefix+"instance_id",uuidBytes(instance))
                .putByteArray(prefix+"definition_id",uuidBytes(definition)).build();
        var head=CompoundBinaryTag.builder().putString("id","minecraft:netherite_helmet").putInt("count",1)
                .put("components",CompoundBinaryTag.builder().put("minecraft:custom_data",
                        CompoundBinaryTag.builder().put("PublicBukkitValues",identity).build()).build()).build();
        var escrow=CompoundBinaryTag.builder().putString(prefix+"operation",source.toString())
                .putString(prefix+"instance",instance.toString()).putString(prefix+"holder",holder.toString())
                .putLong(prefix+"generation",2).putString(prefix+"consumed","consumed-digest").build();
        return CompoundBinaryTag.builder().putIntArray("UUID",new int[]{(int)(holder.getMostSignificantBits()>>>32),
                (int)holder.getMostSignificantBits(),(int)(holder.getLeastSignificantBits()>>>32),(int)holder.getLeastSignificantBits()})
                .putInt("DataVersion",4903).put("Inventory",ListBinaryTag.empty()).putString("unrelated","preserved")
                .put("equipment",CompoundBinaryTag.builder().put("head",head).build())
                .put("BukkitValues",CompoundBinaryTag.builder().put(prefix+"crown_exchange",escrow).build()).build();
    }
    Path primary(){return directory.resolve(holder+".dat");}
    Path backup(){return directory.resolve(holder+".dat_old");}
    void write(Path file,CompoundBinaryTag value) throws Exception {BinaryTagIO.writer().write(value,file,BinaryTagIO.Compression.GZIP);}
    @Test void failedReplaceLeavesNoTemporaryCopyBesideTheSave() throws Exception {
        Path target=directory.resolve(holder+".dat");
        Files.createDirectories(target.resolve("occupied"));
        assertThrows(java.io.IOException.class, () -> PaperCrownOfflineSave.replace(target,new byte[]{1,2,3}));
        try (var siblings=Files.list(directory)) {
            assertTrue(siblings.noneMatch(path -> path.getFileName().toString().contains(".crown-")));
        }
    }
    @Test void removesBothLoadableCopiesAndPreservesOriginalEvidenceAndUnrelatedData() throws Exception {        write(primary(),save());write(backup(),save().putInt("older",1));
        byte[] original=Files.readAllBytes(primary());
        var prepared=PaperCrownOfflineSave.prepare(directory,request());
        assertNotNull(prepared.crown());
        PaperCrownOfflineSave.apply(prepared,directory.resolve("evidence"));
        assertArrayEquals(Files.readAllBytes(primary()),Files.readAllBytes(backup()));
        var after=PaperCrownSavedPlayer.read(primary());
        assertEquals(0,PaperCrownSavedPlayer.count(after,instance,definition,0));
        assertEquals("preserved",after.getString("unrelated"));
        try(var files=Files.list(directory.resolve("evidence"))) {
            assertTrue(files.anyMatch(file->{try{return Arrays.equals(original,Files.readAllBytes(file));}catch(Exception e){return false;}}));
        }
        assertNull(PaperCrownOfflineSave.prepare(directory,request()).crown());
    }
    @Test void changedSaveBeforeWriteIsNeverOverwritten() throws Exception {
        write(primary(),save());var prepared=PaperCrownOfflineSave.prepare(directory,request());
        write(primary(),save().putInt("changed",1));
        assertThrows(java.io.IOException.class,()->PaperCrownOfflineSave.apply(prepared,directory.resolve("evidence")));
        assertEquals(1,PaperCrownSavedPlayer.read(primary()).getInt("changed"));
        assertFalse(Files.exists(backup()));
    }
    @Test void wrongOwnerAndDuplicateAndUncommittedHelmetAreHeld() throws Exception {
        write(primary(),save().putIntArray("UUID",new int[]{0,0,0,0}));
        assertThrows(java.io.IOException.class,()->PaperCrownOfflineSave.prepare(directory,request()));
        write(primary(),save().put("Inventory",ListBinaryTag.from(List.of(save().getCompound("equipment").getCompound("head")))));
        assertThrows(java.io.IOException.class,()->PaperCrownOfflineSave.prepare(directory,request()));
        var saved=save();var markers=saved.getCompound("BukkitValues");
        write(primary(),saved.put("BukkitValues",markers.put(prefix+"crown_exchange",markers.getCompound(prefix+"crown_exchange").putByteArray(prefix+"original",new byte[]{1}))));
        assertThrows(java.io.IOException.class,()->PaperCrownOfflineSave.prepare(directory,request()));
    }
    @Test void resumesAfterFallbackWriteWithoutRecreatingCrown() throws Exception {
        write(primary(),save());var prepared=PaperCrownOfflineSave.prepare(directory,request());
        Files.write(backup(),prepared.removed());
        var retry=PaperCrownOfflineSave.prepare(directory,request());
        assertNotNull(retry.crown());
        PaperCrownOfflineSave.apply(retry,directory.resolve("evidence"));
        assertNull(PaperCrownOfflineSave.prepare(directory,request()).crown());
    }
    private static byte[] uuidBytes(UUID value){return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();}
}
