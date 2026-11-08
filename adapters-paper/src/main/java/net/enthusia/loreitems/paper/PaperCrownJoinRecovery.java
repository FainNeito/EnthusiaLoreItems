package net.enthusia.loreitems.paper;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.enthusia.loreitems.application.CrownEquipmentRecovery;
import net.enthusia.loreitems.domain.ExclusiveCustodyReservation;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

public final class PaperCrownJoinRecovery implements Listener {
    private static final NamespacedKey ESCROW = new NamespacedKey("enthusialoreitems", "crown_exchange");
    private static final NamespacedKey RECLAIMED = new NamespacedKey("enthusialoreitems", "crown_reclaimed");
    private final Plugin plugin;
    private final CrownEquipmentRecovery recovery;
    private final BooleanSupplier active;
    private final PaperCrownSlotExchange exchange = new PaperCrownSlotExchange(PaperItemCodecThreadGuard.system());

    public PaperCrownJoinRecovery(Plugin plugin, CrownEquipmentRecovery recovery, BooleanSupplier active) {
        this.plugin = plugin;
        this.recovery = recovery;
        this.active = active;
    }

    public void register() {
        if (!active.getAsBoolean()) return;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getOnlinePlayers().forEach(player -> recover(player, false));
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(org.bukkit.event.entity.PlayerDeathEvent event) {
        Player holder = event.getEntity();
        if (!active.getAsBoolean() || event.getKeepInventory() || !holder.getPersistentDataContainer().has(ESCROW)) return;
        if (exchange.markedCrown(holder).isEmpty()) return;
        var crown = holder.getInventory().getHelmet();
        if (java.util.Collections.frequency(event.getDrops(), crown) != 1) return;
        // Paper keeps the matching equipped item in its original slot. Remove its world drop once.
        event.getDrops().remove(crown);
        if (!event.getItemsToKeep().contains(crown)) event.getItemsToKeep().add(crown);
    }
    @EventHandler(priority = org.bukkit.event.EventPriority.MONITOR, ignoreCancelled = true)
    public void recordKiller(org.bukkit.event.entity.PlayerDeathEvent event) {
        Player holder = event.getEntity();
        if (!active.getAsBoolean() || !holder.getPersistentDataContainer().has(ESCROW)) return;
        var cause = new PaperCrownDeathObservation().observe(holder.getUniqueId(), event.getDamageSource());
        if (cause.kind() != PaperCrownDeathObservation.Kind.PLAYER_KILL_CANDIDATE) return;
        var request = exchange.markedCrown(holder);
        if (request.isEmpty()) return;
        var helmet = holder.getInventory().getHelmet();
        if (!event.getKeepInventory() && (!event.getItemsToKeep().contains(helmet) || event.getDrops().contains(helmet))) return;
        recovery.recordPlayerKill(request.orElseThrow(), cause.attacker()).whenComplete((accepted, failure) -> {
            if (!Boolean.TRUE.equals(accepted) || failure != null)
                plugin.getLogger().warning("Crown succession requires review for " + request.orElseThrow().operationId());
        });
    }

    @EventHandler
    public void onRespawn(org.bukkit.event.player.PlayerRespawnEvent event) {
        var session = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> recover(session, false));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        recover(event.getPlayer(), true);
    }

    public void reclaim(UUID holder) {
        onMain(() -> {
            var player = plugin.getServer().getPlayer(holder);
            if (player != null) recover(player, false);
        });
    }

    public void deliver(UUID winner) {
        onMain(() -> {
            var player=plugin.getServer().getPlayer(winner);
            if(player!=null) {
                if(player.getPersistentDataContainer().has(RECLAIMED)) clearReleasedReclamation(player);
                else receive(player);
            }
        });
    }

    private void clearReleasedReclamation(Player player) {
        var data=player.getPersistentDataContainer();
        String marker=data.get(RECLAIMED,org.bukkit.persistence.PersistentDataType.STRING);
        final UUID operation;
        try {operation=UUID.fromString(marker);}catch(IllegalArgumentException | NullPointerException invalid){return;}
        recovery.releasedReclamation(player.getUniqueId(),operation).thenAcceptAsync(result -> {
            if(!live(player) || !current(player, player.getUniqueId()) || result.isEmpty())return;
            var proof=result.get();
            if(!reclaimedAndAbsent(player,marker,proof.source(),proof.definition(),player.getUniqueId()))return;
            data.remove(ESCROW);data.remove(RECLAIMED);player.saveData();
            receive(player);
        },this::onMain).exceptionally(this::reportFailure);
    }

