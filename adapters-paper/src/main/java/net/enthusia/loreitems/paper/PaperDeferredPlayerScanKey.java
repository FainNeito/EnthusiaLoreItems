package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.UUID;

/** Main-thread-only key for coalescing one-tick scans without merging event sources. */
record PaperDeferredPlayerScanKey(UUID playerId, String source) {
    PaperDeferredPlayerScanKey {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(source, "source");
    }
}
