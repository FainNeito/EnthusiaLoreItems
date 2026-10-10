package net.enthusia.loreitems.paper;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Bounded main-thread discovery of tracked items in one inventory tree. */
class PaperTemplateUpdateScanner {
    private static final int MAX_ITEMS_PER_PASS = 256;
    private static final int MAX_NESTING_DEPTH = 8;
    private static final int MAX_CONTINUATION_PASSES = 64;
    private static final int MAX_PENDING_REFERENCES = 8_192;

    private final PaperItemIdentityCodec identityCodec = new PaperItemIdentityCodec();
    private final Map<PaperInventoryReference, ScanCursor> cursors = new ConcurrentHashMap<>();

    ScanResult scan(
            Plugin plugin,
            Inventory inventory,
            Consumer<Candidate> consumer) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(inventory, "inventory");
        Objects.requireNonNull(consumer, "consumer");
        PaperInventoryReference inventoryReference = PaperInventoryReference.capture(inventory)
                .orElse(null);
        if (inventoryReference == null) {
            return ScanResult.complete(0);
        }

        ScanCursor cursor = cursors.computeIfAbsent(
                inventoryReference,
                ignored -> createCursor(inventoryReference, inventory));
        boolean overflowed = processPass(inventory, cursor);
        cursor.incrementContinuationPasses();
        if (overflowed
                || (!cursor.pendingNodes().isEmpty()
                        && cursor.continuationPasses() >= MAX_CONTINUATION_PASSES)) {
            cursors.remove(inventoryReference);
            return ScanResult.abandonedScan();
        }
        if (!cursor.pendingNodes().isEmpty()) {
            return ScanResult.continuation();
        }

        cursors.remove(inventoryReference);
        return ScanResult.complete(cursor.emitObserved(consumer));
    }

    void reset(PaperInventoryReference reference) {
        cursors.remove(Objects.requireNonNull(reference, "reference"));
    }

    void clear() {
        cursors.clear();
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private ScanCursor createCursor(
            PaperInventoryReference inventoryReference,
            Inventory inventory) {
        Objects.requireNonNull(inventoryReference, "inventoryReference");
        Objects.requireNonNull(inventory, "inventory");
        ScanCursor cursor = new ScanCursor();
        ItemStack[] contents = Objects.requireNonNull(
                inventory.getContents(), "inventory contents");
        for (int slot = 0; slot < contents.length; slot++) {
            ItemStack item = contents[slot];
            if (item != null && !item.getType().isAir()) {
                cursor.pendingNodes().add(new ScanNode(
                        PaperTemplateUpdateItemReference.root(inventoryReference, slot),
                        0));
            }
        }
        return cursor;
    }

    private boolean processPass(Inventory inventory, ScanCursor cursor) {
        PaperTemplateUpdateDiscoveryPass discovery = new PaperTemplateUpdateDiscoveryPass(inventory);
        int processed = 0;
        while (processed < MAX_ITEMS_PER_PASS && !cursor.pendingNodes().isEmpty()) {
            ScanNode node = cursor.pendingNodes().remove();
            ItemStack item = discovery.read(node.reference());
            if (item != null && !item.getType().isAir()) {
                cursor.observe(readCandidate(item, node.reference()));
                if (node.depth() < MAX_NESTING_DEPTH
                        && enqueueChildren(cursor.pendingNodes(), node, discovery)) {
                    return true;
                }
            }
            processed++;
        }
        return false;
    }

    private Candidate readCandidate(
            ItemStack item,
            PaperTemplateUpdateItemReference reference) {
        ItemIdentityReadResult identity = identityCodec.readIdentity(item);
        if (identity instanceof ItemIdentityReadResult.Tracked tracked) {
            return new Candidate(tracked.identity(), reference);
        }
        return null;
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private static boolean enqueueChildren(
            Queue<ScanNode> pendingNodes,
            ScanNode parent,
            PaperTemplateUpdateDiscoveryPass discovery) {
        PaperTemplateUpdateDiscoveryPass.NestedContents contents =
                discovery.children(parent.reference());
        for (int index = 0; index < contents.items().size(); index++) {
            ItemStack nested = contents.items().get(index);
            if (nested != null && !nested.getType().isAir()
                    && !enqueue(pendingNodes, new ScanNode(
                            parent.reference().nested(new PaperTemplateUpdateItemReference.NestedStep(
                                    contents.kind(), index)), parent.depth() + 1))) {
                return true;
            }
        }
        return false;
    }

    private static boolean enqueue(Queue<ScanNode> pendingNodes, ScanNode node) {
        if (pendingNodes.size() >= MAX_PENDING_REFERENCES) {
            return false;
        }
        pendingNodes.add(node);
        return true;
    }

    private record ScanNode(
            PaperTemplateUpdateItemReference reference,
            int depth) {
        private ScanNode {
            Objects.requireNonNull(reference, "reference");
            if (depth < 0 || depth > MAX_NESTING_DEPTH) {
                throw new IllegalArgumentException("Invalid scan depth");
            }
        }
    }

    private static final class ScanCursor {
        private final Queue<ScanNode> nodesPending = new ArrayDeque<>();
        private final Map<UUID, CandidateAccumulator> candidates = new ConcurrentHashMap<>();
        private int passesCompleted;

        private Queue<ScanNode> pendingNodes() {
            return nodesPending;
        }

        private int continuationPasses() {
            return passesCompleted;
        }

        private void incrementContinuationPasses() {
            passesCompleted++;
        }

        private void observe(Candidate candidate) {
            if (candidate == null) {
                return;
            }
            candidates.compute(
                    candidate.identity().instanceId().value(),
                    (ignored, accumulator) -> accumulator == null
                            ? new CandidateAccumulator(candidate)
                            : accumulator.increment());
        }

        private int emitObserved(Consumer<Candidate> consumer) {
            int observed = 0;
            for (CandidateAccumulator accumulator : candidates.values()) {
                for (int copy = 0; copy < accumulator.observationCount(); copy++) {
                    consumer.accept(accumulator.firstCandidate());
                    observed++;
                }
            }
            return observed;
        }
    }

    private static final class CandidateAccumulator {
        private final Candidate representativeCandidate;
        private int observations = 1;

        private CandidateAccumulator(Candidate firstCandidate) {
            this.representativeCandidate =
                    Objects.requireNonNull(firstCandidate, "firstCandidate");
        }

        private CandidateAccumulator increment() {
            observations++;
            return this;
        }

        private Candidate firstCandidate() {
            return representativeCandidate;
        }

        private int observationCount() {
            return observations;
        }
    }

    record Candidate(
            LoreItemIdentity identity,
            PaperTemplateUpdateReference reference) {
        Candidate {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(reference, "reference");
        }
    }

    record ScanResult(
            int submitted,
            boolean continuationRequired,
            boolean abandoned) {
        ScanResult {
            if (submitted < 0) {
                throw new IllegalArgumentException("submitted must not be negative");
            }
            if (abandoned && (submitted != 0 || continuationRequired)) {
                throw new IllegalArgumentException(
                        "Abandoned scans cannot submit or continue");
            }
        }

        static ScanResult complete(int submitted) {
            return new ScanResult(submitted, false, false);
        }

        static ScanResult continuation() {
            return new ScanResult(0, true, false);
        }

        static ScanResult abandonedScan() {
            return new ScanResult(0, false, true);
        }
    }
}