    /** The holder's save still names this reclamation and the escrowed Crown is absent from every location. */
    private boolean reclaimedAndAbsent(Player player, String operation, ExclusiveCustodyReservation source, UUID definition,
            UUID expectedPlayer) {
        var data = player.getPersistentDataContainer();
        var escrow = data.get(ESCROW,org.bukkit.persistence.PersistentDataType.TAG_CONTAINER);
        return operation.equals(data.get(RECLAIMED,org.bukkit.persistence.PersistentDataType.STRING)) && escrow != null
                && PaperCrownSlotExchange.matches(escrow,source)
                && new PaperCrownInventoryProbe(PaperItemCodecThreadGuard.system()).observe(player,expectedPlayer,
                        definition,source.instanceId()) == PaperCrownInventoryProbe.Observation.MISSING
                && new PaperCrownSavedPlayer().confirmAbsenceIncludingFallback(player,source.instanceId(),definition);
    }

    private boolean live(Player player) {
        return active.getAsBoolean() && player.isOnline() && !player.isDead();
    }

    private boolean current(Player player, UUID holder) {
        return plugin.getServer().getPlayer(holder) == player;
    }

    private boolean marks(Player player, ExclusiveCustodyReservation source) {
        return exchange.markedCrown(player).filter(source::equals).isPresent();
    }

    private boolean holdsMarkedCrown(Player player, ExclusiveCustodyReservation source) {
        return live(player) && current(player, source.holderId()) && marks(player, source);
    }

    private static boolean helmetIs(Player player, byte[] expected) {
        var current = player.getInventory().getHelmet();
        return current != null && java.util.Arrays.equals(expected, current.serializeAsBytes());
    }

    private void recover(Player session, boolean freshJoin) {
        if (!active.getAsBoolean()) return;
        var data = session.getPersistentDataContainer();
        if (!data.has(ESCROW)) {
            // A recipient who was dead or offline when the Crown was released takes it on respawn as well as on join.
            receive(session);
            return;
        }
        if (data.has(RECLAIMED)) {
            resumeReclamation(session);
            return;
        }
        var marker = new NamespacedKey(plugin, "crown_released");
        if (data.has(marker)) {
            if (freshJoin) confirmRemoval(session, marker);
            return;
        }
        var incoming = new NamespacedKey(plugin, "crown_incoming");
        if (data.has(incoming)) {
            if (saved(session, true)) finalizeIncoming(session, incoming);
            return;
        }
        resumeEscrow(session, freshJoin, marker);
    }

    private void resumeReclamation(Player session) {
        clearReleasedReclamation(session);
        recovery.findReclamation(session.getUniqueId()).thenAcceptAsync(request ->
                request.ifPresent(value -> confirmSavedReclamation(session, value)), this::onMain)
                .exceptionally(this::reportFailure);
    }

    private void resumeEscrow(Player session, boolean freshJoin, NamespacedKey marker) {
        UUID holder = session.getUniqueId();
        var loaded = (freshJoin || saved(session,true)) ? exchange.readLoadedExchange(session) : java.util.Optional.<PaperCrownSlotExchange.LoadedExchange>empty();
        var decision = loaded.map(save -> recovery.confirmLoaded(save.reservation(), save.definition(), save.original()))
                .orElseGet(() -> recovery.findEquipped(holder));
        decision.thenAcceptAsync(result -> finishEscrow(session, holder, result, marker),
                task -> plugin.getServer().getScheduler().runTask(plugin, task)).exceptionally(failure -> {
            if (active.getAsBoolean()) plugin.getLogger().log(java.util.logging.Level.WARNING, "Crown recovery failed for " + holder, failure);
            return null;
        });
    }

