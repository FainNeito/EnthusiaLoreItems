package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.enthusia.loreitems.application.CrownHelmetEvidenceStore;
import net.enthusia.loreitems.domain.ExclusiveCustodyReservation;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class PaperCrownHelmetPreparationTest {
    @BeforeEach void start() { MockBukkit.mock(); }
    @AfterEach void stop() { MockBukkit.unmock(); }

    @Test void changedHelmetDuringDatabaseWriteIsHeldWithoutMutation() {
        var player = MockBukkit.getMock().addPlayer();
        player.getInventory().setHelmet(ItemStack.of(Material.NETHERITE_HELMET));
        var request = request(player.getUniqueId());
        var pending = new CompletableFuture<CrownHelmetEvidenceStore.Result>();
        var holds = new AtomicInteger();
        CrownHelmetEvidenceStore store = new CrownHelmetEvidenceStore() {
            @Override public java.util.concurrent.CompletionStage<Result> record(ExclusiveCustodyReservation operation, boolean empty, byte[] evidence) { return pending; }
            @Override public java.util.concurrent.CompletionStage<Boolean> hold(ExclusiveCustodyReservation operation) {
                holds.incrementAndGet(); return CompletableFuture.completedFuture(true);
            }
        };
        var scheduled = new ArrayList<Runnable>();
        var coordinator = new PaperCrownHelmetPreparation(new PaperItemCodecThreadGuard(() -> true), new PaperCrownPreparationFence(new PaperItemCodecThreadGuard(() -> true)), store,
                scheduled::add, id -> player);
        var outcome = coordinator.prepare(player, request).toCompletableFuture();
        player.getInventory().setHelmet(ItemStack.of(Material.DIAMOND_HELMET));
        pending.complete(CrownHelmetEvidenceStore.Result.RECORDED);
        assertFalse(outcome.isDone());
        scheduled.forEach(Runnable::run);
        assertEquals(PaperCrownHelmetPreparation.Result.HELD, outcome.join());
        assertEquals(1, holds.get());
        assertEquals(Material.DIAMOND_HELMET, player.getInventory().getHelmet().getType());
    }

    @Test void emptyHeadIsRecordedAndOnlyBecomesEvidenceForReview() {
        var player = MockBukkit.getMock().addPlayer(); var request = request(player.getUniqueId());
        CrownHelmetEvidenceStore store = (operation, empty, evidence) -> {
            assertTrue(empty); assertEquals(0, evidence.length);
            return CompletableFuture.completedFuture(CrownHelmetEvidenceStore.Result.RECORDED);
        };
        var scheduled = new ArrayList<Runnable>();
        var coordinator = new PaperCrownHelmetPreparation(new PaperItemCodecThreadGuard(() -> true), new PaperCrownPreparationFence(new PaperItemCodecThreadGuard(() -> true)), store,
                scheduled::add, id -> player);
        var outcome = coordinator.prepare(player, request).toCompletableFuture();
        scheduled.forEach(Runnable::run);
        assertEquals(PaperCrownHelmetPreparation.Result.EVIDENCE_RECORDED_FOR_REVIEW, outcome.join());
        assertNull(player.getInventory().getHelmet());
    }

    @Test void failedWriteAndUnavailablePlayerNeverProduceAReadyResult() {
        var player = MockBukkit.getMock().addPlayer(); var calls = new AtomicInteger();
        CrownHelmetEvidenceStore store = (operation, empty, evidence) -> {
            calls.incrementAndGet(); return CompletableFuture.failedFuture(new IllegalStateException("offline"));
        };
        var scheduled = new ArrayList<Runnable>();
        var coordinator = new PaperCrownHelmetPreparation(new PaperItemCodecThreadGuard(() -> true), new PaperCrownPreparationFence(new PaperItemCodecThreadGuard(() -> true)), store,
                scheduled::add, id -> player);
        var failed = coordinator.prepare(player, request(player.getUniqueId())).toCompletableFuture();
        scheduled.forEach(Runnable::run);
        assertEquals(PaperCrownHelmetPreparation.Result.HELD, failed.join());
        assertEquals(PaperCrownHelmetPreparation.Result.HELD,
                coordinator.prepare(player, request(UUID.randomUUID())).toCompletableFuture().join());
        assertEquals(1, calls.get());
    }

    @Test void playerUnavailableAfterCommitKeepsPreparationOnHold() {
        var player = MockBukkit.getMock().addPlayer(); var request = request(player.getUniqueId());
        CrownHelmetEvidenceStore store = (operation, empty, evidence) -> CompletableFuture.completedFuture(CrownHelmetEvidenceStore.Result.RECORDED);
        var scheduled = new ArrayList<Runnable>();
        var coordinator = new PaperCrownHelmetPreparation(new PaperItemCodecThreadGuard(() -> true), new PaperCrownPreparationFence(new PaperItemCodecThreadGuard(() -> true)), store,
                scheduled::add, id -> null);
        var outcome = coordinator.prepare(player, request).toCompletableFuture();
        scheduled.forEach(Runnable::run);
        assertEquals(PaperCrownHelmetPreparation.Result.HELD, outcome.join());
    }

    @Test void invalidatedCallbackCannotProceedEvenWhenHelmetLooksIdenticalAgain() {
        var player = MockBukkit.getMock().addPlayer();
        player.getInventory().setHelmet(ItemStack.of(Material.NETHERITE_HELMET));
        var request = request(player.getUniqueId());
        var guard = new PaperItemCodecThreadGuard(() -> true); var fence = new PaperCrownPreparationFence(guard);
        var pending = new CompletableFuture<CrownHelmetEvidenceStore.Result>();
        CrownHelmetEvidenceStore store = (operation, empty, evidence) -> pending;
        var scheduled = new ArrayList<Runnable>();
        var coordinator = new PaperCrownHelmetPreparation(guard, fence, store, scheduled::add, id -> player);
        var outcome = coordinator.prepare(player, request).toCompletableFuture();
        var previous = player.getInventory().getHelmet();
        player.getInventory().setHelmet(null); fence.invalidate(player.getUniqueId());
        player.getInventory().setHelmet(previous);
        pending.complete(CrownHelmetEvidenceStore.Result.RECORDED);
        scheduled.forEach(Runnable::run);
        assertEquals(PaperCrownHelmetPreparation.Result.HELD, outcome.join());
        assertEquals(previous, player.getInventory().getHelmet());
    }

    @Test void staleFenceCannotReleaseANewerPreparation() {
        var fence = new PaperCrownPreparationFence(new PaperItemCodecThreadGuard(() -> true)); var player = UUID.randomUUID();
        var first = fence.begin(player); assertNotNull(first); assertNull(fence.begin(player));
        fence.invalidate(player); assertNull(fence.begin(player));
        fence.end(player, first); var second = fence.begin(player); assertNotNull(second);
        fence.end(player, first);
        assertTrue(fence.valid(player, second));
        fence.end(player, second); assertFalse(fence.valid(player, second));
    }

    @Test void shutdownInvalidationHoldsLateCommittedEvidence() {
        var player = MockBukkit.getMock().addPlayer();
        var request = request(player.getUniqueId());
        var guard = new PaperItemCodecThreadGuard(() -> true);
        var fence = new PaperCrownPreparationFence(guard);
        var pending = new CompletableFuture<CrownHelmetEvidenceStore.Result>();
        var holds = new AtomicInteger();
        CrownHelmetEvidenceStore store = new CrownHelmetEvidenceStore() {
            @Override public java.util.concurrent.CompletionStage<Result> record(ExclusiveCustodyReservation operation, boolean empty, byte[] evidence) {
                return pending;
            }
            @Override public java.util.concurrent.CompletionStage<Boolean> hold(ExclusiveCustodyReservation operation) {
                holds.incrementAndGet(); return CompletableFuture.completedFuture(true);
            }
        };
        var scheduled = new ArrayList<Runnable>();
        var coordinator = new PaperCrownHelmetPreparation(guard, fence, store, scheduled::add, id -> player);
        var outcome = coordinator.prepare(player, request).toCompletableFuture();
        fence.invalidateAll();
        pending.complete(CrownHelmetEvidenceStore.Result.RECORDED);
        scheduled.forEach(Runnable::run);
        assertEquals(PaperCrownHelmetPreparation.Result.HELD, outcome.join());
        assertEquals(1, holds.get());
        assertNull(fence.begin(player.getUniqueId()));
    }

    @Test void overlappingRetryInvalidatesTheOriginalEvenIfItsWriteLaterSucceeds() {
        var player = MockBukkit.getMock().addPlayer();
        var request = request(player.getUniqueId());
        var guard = new PaperItemCodecThreadGuard(() -> true);
        var fence = new PaperCrownPreparationFence(guard);
        var pendingWrite = new CompletableFuture<CrownHelmetEvidenceStore.Result>();
        var holdCalls = new AtomicInteger();
        CrownHelmetEvidenceStore store = new CrownHelmetEvidenceStore() {
            @Override public java.util.concurrent.CompletionStage<Result> record(ExclusiveCustodyReservation operation, boolean empty, byte[] evidence) {
                return pendingWrite;
            }
            @Override public java.util.concurrent.CompletionStage<Boolean> hold(ExclusiveCustodyReservation operation) {
                holdCalls.incrementAndGet();
                return CompletableFuture.completedFuture(true);
            }
        };
        var scheduled = new ArrayList<Runnable>();
        var coordinator = new PaperCrownHelmetPreparation(guard, fence, store, scheduled::add, id -> player);
        var original = coordinator.prepare(player, request).toCompletableFuture();
        assertEquals(PaperCrownHelmetPreparation.Result.HELD, coordinator.prepare(player, request).toCompletableFuture().join());
        pendingWrite.complete(CrownHelmetEvidenceStore.Result.RECORDED);
        scheduled.forEach(Runnable::run);
        assertEquals(PaperCrownHelmetPreparation.Result.HELD, original.join());
        assertEquals(2, holdCalls.get());
        assertNull(fence.begin(player.getUniqueId()));
    }

    @Test void failedPreparationKeepsFenceClosedWhileHoldIsPendingOrFails() {
        var player = MockBukkit.getMock().addPlayer();
        var request = request(player.getUniqueId());
        var guard = new PaperItemCodecThreadGuard(() -> true);
        var fence = new PaperCrownPreparationFence(guard);
        var pendingHold = new CompletableFuture<Boolean>();
        CrownHelmetEvidenceStore store = new CrownHelmetEvidenceStore() {
            @Override public java.util.concurrent.CompletionStage<Result> record(ExclusiveCustodyReservation operation, boolean empty, byte[] evidence) {
                return CompletableFuture.failedFuture(new IllegalStateException("write uncertain"));
            }
            @Override public java.util.concurrent.CompletionStage<Boolean> hold(ExclusiveCustodyReservation operation) {
                return pendingHold;
            }
        };
        var scheduled = new ArrayList<Runnable>();
        var coordinator = new PaperCrownHelmetPreparation(guard, fence, store, scheduled::add, id -> player);
        var outcome = coordinator.prepare(player, request).toCompletableFuture();
        scheduled.forEach(Runnable::run);
        assertFalse(outcome.isDone());
        assertNull(fence.begin(player.getUniqueId()));
        pendingHold.completeExceptionally(new IllegalStateException("hold uncertain"));
        assertEquals(PaperCrownHelmetPreparation.Result.HELD, outcome.join());
        assertNull(fence.begin(player.getUniqueId()));
    }

    @Test void inventoryInteractionInvalidatesTheActiveCallbackFence() {
        var player = MockBukkit.getMock().addPlayer();
        var fence = new PaperCrownPreparationFence(new PaperItemCodecThreadGuard(() -> true));
        var token = fence.begin(player.getUniqueId()); assertNotNull(token);
        new PaperCrownPreparationListener(fence).onSwap(new PlayerSwapHandItemsEvent(player, null, null));
        assertFalse(fence.valid(player.getUniqueId(), token));
    }

    @SuppressWarnings("removal")
    @Test void itemDamageInvalidatesAnActivePreparation() {
        var player = MockBukkit.getMock().addPlayer();
        var fence = new PaperCrownPreparationFence(new PaperItemCodecThreadGuard(() -> true));
        var token = fence.begin(player.getUniqueId()); assertNotNull(token);
        new PaperCrownPreparationListener(fence).onItemDamage(
                new PlayerItemDamageEvent(player, ItemStack.of(Material.NETHERITE_HELMET), 1));
        assertFalse(fence.valid(player.getUniqueId(), token));
    }

    private static ExclusiveCustodyReservation request(UUID holder) {
        return new ExclusiveCustodyReservation(UUID.randomUUID(), UUID.randomUUID(), holder, 1,
                ExclusiveCustodyReservation.State.RESERVED);
    }
}
