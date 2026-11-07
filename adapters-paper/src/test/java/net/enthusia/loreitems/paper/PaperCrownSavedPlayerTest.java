package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.UUID;
import net.kyori.adventure.nbt.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperCrownSavedPlayerTest {
    @TempDir Path temporary;
    @Test void resolvesVerifiedSaveLayoutsAndRejectsUnknownDimensions() {
        assertEquals(temporary.resolve("playerdata"), PaperCrownSavedPlayer.playerDirectory(temporary,"1.21.11"));
        assertEquals(temporary.resolve("players/data"), PaperCrownSavedPlayer.playerDirectory(
                temporary.resolve("dimensions/minecraft/overworld"),"26.2"));
        assertThrows(IllegalArgumentException.class, () -> PaperCrownSavedPlayer.playerDirectory(temporary,"26.2"));
        assertThrows(IllegalArgumentException.class, () -> PaperCrownSavedPlayer.playerDirectory(
                temporary.resolve("dimensions/minecraft/the_nether"),"26.2"));
        assertThrows(IllegalArgumentException.class, () -> PaperCrownSavedPlayer.playerDirectory(temporary,"future"));
    }
    @Test void detectsNestedDuplicatesAndConflictingIdentities() {
        UUID instance=UUID.randomUUID(), definition=UUID.randomUUID();
        var item=CompoundBinaryTag.builder().putByteArray("enthusialoreitems:instance_id",bytes(instance))
                .putByteArray("enthusialoreitems:definition_id",bytes(definition)).build();
        assertEquals(1,PaperCrownSavedPlayer.count(item,instance,definition,0));
        var twice=ListBinaryTag.from(java.util.List.of(item,item));
        assertEquals(2,PaperCrownSavedPlayer.count(twice,instance,definition,0));
        assertThrows(IllegalArgumentException.class,()->PaperCrownSavedPlayer.count(item,instance,UUID.randomUUID(),0));
        assertThrows(IllegalArgumentException.class,()->PaperCrownSavedPlayer.count(item,instance,definition,65));
    }
    @Test void readsActualCompressedSaveAndRejectsMissingOrMalformedFile() throws Exception {
        Path save=temporary.resolve("player.dat");
        assertThrows(java.io.IOException.class,()->PaperCrownSavedPlayer.read(save));
        var data=CompoundBinaryTag.builder().putString("marker","saved").build();
        BinaryTagIO.writer().write(data,save,BinaryTagIO.Compression.GZIP);
        assertEquals(data,PaperCrownSavedPlayer.read(save));
        java.nio.file.Files.writeString(save,"truncated");
        assertThrows(java.io.IOException.class,()->PaperCrownSavedPlayer.read(save));
    }
    @Test void fallbackContainingCrownOrStaleMarkersCannotReleaseCustody() {
        UUID instance=UUID.randomUUID(),definition=UUID.randomUUID();
        var saved=CompoundBinaryTag.builder().putIntArray("UUID",new int[]{1,2,3,4})
                .put("Inventory",ListBinaryTag.empty()).put("BukkitValues",CompoundBinaryTag.builder()
                        .putString("enthusialoreitems:crown_reclaimed","operation").build()).build();
        assertTrue(PaperCrownSavedPlayer.fallbackAgrees(saved,saved,instance,definition));
        assertFalse(PaperCrownSavedPlayer.fallbackAgrees(saved,saved.remove("BukkitValues"),instance,definition));
        var crown=CompoundBinaryTag.builder().putByteArray("enthusialoreitems:instance_id",bytes(instance))
                .putByteArray("enthusialoreitems:definition_id",bytes(definition)).build();
        assertFalse(PaperCrownSavedPlayer.fallbackAgrees(saved,saved.put("equipment",crown),instance,definition));
    }
    private static byte[] bytes(UUID id) {
        return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array();
    }
}
