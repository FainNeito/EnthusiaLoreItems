package net.enthusia.loreitems.paper;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import net.enthusia.loreitems.application.CrownExchangeAdmission;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import org.bukkit.plugin.Plugin;
import java.util.UUID;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.domain.ExclusiveCustodyReservation;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Inventory step only. The caller must own the custody reservation and exclude competing writers. */
final class PaperCrownSlotExchange {
    enum Result { STAGED, HELD }
    private static final NamespacedKey ESCROW = new NamespacedKey("enthusialoreitems", "crown_exchange");
    private final PaperItemCodecThreadGuard guard;

    PaperCrownSlotExchange(PaperItemCodecThreadGuard guard) {
        this.guard = guard;
    }

    /** Collaborators for one staging attempt. */
    record Staging(CrownExchangeAdmission admission, PaperCrownPreparationFence fence, Plugin plugin, Executor primaryThread) {}

    /** The Crown to move: an existing storage slot, or a first issue minted from {@code template} (slot -1). */
    private record Source(int slot, ItemStack item, EncodedItemTemplate template, long revision) {
        boolean firstIssue() {
            return template != null;
        }
    }

    CompletionStage<Result> stage(Player player, ExclusiveCustodyReservation request, UUID definition, int sourceSlot,
            CrownExchangeAdmission admission, PaperCrownPreparationFence fence, Plugin plugin, Executor primaryThread) {
        guard.requirePrimaryThread();
        if (player == null || sourceSlot < 0 || sourceSlot >= player.getInventory().getStorageContents().length)
            return held();
        return new Attempt(player, request, definition, new Source(sourceSlot, player.getInventory().getItem(sourceSlot), null, 0),
                new Staging(admission, fence, plugin, primaryThread)).run();
    }

    CompletionStage<Result> stageFirst(Player player, ExclusiveCustodyReservation request, UUID definition, long revision,
            EncodedItemTemplate template, Staging staging) {
        guard.requirePrimaryThread();
        var identity = new net.enthusia.loreitems.application.LoreItemIdentity(
                new net.enthusia.loreitems.domain.LoreDefinitionId(definition),
                new net.enthusia.loreitems.domain.LoreInstanceId(request.instanceId()),
                new net.enthusia.loreitems.domain.TemplateRevision(revision));
        var helmet = new PaperItemTemplateCodec(guard).decode(template);
        var crown = new PaperItemIdentityCodec(guard).writeIdentity(helmet, identity);
        return new Attempt(player, request, definition, new Source(-1, crown, template, revision), staging).run();
    }

    private static CompletionStage<Result> held() {
        return CompletableFuture.completedFuture(Result.HELD);
    }

    /** One staging attempt; its fence token and exchange window live exactly as long as its outcome. */
    private final class Attempt {
        private final Player player;
        private final ExclusiveCustodyReservation request;
        private final UUID definition;
        private final Source source;
        private final Staging staging;
        private final PaperCrownInventoryProbe.Observation expectedLocation;
        private final PaperCrownHelmetEvidence capture = new PaperCrownHelmetEvidence(guard);
        private final CompletableFuture<Result> outcome = new CompletableFuture<>();

        Attempt(Player player, ExclusiveCustodyReservation request, UUID definition, Source source, Staging staging) {
            this.player = player;
            this.request = request;
            this.definition = definition;
            this.source = source;
            this.staging = staging;
            this.expectedLocation = source.firstIssue()
                    ? PaperCrownInventoryProbe.Observation.MISSING : PaperCrownInventoryProbe.Observation.ELSEWHERE;
        }