    private void finishEscrow(Player session, UUID holder, java.util.Optional<CrownEquipmentRecovery.Receipt> result,
            NamespacedKey marker) {
        if (!active.getAsBoolean() || !current(session, holder) || !session.isOnline()) return;
        if (result.isEmpty()) {
            plugin.getLogger().warning("Crown escrow has no matching committed equipment receipt for " + holder);
            return;
        }
        var receipt = result.orElseThrow();
        if (!exchange.finishCommitted(session, receipt.reservation(), receipt.definition(), receipt.originalDigest())) {
            plugin.getLogger().warning("Crown escrow requires review for " + holder);
            return;
        }
        recovery.findReclamation(holder).thenAcceptAsync(request -> {
            if (request.isPresent()) stageReclamation(session, request.get());
            else stageRemoval(session, receipt, marker);
        }, this::onMain).exceptionally(this::reportFailure);
    }
    private boolean saved(Player player, boolean equipped) {
        var request = exchange.markedCrown(player);
        if (request.isEmpty()) return false;
        var item = new PaperItemIdentityCodec(PaperItemCodecThreadGuard.system()).readIdentity(player.getInventory().getHelmet());
        return item instanceof net.enthusia.loreitems.application.ItemIdentityReadResult.Tracked tracked
                && new PaperCrownSavedPlayer().agrees(player,request.get().instanceId(),tracked.identity().definitionId().value(),equipped);
    }

