package net.enthusia.loreitems.paper;

import java.util.UUID;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

/** Deferred bounded scan request that never force-loads an inaccessible chunk or player. */
record PaperTrackingScanRequest(
        UUID playerId,
        boolean unique,
        ChunkReference chunk,
        TrackingObservationUseCase.Presence presence,
        String source) {

    static PaperTrackingScanRequest player(UUID playerId, boolean unique, String source) {
        return new PaperTrackingScanRequest(
                playerId,
                unique,
                null,
                TrackingObservationUseCase.Presence.PRESENT,
                source);
    }

    static PaperTrackingScanRequest chunk(
            Chunk chunk,
            TrackingObservationUseCase.Presence presence,
            String source) {
        return new PaperTrackingScanRequest(
                null,
                false,
                ChunkReference.capture(chunk),
                presence,
                source);
    }

    /**
     * Only periodic reconciliation may be coalesced. Lifecycle-triggered scans
     * have distinct evidence semantics and always retain their own queue slot.
     */
    ChunkReference periodicChunkKey() {
        return "periodic-loaded-chunk".equals(source)
                && presence == TrackingObservationUseCase.Presence.PRESENT
                ? chunk : null;
    }

    void run(Plugin plugin, PaperPhysicalTrackingListener listener) {
        if (playerId != null) {
            listener.scanPlayer(playerId, unique, source);
            return;
        }
        World world = plugin.getServer().getWorld(chunk.worldId());
        if (world != null && world.isChunkLoaded(chunk.x(), chunk.z())) {
            listener.scanChunk(
                    world.getChunkAt(chunk.x(), chunk.z()),
                    presence,
                    source);
        }
    }

    record ChunkReference(UUID worldId, int x, int z) {
        private static ChunkReference capture(Chunk chunk) {
            return new ChunkReference(
                    chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        }
    }
}
