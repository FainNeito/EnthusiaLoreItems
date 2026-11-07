package net.enthusia.loreitems.paper;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import net.enthusia.loreitems.application.CrownExchangeAdmission;
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

    CompletionStage<Result> stage(Player player, ExclusiveCustodyReservation request, UUID definition, int sourceSlot,
            CrownExchangeAdmission admission, PaperCrownPreparationFence fence, Plugin plugin, Executor primaryThread) {
        guard.requirePrimaryThread();
        if (player == null || sourceSlot < 0 || sourceSlot >= player.getInventory().getStorageContents().length)
            return CompletableFuture.completedFuture(Result.HELD);
        return stage(player, request, definition, sourceSlot, player.getInventory().getItem(sourceSlot),
                null, 0, admission, fence, plugin, primaryThread);
    }

    CompletionStage<Result> stageFirst(Player player, ExclusiveCustodyReservation request, UUID definition, long revision,
            net.enthusia.loreitems.application.EncodedItemTemplate template, CrownExchangeAdmission admission,
            PaperCrownPreparationFence fence, Plugin plugin, Executor primaryThread) {
        guard.requirePrimaryThread();
        var identity = new net.enthusia.loreitems.application.LoreItemIdentity(
                new net.enthusia.loreitems.domain.LoreDefinitionId(definition),
                new net.enthusia.loreitems.domain.LoreInstanceId(request.instanceId()),
                new net.enthusia.loreitems.domain.TemplateRevision(revision));
        var helmet = new PaperItemTemplateCodec(guard).decode(template);
        var crown = new PaperItemIdentityCodec(guard).writeIdentity(helmet, identity);
        return stage(player, request, definition, -1, crown, template, revision, admission, fence, plugin, primaryThread);
    }

    private CompletionStage<Result> stage(Player player, ExclusiveCustodyReservation request, UUID definition,
            int sourceSlot, ItemStack source, net.enthusia.loreitems.application.EncodedItemTemplate template, long revision,
            CrownExchangeAdmission admission, PaperCrownPreparationFence fence, Plugin plugin, Executor primaryThread) {
        var expectedLocation = template == null ? PaperCrownInventoryProbe.Observation.ELSEWHERE : PaperCrownInventoryProbe.Observation.MISSING;
        var capture = new PaperCrownHelmetEvidence(guard);
        var before = capture.capture(player, request.holderId());
        if (before.isEmpty()) {
            return CompletableFuture.completedFuture(Result.HELD);
        }

        if (source == null || source.getType() != Material.NETHERITE_HELMET || source.getAmount() != 1
                || player.getPersistentDataContainer().has(ESCROW)) return CompletableFuture.completedFuture(Result.HELD);
        var identity = new PaperItemIdentityCodec(guard).readIdentity(source);
        if (!(identity instanceof ItemIdentityReadResult.Tracked tracked)
                || !tracked.identity().definitionId().value().equals(definition)
                || !tracked.identity().instanceId().value().equals(request.instanceId())
                || new PaperCrownInventoryProbe(guard).observe(player, request.holderId(), definition, request.instanceId())
                != expectedLocation) return CompletableFuture.completedFuture(Result.HELD);
        var expectedSource = source.clone();
        var token = fence.begin(request.holderId());
        if (token == null) return CompletableFuture.completedFuture(Result.HELD);
        var outcome = new CompletableFuture<Result>();
        var window = new PaperCrownExchangeWindow(plugin, player, () -> outcome.complete(Result.HELD));
        outcome.whenComplete((result, error) -> {
            fence.end(request.holderId(), token);
            window.close();
        });
        var original = before.orElseThrow();
        try {
            window.open();
            var decision = template == null ? admission.admit(request, definition, original.empty(), original.bytes())
                    : admission.reserveFirstIssue(request, definition, revision, template, original.bytes());
            // The main-thread callback may be rejected during shutdown. A subsequently
            // committed reservation still needs review even after the window has closed.
            decision.thenCombine(outcome, (accepted, result) -> Boolean.TRUE.equals(accepted) && result == Result.HELD)
                    .thenCompose(hold -> hold ? admission.hold(request) : CompletableFuture.completedFuture(false))
                    .exceptionally(error -> {
                        plugin.getLogger().log(java.util.logging.Level.WARNING, "Could not confirm Crown custody for " + request.operationId(), error);
                        return false;
                    });
            decision.whenCompleteAsync((admitted, failure) -> {
                boolean accepted = Boolean.TRUE.equals(admitted);
                try {
                    if (failure == null && accepted && !outcome.isDone() && fence.valid(request.holderId(), token)
                            && plugin.isEnabled() && plugin.getServer().getPlayer(request.holderId()) == player
                            && (template != null || expectedSource.equals(player.getInventory().getItem(sourceSlot)))) {
                        var current = capture.capture(player, request.holderId());
                        if (current.isPresent() && Arrays.equals(current.orElseThrow().bytes(), original.bytes())
                                && moveReserved(player, request, definition, sourceSlot, expectedSource, expectedLocation) == Result.STAGED) {
                            outcome.complete(Result.STAGED);
                            return;
                        }
                    }
                } finally {
                    if (!outcome.isDone()) outcome.complete(Result.HELD);
                }
            }, primaryThread);
        } catch (RuntimeException unavailable) {
            outcome.complete(Result.HELD);
        }
        return outcome.minimalCompletionStage();
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
        if (player == null || !player.isOnline() || !player.getUniqueId().equals(request.holderId())
                || request.state() != ExclusiveCustodyReservation.State.RESERVED) return Result.HELD;
        try {
            var inventory = player.getInventory();

            var data = player.getPersistentDataContainer();
            if (data.has(ESCROW)) return Result.HELD;

            var codec = new PaperItemIdentityCodec(guard);
            if (crown == null || crown.getType() != Material.NETHERITE_HELMET || crown.getAmount() != 1
                    || !(codec.readIdentity(crown) instanceof ItemIdentityReadResult.Tracked tracked)
                    || !tracked.identity().instanceId().value().equals(request.instanceId())
                    || !tracked.identity().definitionId().value().equals(definition)) return Result.HELD;
            var probe = new PaperCrownInventoryProbe(guard);
            if (probe.observe(player, request.holderId(), definition, request.instanceId())
                    != expectedLocation) return Result.HELD;
            var original = new PaperCrownHelmetEvidence(guard).capture(player, request.holderId());
            if (original.isEmpty()) return Result.HELD;
            var escrow = data.getAdapterContext().newPersistentDataContainer();
            escrow.set(key("operation"), PersistentDataType.STRING, request.operationId().toString());
            escrow.set(key("instance"), PersistentDataType.STRING, request.instanceId().toString());
            escrow.set(key("holder"), PersistentDataType.STRING, request.holderId().toString());
            escrow.set(key("generation"), PersistentDataType.LONG, request.generation());
            escrow.set(key("original"), PersistentDataType.BYTE_ARRAY, original.orElseThrow().bytes());
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

    private static NamespacedKey key(String field) {
        return new NamespacedKey("enthusialoreitems", field);
    }
}