    private void stageReclamation(Player player, CrownEquipmentRecovery.Reclamation request) {
        var source = request.source().reservation();
        if (request.held() || !holdsMarkedCrown(player, source) || !saved(player,true)) return;
        var crown = player.getInventory().getHelmet();
        if (crown == null) return;
        byte[] original = crown.serializeAsBytes();
        recovery.stageReclamation(request,original).thenAcceptAsync(staged -> {
            if (!staged || !holdsMarkedCrown(player, source) || !helmetIs(player, original)) return;
            player.getPersistentDataContainer().set(RECLAIMED,org.bukkit.persistence.PersistentDataType.STRING,
                    request.operation().toString());
            player.getInventory().setHelmet(null);
            player.saveData();
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> confirmSavedReclamation(player,request),20L);
        }, this::onMain).exceptionally(this::reportFailure);
    }

    private void confirmSavedReclamation(Player player, CrownEquipmentRecovery.Reclamation request) {
        var source = request.source().reservation();
        if (!live(player) || !current(player, source.holderId()) || !reclaimedAndAbsent(player,
                request.operation().toString(), source, request.source().definition(), source.holderId())) return;
        recovery.confirmReclamation(request).thenAccept(held -> {
            if (!held) plugin.getLogger().warning("Crown reclamation needs review for " + request.operation());
        }).exceptionally(this::reportFailure);
    }

    private void stageRemoval(Player session, CrownEquipmentRecovery.Receipt receipt, NamespacedKey marker) {
        var source = receipt.reservation();
        var helmet = session.getInventory().getHelmet();
        if (helmet == null || session.isDead() || !marks(session, source)) return;
        byte[] bytes = helmet.serializeAsBytes();
        recovery.escrowForHandoff(receipt, bytes).thenAcceptAsync(accepted -> {
            if (!accepted || !holdsMarkedCrown(session, source) || !helmetIs(session, bytes)) return;
            releaseForHandoff(session, receipt, marker);
        }, task -> plugin.getServer().getScheduler().runTask(plugin, task)).exceptionally(error -> {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Crown handoff requires review for " + source.operationId(), error);
            return null;
        });
    }

    private void releaseForHandoff(Player session, CrownEquipmentRecovery.Receipt receipt, NamespacedKey marker) {
        var source = receipt.reservation();
        session.getPersistentDataContainer().set(marker, org.bukkit.persistence.PersistentDataType.STRING,
                source.operationId() + "/" + source.instanceId() + "/" + receipt.definition());
        session.getInventory().setHelmet(null);
        session.saveData();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (active.getAsBoolean() && session.isOnline() && current(session, source.holderId())
                    && new PaperCrownSavedPlayer().agrees(session, source.instanceId(), receipt.definition(), false))
                confirmRemoval(session, marker);
        }, 20L);
    }

    private void confirmRemoval(Player session, NamespacedKey marker) {
        if (!live(session) || !current(session, session.getUniqueId())) return;
        try {
            String[] ids = session.getPersistentDataContainer().get(marker, org.bukkit.persistence.PersistentDataType.STRING).split("/", -1);
            if (ids.length != 3) return;
            UUID operation = UUID.fromString(ids[0]);
            UUID instance = UUID.fromString(ids[1]);
            UUID definition = UUID.fromString(ids[2]);
            if (!absentEverywhere(session, instance, definition)) return;
            String expected = operation + "/" + instance + "/" + definition;
            recovery.confirmSourceReleased(session.getUniqueId(), operation, instance, definition).thenAcceptAsync(
                    killer -> afterSourceReleased(session, marker, expected, killer), this::onMain).exceptionally(this::reportFailure);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            plugin.getLogger().warning("Malformed Crown release evidence for " + session.getUniqueId());
        }
    }

    private static boolean absentEverywhere(Player session, UUID instance, UUID definition) {
        return new PaperCrownInventoryProbe(PaperItemCodecThreadGuard.system()).observe(session, session.getUniqueId(), definition,
                instance) == PaperCrownInventoryProbe.Observation.MISSING
                && new PaperCrownSavedPlayer().confirmAbsenceIncludingFallback(session,instance,definition);
    }

    private void afterSourceReleased(Player session, NamespacedKey marker, String expected, java.util.Optional<UUID> killer) {
        if (!active.getAsBoolean() || killer.isEmpty()) return;
        var data = session.getPersistentDataContainer();
        if (current(session, session.getUniqueId())
                && expected.equals(data.get(marker, org.bukkit.persistence.PersistentDataType.STRING))) {
            data.remove(marker);
            data.remove(ESCROW);
            session.saveData();
        }
        var recipient = plugin.getServer().getPlayer(killer.get());
        if (recipient != null) receive(recipient);
    }

    /** A worn helmet never blocks delivery: the Crown replaces it. */
    private boolean canReceive(Player player) {
        return active.getAsBoolean() && player.isOnline() && !player.isDead() && !player.getPersistentDataContainer().has(ESCROW);
    }

    private void receive(Player player) {
        if (!canReceive(player)) return;
        recovery.takeReleased(player.getUniqueId()).thenAcceptAsync(result -> {
            if (result.isEmpty()) {
                reportUnconfirmedDelivery(player);
                return;
            }
            equipDelivered(player, result.get());
        }, this::onMain).exceptionally(this::reportFailure);
    }

    private void reportUnconfirmedDelivery(Player player) {
        recovery.hasUnconfirmedDelivery(player.getUniqueId()).thenAccept(pending -> {
            if (pending) plugin.getLogger().warning("Crown delivery requires review for " + player.getUniqueId()
                    + ": a committed grant has no matching saved destination evidence.");
        }).exceptionally(this::reportFailure);
    }

    private void equipDelivered(Player player, CrownEquipmentRecovery.Delivery delivery) {
        var request = delivery.destination();
        if (!canReceive(player) || !current(player, request.holderId())) {
            // Nothing has been placed yet, so the claim goes back for the recipient's next respawn or join.
            recovery.returnUnequippedGrant(delivery).thenAccept(returned -> {
                if (!returned) plugin.getLogger().warning("Crown delivery requires review for " + request.operationId());
            }).exceptionally(this::reportFailure);
            return;
        }
        var crown = org.bukkit.inventory.ItemStack.deserializeBytes(delivery.crown());
        if (!deliverable(player, crown, request, delivery.definition()))
            throw new IllegalStateException("Conflicting Crown identity on delivery " + request.operationId());
        var inventory = player.getInventory();
        var prior = inventory.getHelmet();
        var data = player.getPersistentDataContainer();
        data.set(ESCROW,org.bukkit.persistence.PersistentDataType.TAG_CONTAINER,successorEscrow(data, request));
        data.set(new NamespacedKey(plugin,"crown_incoming"),org.bukkit.persistence.PersistentDataType.STRING,delivery.definition().toString());
        if (prior != null && !prior.getType().isAir()) displaceHelmet(player, prior, request);
        inventory.setHelmet(crown);
        player.saveData();
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> finalizeWhenSaved(player, request), 20L);
    }

    /**
     * The recipient's worn helmet is destroyed, so delivery is instant even with a full inventory. A tracked LoreItems
     * helmet is never destroyed outside its lifecycle: it moves to a free slot, or drops at the recipient's feet.
     */
    private void displaceHelmet(Player player, org.bukkit.inventory.ItemStack prior, ExclusiveCustodyReservation request) {
        var identity = new PaperItemIdentityCodec(PaperItemCodecThreadGuard.system()).readIdentity(prior);
        if (!(identity instanceof net.enthusia.loreitems.application.ItemIdentityReadResult.Tracked)) {
            plugin.getLogger().info("Crown delivery " + request.operationId() + " replaced " + player.getName() + "'s " + prior.getType());
            return;
        }
        var inventory = player.getInventory();
        int free = inventory.firstEmpty();
        if (free >= 0) inventory.setItem(free, prior);
        else player.getWorld().dropItem(player.getLocation(), prior);
        plugin.getLogger().info("Crown delivery " + request.operationId() + " moved " + player.getName() + "'s tracked "
                + prior.getType() + (free >= 0 ? " into their inventory" : " to the ground at their feet"));
    }

    /** Identity is read before the shape checks, as in the original single-condition form. */
    private static boolean deliverable(Player player, org.bukkit.inventory.ItemStack crown, ExclusiveCustodyReservation request,
            UUID definition) {
        var guard = PaperItemCodecThreadGuard.system();
        var identity = new PaperItemIdentityCodec(guard).readIdentity(crown);
        return PaperCrownSlotExchange.singleNetheriteHelmet(crown)
                && PaperCrownSlotExchange.tracksRequest(identity, request, definition)
                && new PaperCrownInventoryProbe(guard).observe(player, request.holderId(), definition, request.instanceId())
                == PaperCrownInventoryProbe.Observation.MISSING;
    }

    private org.bukkit.persistence.PersistentDataContainer successorEscrow(org.bukkit.persistence.PersistentDataContainer data,
            ExclusiveCustodyReservation request) {
        var escrow = data.getAdapterContext().newPersistentDataContainer();
        var text = org.bukkit.persistence.PersistentDataType.STRING;
        escrow.set(new NamespacedKey(plugin,"operation"),text,request.operationId().toString());
        escrow.set(new NamespacedKey(plugin,"holder"),text,request.holderId().toString());
        escrow.set(new NamespacedKey(plugin,"instance"),text,request.instanceId().toString());
        escrow.set(new NamespacedKey(plugin,"generation"),org.bukkit.persistence.PersistentDataType.LONG,request.generation());
        // A successor's replaced helmet is destroyed and not recorded as a sacrificed item.
        escrow.set(new NamespacedKey(plugin,"consumed"),text,"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        return escrow;
    }

    private void finalizeWhenSaved(Player player, ExclusiveCustodyReservation request) {
        if (active.getAsBoolean() && player.isOnline() && current(player, request.holderId()) && saved(player, true))
            finalizeIncoming(player, new NamespacedKey(plugin, "crown_incoming"));
    }

    private void finalizeIncoming(Player player, NamespacedKey incoming) {
        var request = exchange.markedCrown(player);
        var helmet = player.getInventory().getHelmet();
        if (request.isEmpty() || helmet == null || !saved(player, true)) return;
        var read = new PaperItemIdentityCodec(PaperItemCodecThreadGuard.system()).readIdentity(helmet);
        if (!(read instanceof net.enthusia.loreitems.application.ItemIdentityReadResult.Tracked tracked)) return;
        var definition = tracked.identity().definitionId().value();
        var target = request.get();
        if (!definition.toString().equals(player.getPersistentDataContainer().get(incoming, org.bukkit.persistence.PersistentDataType.STRING))
                || new PaperCrownInventoryProbe(PaperItemCodecThreadGuard.system()).observe(
                        player, target.holderId(), definition, target.instanceId())
                != PaperCrownInventoryProbe.Observation.HEAD_ONLY) return;
        recovery.commitSuccessor(target,definition).thenAcceptAsync(committed -> announceSuccessor(player, target, incoming, committed),
                this::onMain).exceptionally(this::reportFailure);
    }

    private void announceSuccessor(Player player, ExclusiveCustodyReservation target, NamespacedKey incoming, Boolean committed) {
        if (!active.getAsBoolean()) return;
        if (committed) plugin.getServer().broadcast(net.kyori.adventure.text.Component.text(
                player.getName() + " now wears the Crown of Sorrow."));
        recovery.findEquipped(target.holderId()).thenAcceptAsync(receipt -> {
            if (current(player, target.holderId()) && receipt.isPresent()
                    && receipt.get().reservation().equals(target) && marks(player, target)) {
                player.getPersistentDataContainer().remove(incoming);
                player.saveData();
            }
        }, this::onMain).exceptionally(this::reportFailure);
    }

    private void onMain(Runnable action) { plugin.getServer().getScheduler().runTask(plugin, action); }

    private Void reportFailure(Throwable error) {
        plugin.getLogger().log(java.util.logging.Level.WARNING,"Crown succession requires review",error);
        return null;
    }

}