        CompletionStage<Result> run() {
            var before = capture.capture(player, request.holderId());
            if (before.isEmpty() || !admissibleSource()) return held();
            var expectedSource = source.item().clone();
            var token = staging.fence().begin(request.holderId());
            if (token == null) return held();
            var window = new PaperCrownExchangeWindow(staging.plugin(), player, () -> outcome.complete(Result.HELD));
            outcome.whenComplete((result, error) -> {
                staging.fence().end(request.holderId(), token);
                window.close();
            });
            var original = before.orElseThrow();
            try {
                window.open();
                var decision = decide(original);
                holdIfAbandoned(decision);
                decision.whenCompleteAsync((admitted, failure) ->
                        settle(failure == null && Boolean.TRUE.equals(admitted), token, expectedSource, original),
                        staging.primaryThread());
            } catch (RuntimeException unavailable) {
                outcome.complete(Result.HELD);
            }
            return outcome.minimalCompletionStage();
        }

        private boolean admissibleSource() {
            var item = source.item();
            if (!singleNetheriteHelmet(item) || player.getPersistentDataContainer().has(ESCROW)) return false;
            return tracksRequest(new PaperItemIdentityCodec(guard).readIdentity(item), request, definition)
                    && new PaperCrownInventoryProbe(guard).observe(player, request.holderId(), definition, request.instanceId())
                    == expectedLocation;
        }

        private CompletionStage<Boolean> decide(PaperCrownHelmetEvidence.Snapshot original) {
            return source.firstIssue()
                    ? staging.admission().reserveFirstIssue(request, definition, source.revision(), source.template(), original.bytes())
                    : staging.admission().admit(request, definition, original.empty(), original.bytes());
        }

        /**
         * The main-thread callback may be rejected during shutdown. A subsequently
         * committed reservation still needs review even after the window has closed.
         */
        private void holdIfAbandoned(CompletionStage<Boolean> decision) {
            decision.thenCombine(outcome, (accepted, result) -> Boolean.TRUE.equals(accepted) && result == Result.HELD)
                    .thenCompose(hold -> hold ? staging.admission().hold(request) : CompletableFuture.completedFuture(false))
                    .exceptionally(error -> {
                        staging.plugin().getLogger().log(java.util.logging.Level.WARNING,
                                "Could not confirm Crown custody for " + request.operationId(), error);
                        return false;
                    });
        }

        private void settle(boolean accepted, UUID token, ItemStack expectedSource, PaperCrownHelmetEvidence.Snapshot original) {
            try {
                if (accepted && stillCurrent(token, expectedSource) && helmetUnchanged(original)
                        && moveReserved(player, request, definition, source.slot(), expectedSource, expectedLocation) == Result.STAGED) {
                    outcome.complete(Result.STAGED);
                }
            } finally {
                if (!outcome.isDone()) outcome.complete(Result.HELD);
            }
        }

        private boolean stillCurrent(UUID token, ItemStack expectedSource) {
            return !outcome.isDone() && staging.fence().valid(request.holderId(), token)
                    && staging.plugin().isEnabled() && staging.plugin().getServer().getPlayer(request.holderId()) == player
                    && (source.firstIssue() || expectedSource.equals(player.getInventory().getItem(source.slot())));
        }

        private boolean helmetUnchanged(PaperCrownHelmetEvidence.Snapshot original) {
            var current = capture.capture(player, request.holderId());
            return current.isPresent() && Arrays.equals(current.orElseThrow().bytes(), original.bytes());
        }
    }

    static boolean singleNetheriteHelmet(ItemStack item) {
        return item != null && item.getType() == Material.NETHERITE_HELMET && item.getAmount() == 1;
    }

    static boolean tracksRequest(ItemIdentityReadResult identity, ExclusiveCustodyReservation request, UUID definition) {
        return identity instanceof ItemIdentityReadResult.Tracked tracked
                && tracked.identity().definitionId().value().equals(definition)
                && tracked.identity().instanceId().value().equals(request.instanceId());
    }

    /** Read the holder marker written in the same player save as the physical Crown. */
    Optional<ExclusiveCustodyReservation> markedCrown(Player player) {
        guard.requirePrimaryThread();
        var helmet = player.getInventory().getHelmet();
        if (helmet == null || helmet.getType() != Material.NETHERITE_HELMET || helmet.getAmount() != 1) return Optional.empty();
        try {
            var escrow = player.getPersistentDataContainer().get(ESCROW, PersistentDataType.TAG_CONTAINER);
            if (escrow == null || !(new PaperItemIdentityCodec(guard).readIdentity(helmet) instanceof ItemIdentityReadResult.Tracked tracked))
                return Optional.empty();
            var request = new ExclusiveCustodyReservation(UUID.fromString(escrow.get(key("operation"), PersistentDataType.STRING)),
                    tracked.identity().instanceId().value(), player.getUniqueId(), escrow.get(key("generation"), PersistentDataType.LONG),
                    ExclusiveCustodyReservation.State.RESERVED);
            return matches(escrow, request) && new PaperCrownInventoryProbe(guard).observe(player, player.getUniqueId(),
                    tracked.identity().definitionId().value(), request.instanceId()) == PaperCrownInventoryProbe.Observation.HEAD_ONLY
                    ? Optional.of(request) : Optional.empty();
        } catch (NullPointerException | IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }
    record LoadedExchange(ExclusiveCustodyReservation reservation, UUID definition, byte[] original) {}

    Optional<LoadedExchange> readLoadedExchange(Player player) {
        guard.requirePrimaryThread();
        try {
            var escrow = player.getPersistentDataContainer().get(ESCROW, PersistentDataType.TAG_CONTAINER);
            var helmet = player.getInventory().getHelmet();
            if (escrow == null || helmet == null || helmet.getType() != Material.NETHERITE_HELMET || helmet.getAmount() != 1
                    || !(new PaperItemIdentityCodec(guard).readIdentity(helmet) instanceof ItemIdentityReadResult.Tracked tracked))
                return Optional.empty();
            var request = new ExclusiveCustodyReservation(
                    UUID.fromString(escrow.get(key("operation"), PersistentDataType.STRING)),
                    tracked.identity().instanceId().value(), player.getUniqueId(),
                    escrow.get(key("generation"), PersistentDataType.LONG), ExclusiveCustodyReservation.State.RESERVED);
            UUID definition = tracked.identity().definitionId().value();
            return observeEscrow(player, request, definition).map(bytes -> new LoadedExchange(request, definition, bytes));
        } catch (IllegalArgumentException | NullPointerException malformed) {
            return Optional.empty();
        }
    }

    /** Observe a reloaded player before recovery. Does not prove a save or authorize consumption. */
    Optional<byte[]> observeEscrow(Player player, ExclusiveCustodyReservation request, UUID definition) {
        guard.requirePrimaryThread();
        if (request.state() != ExclusiveCustodyReservation.State.RESERVED
                || new PaperCrownInventoryProbe(guard).observe(player, request.holderId(), definition,
                request.instanceId()) != PaperCrownInventoryProbe.Observation.HEAD_ONLY) return Optional.empty();
        try {
            var escrow = player.getPersistentDataContainer().get(ESCROW, PersistentDataType.TAG_CONTAINER);
            if (!matches(escrow, request) || escrow.has(key("consumed"))) return Optional.empty();
            var original = escrow.get(key("original"), PersistentDataType.BYTE_ARRAY);
            return original == null || original.length > 1024 * 1024 ? Optional.empty() : Optional.of(original.clone());
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }
    static boolean matches(org.bukkit.persistence.PersistentDataContainer escrow, ExclusiveCustodyReservation request) {
        return escrow != null && request.operationId().toString().equals(escrow.get(key("operation"), PersistentDataType.STRING))
                && request.instanceId().toString().equals(escrow.get(key("instance"), PersistentDataType.STRING))
                && request.holderId().toString().equals(escrow.get(key("holder"), PersistentDataType.STRING))
                && Long.valueOf(request.generation()).equals(escrow.get(key("generation"), PersistentDataType.LONG));
    }

    /** Caller supplies the durable equipped receipt for this request while holding exclusive player access. */
    boolean finishCommitted(Player player, ExclusiveCustodyReservation request, UUID definition, String committedDigest) {
        guard.requirePrimaryThread();
        if (committedDigest == null || !committedDigest.matches("[0-9a-f]{64}")
                || new PaperCrownInventoryProbe(guard).observe(player, request.holderId(), definition, request.instanceId())
                != PaperCrownInventoryProbe.Observation.HEAD_ONLY) return false;
        try {
            var data = player.getPersistentDataContainer();
            var escrow = data.get(ESCROW, PersistentDataType.TAG_CONTAINER);
            if (!matches(escrow, request)) return false;
            if (escrow.has(key("consumed"))) {
                return !escrow.has(key("original")) && committedDigest.equals(escrow.get(key("consumed"), PersistentDataType.STRING));
            }
            var original = observeEscrow(player, request, definition);
            if (original.isEmpty()) return false;
            var hash = java.security.MessageDigest.getInstance("SHA-256").digest(original.orElseThrow());
            if (!committedDigest.equals(java.util.HexFormat.of().formatHex(hash))) return false;
            escrow.remove(key("original"));
            escrow.set(key("consumed"), PersistentDataType.STRING, committedDigest);
            data.set(ESCROW, PersistentDataType.TAG_CONTAINER, escrow);
            player.saveData();
            return true;
        } catch (java.security.NoSuchAlgorithmException | RuntimeException uncertain) {
            return false;
        }
    }
    private Result moveReserved(Player player, ExclusiveCustodyReservation request, UUID definition, int sourceSlot, ItemStack crown, PaperCrownInventoryProbe.Observation expectedLocation) {
        guard.requirePrimaryThread();
        if (!reservedForOnlineHolder(player, request)) return Result.HELD;
        try {
            var inventory = player.getInventory();

            var data = player.getPersistentDataContainer();
            if (data.has(ESCROW)) return Result.HELD;

            if (!singleNetheriteHelmet(crown)
                    || !tracksRequest(new PaperItemIdentityCodec(guard).readIdentity(crown), request, definition)) return Result.HELD;
            var probe = new PaperCrownInventoryProbe(guard);
            if (probe.observe(player, request.holderId(), definition, request.instanceId())
                    != expectedLocation) return Result.HELD;
            var original = new PaperCrownHelmetEvidence(guard).capture(player, request.holderId());
            if (original.isEmpty()) return Result.HELD;
            var escrow = escrowFor(data, request, original.orElseThrow().bytes());
            // The escrow and both inventory slots belong to the same player's save.
            // An existing escrow always blocks replay, including after a partial mutation.
            var moving = crown.clone();
            data.set(ESCROW, PersistentDataType.TAG_CONTAINER, escrow);
            if (sourceSlot >= 0) inventory.setItem(sourceSlot, null);
            inventory.setHelmet(moving);
            return probe.observe(player, request.holderId(), definition, request.instanceId())
                    == PaperCrownInventoryProbe.Observation.HEAD_ONLY ? Result.STAGED : Result.HELD;
        } catch (RuntimeException uncertain) {
            return Result.HELD;
        }
    }

    private static boolean reservedForOnlineHolder(Player player, ExclusiveCustodyReservation request) {
        return player != null && player.isOnline() && player.getUniqueId().equals(request.holderId())
                && request.state() == ExclusiveCustodyReservation.State.RESERVED;
    }

    private static org.bukkit.persistence.PersistentDataContainer escrowFor(org.bukkit.persistence.PersistentDataContainer data,
            ExclusiveCustodyReservation request, byte[] original) {
        var escrow = data.getAdapterContext().newPersistentDataContainer();
        escrow.set(key("operation"), PersistentDataType.STRING, request.operationId().toString());
        escrow.set(key("instance"), PersistentDataType.STRING, request.instanceId().toString());
        escrow.set(key("holder"), PersistentDataType.STRING, request.holderId().toString());
        escrow.set(key("generation"), PersistentDataType.LONG, request.generation());
        escrow.set(key("original"), PersistentDataType.BYTE_ARRAY, original);
        return escrow;
    }

    private static NamespacedKey key(String field) {
        return new NamespacedKey("enthusialoreitems", field);
    }
}
