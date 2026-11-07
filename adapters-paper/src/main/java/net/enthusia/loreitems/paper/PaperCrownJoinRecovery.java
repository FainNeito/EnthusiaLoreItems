package net.enthusia.loreitems.paper;

import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.enthusia.loreitems.application.CrownEquipmentRecovery;
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
            if(!active.getAsBoolean() || !player.isOnline() || player.isDead() ||
                    plugin.getServer().getPlayer(player.getUniqueId())!=player || result.isEmpty())return;
            var proof=result.get();var escrow=data.get(ESCROW,org.bukkit.persistence.PersistentDataType.TAG_CONTAINER);
            if(!marker.equals(data.get(RECLAIMED,org.bukkit.persistence.PersistentDataType.STRING)) || escrow==null ||
                    !PaperCrownSlotExchange.matches(escrow,proof.source()) ||
                    new PaperCrownInventoryProbe(PaperItemCodecThreadGuard.system()).observe(player,player.getUniqueId(),
                            proof.definition(),proof.source().instanceId())!=PaperCrownInventoryProbe.Observation.MISSING ||
                    !new PaperCrownSavedPlayer().confirmAbsenceIncludingFallback(player,proof.source().instanceId(),proof.definition()))return;
            data.remove(ESCROW);data.remove(RECLAIMED);player.saveData();
            receive(player);
        },this::onMain).exceptionally(this::reportFailure);
    }

    private void recover(Player session, boolean freshJoin) {
        if (!active.getAsBoolean()) return;
        if (!session.getPersistentDataContainer().has(ESCROW)) {
            if (freshJoin) receive(session);
            return;
        }
        if (session.getPersistentDataContainer().has(RECLAIMED)) {
            clearReleasedReclamation(session);
            recovery.findReclamation(session.getUniqueId()).thenAcceptAsync(request ->
                    request.ifPresent(value -> confirmSavedReclamation(session, value)), this::onMain)
                    .exceptionally(this::reportFailure);
            return;
        }
        UUID holder = session.getUniqueId();
        var data = session.getPersistentDataContainer();
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
        var loaded = (freshJoin || saved(session,true)) ? exchange.readLoadedExchange(session) : java.util.Optional.<PaperCrownSlotExchange.LoadedExchange>empty();
        var decision = loaded.map(save -> recovery.confirmLoaded(save.reservation(), save.definition(), save.original()))
                .orElseGet(() -> recovery.findEquipped(holder));
        decision.thenAcceptAsync(result -> {
            if (!active.getAsBoolean() || plugin.getServer().getPlayer(holder) != session || !session.isOnline()) return;
            if (result.isEmpty()) {
                plugin.getLogger().warning("Crown escrow has no matching committed equipment receipt for " + holder);
                return;
            }
            var receipt = result.orElseThrow();
            if (!exchange.finishCommitted(session, receipt.reservation(), receipt.definition(), receipt.originalDigest())) {
                plugin.getLogger().warning("Crown escrow requires review for " + holder);
            } else {
                recovery.findReclamation(holder).thenAcceptAsync(request -> {
                    if (request.isPresent()) stageReclamation(session, request.get());
                    else stageRemoval(session, receipt, marker);
                }, this::onMain).exceptionally(this::reportFailure);
            }
        }, task -> plugin.getServer().getScheduler().runTask(plugin, task)).exceptionally(failure -> {
            if (active.getAsBoolean()) plugin.getLogger().log(java.util.logging.Level.WARNING, "Crown recovery failed for " + holder, failure);
            return null;
        });
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
        if (request.held() || !active.getAsBoolean() || player.isDead() || !player.isOnline()
                || plugin.getServer().getPlayer(source.holderId()) != player
                || exchange.markedCrown(player).filter(source::equals).isEmpty() || !saved(player,true)) return;
        var crown = player.getInventory().getHelmet();
        if (crown == null) return;
        byte[] original = crown.serializeAsBytes();
        recovery.stageReclamation(request,original).thenAcceptAsync(staged -> {
            if (!staged || !active.getAsBoolean() || !player.isOnline() || player.isDead()
                    || plugin.getServer().getPlayer(source.holderId()) != player
                    || exchange.markedCrown(player).filter(source::equals).isEmpty()) return;
            var current = player.getInventory().getHelmet();
            if (current == null || !java.util.Arrays.equals(original,current.serializeAsBytes())) return;
            player.getPersistentDataContainer().set(RECLAIMED,org.bukkit.persistence.PersistentDataType.STRING,
                    request.operation().toString());
            player.getInventory().setHelmet(null);
            player.saveData();
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> confirmSavedReclamation(player,request),20L);
        }, this::onMain).exceptionally(this::reportFailure);
    }

    private void confirmSavedReclamation(Player player, CrownEquipmentRecovery.Reclamation request) {
        var source = request.source().reservation();
        var data = player.getPersistentDataContainer();
        var escrow = data.get(ESCROW,org.bukkit.persistence.PersistentDataType.TAG_CONTAINER);
        if (!active.getAsBoolean() || !player.isOnline() || player.isDead()
                || plugin.getServer().getPlayer(source.holderId()) != player || escrow == null
                || !PaperCrownSlotExchange.matches(escrow,source)
                || !request.operation().toString().equals(data.get(RECLAIMED,org.bukkit.persistence.PersistentDataType.STRING))
                || new PaperCrownInventoryProbe(PaperItemCodecThreadGuard.system()).observe(player,
                        source.holderId(),request.source().definition(),source.instanceId()) != PaperCrownInventoryProbe.Observation.MISSING
                || !new PaperCrownSavedPlayer().confirmAbsenceIncludingFallback(player,source.instanceId(),request.source().definition())) return;
        recovery.confirmReclamation(request).thenAccept(held -> {
            if (!held) plugin.getLogger().warning("Crown reclamation needs review for " + request.operation());
        }).exceptionally(this::reportFailure);
    }

    private void stageRemoval(Player session, CrownEquipmentRecovery.Receipt receipt, NamespacedKey marker) {
        var source = receipt.reservation();
        var helmet = session.getInventory().getHelmet();
        if (helmet == null || session.isDead() || exchange.markedCrown(session).filter(source::equals).isEmpty()) return;
        byte[] bytes = helmet.serializeAsBytes();
        recovery.escrowForHandoff(receipt, bytes).thenAcceptAsync(accepted -> {
            if (!accepted || !active.getAsBoolean() || session.isDead() || !session.isOnline()
                    || plugin.getServer().getPlayer(source.holderId()) != session
                    || exchange.markedCrown(session).filter(source::equals).isEmpty()) return;
            var current = session.getInventory().getHelmet();
            if (current == null || !java.util.Arrays.equals(bytes, current.serializeAsBytes())) return;
            var data = session.getPersistentDataContainer();
            data.set(marker, org.bukkit.persistence.PersistentDataType.STRING,
                    source.operationId() + "/" + source.instanceId() + "/" + receipt.definition());
            session.getInventory().setHelmet(null);
            session.saveData();
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (active.getAsBoolean() && session.isOnline()
                        && plugin.getServer().getPlayer(source.holderId()) == session
                        && new PaperCrownSavedPlayer().agrees(session, source.instanceId(), receipt.definition(), false))
                    confirmRemoval(session, marker);
            }, 20L);
        }, task -> plugin.getServer().getScheduler().runTask(plugin, task)).exceptionally(error -> {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Crown handoff requires review for " + source.operationId(), error);
            return null;
        });
    }

    private void confirmRemoval(Player session, NamespacedKey marker) {
        if (!active.getAsBoolean() || !session.isOnline() || session.isDead()
                || plugin.getServer().getPlayer(session.getUniqueId()) != session) return;
        try {
            String[] ids = session.getPersistentDataContainer().get(marker, org.bukkit.persistence.PersistentDataType.STRING).split("/", -1);
            if (ids.length != 3) return;
            UUID operation = UUID.fromString(ids[0]), instance = UUID.fromString(ids[1]), definition = UUID.fromString(ids[2]);
            var probe = new PaperCrownInventoryProbe(PaperItemCodecThreadGuard.system());
            if (probe.observe(session, session.getUniqueId(), definition, instance) != PaperCrownInventoryProbe.Observation.MISSING) return;
            if (!new PaperCrownSavedPlayer().confirmAbsenceIncludingFallback(session,instance,definition)) return;
            recovery.confirmSourceReleased(session.getUniqueId(), operation, instance, definition).thenAcceptAsync(killer -> {
                if (!active.getAsBoolean() || killer.isEmpty()) return;
                if (plugin.getServer().getPlayer(session.getUniqueId()) == session) {
                    var data = session.getPersistentDataContainer();
                    String expected = operation + "/" + instance + "/" + definition;
                    if (expected.equals(data.get(marker, org.bukkit.persistence.PersistentDataType.STRING))) {
                        data.remove(marker);
                        data.remove(ESCROW);
                        session.saveData();
                    }
                }
                var recipient = plugin.getServer().getPlayer(killer.get());
                if (recipient != null) receive(recipient);
            }, this::onMain).exceptionally(this::reportFailure);
        } catch (IllegalArgumentException | NullPointerException malformed) {
            plugin.getLogger().warning("Malformed Crown release evidence for " + session.getUniqueId());
        }
    }

    private boolean canReceive(Player player) {
        if (!active.getAsBoolean() || !player.isOnline() || player.isDead() || player.getPersistentDataContainer().has(ESCROW)) return false;
        var helmet = player.getInventory().getHelmet();
        return helmet == null || helmet.getType().isAir() || player.getInventory().firstEmpty() >= 0;
    }

    private void receive(Player player) {
        if (!canReceive(player)) return;
        recovery.takeReleased(player.getUniqueId()).thenAcceptAsync(result -> {
            if (result.isEmpty()) {
                recovery.hasUnconfirmedDelivery(player.getUniqueId()).thenAccept(pending -> {
                    if (pending) plugin.getLogger().warning("Crown delivery requires review for " + player.getUniqueId()
                            + ": a committed grant has no matching saved destination evidence.");
                }).exceptionally(this::reportFailure);
                return;
            }
            var delivery = result.get();
            var request = delivery.destination();
            if (!canReceive(player) || plugin.getServer().getPlayer(request.holderId()) != player)
                throw new IllegalStateException("Recipient changed after Crown reservation " + request.operationId());
            var guard = PaperItemCodecThreadGuard.system();
            var crown = org.bukkit.inventory.ItemStack.deserializeBytes(delivery.crown());
            var identity = new PaperItemIdentityCodec(guard).readIdentity(crown);
            if (crown.getType() != org.bukkit.Material.NETHERITE_HELMET || crown.getAmount() != 1
                    || !(identity instanceof net.enthusia.loreitems.application.ItemIdentityReadResult.Tracked tracked)
                    || !tracked.identity().definitionId().value().equals(delivery.definition())
                    || !tracked.identity().instanceId().value().equals(request.instanceId())
                    || new PaperCrownInventoryProbe(guard).observe(player, request.holderId(), delivery.definition(), request.instanceId())
                    != PaperCrownInventoryProbe.Observation.MISSING)
                throw new IllegalStateException("Conflicting Crown identity on delivery " + request.operationId());
            var inventory = player.getInventory();
            var prior = inventory.getHelmet();
            var data = player.getPersistentDataContainer();
            var escrow = data.getAdapterContext().newPersistentDataContainer();
            var text = org.bukkit.persistence.PersistentDataType.STRING;
            escrow.set(new NamespacedKey(plugin,"operation"),text,request.operationId().toString());
            escrow.set(new NamespacedKey(plugin,"holder"),text,request.holderId().toString());
            escrow.set(new NamespacedKey(plugin,"instance"),text,request.instanceId().toString());
            escrow.set(new NamespacedKey(plugin,"generation"),org.bukkit.persistence.PersistentDataType.LONG,request.generation());
            // A successor's own helmet is moved intact; there is no sacrificed item.
            escrow.set(new NamespacedKey(plugin,"consumed"),text,"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
            data.set(ESCROW,org.bukkit.persistence.PersistentDataType.TAG_CONTAINER,escrow);
            data.set(new NamespacedKey(plugin,"crown_incoming"),text,delivery.definition().toString());
            if (prior != null && !prior.getType().isAir()) inventory.setItem(inventory.firstEmpty(),prior);
            inventory.setHelmet(crown);
            player.saveData();
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (active.getAsBoolean() && player.isOnline()
                        && plugin.getServer().getPlayer(request.holderId()) == player && saved(player, true))
                    finalizeIncoming(player, new NamespacedKey(plugin, "crown_incoming"));
            }, 20L);
        }, this::onMain).exceptionally(this::reportFailure);
    }

    private void finalizeIncoming(Player player, NamespacedKey incoming) {
        var request = exchange.markedCrown(player);
        var helmet = player.getInventory().getHelmet();
        if (request.isEmpty() || helmet == null || !saved(player, true)) return;
        var read = new PaperItemIdentityCodec(PaperItemCodecThreadGuard.system()).readIdentity(helmet);
        if (!(read instanceof net.enthusia.loreitems.application.ItemIdentityReadResult.Tracked tracked)) return;
        var definition = tracked.identity().definitionId().value();
        if (!definition.toString().equals(player.getPersistentDataContainer().get(incoming, org.bukkit.persistence.PersistentDataType.STRING))) return;
        var target = request.get();
        if (new PaperCrownInventoryProbe(PaperItemCodecThreadGuard.system()).observe(
                player, target.holderId(), definition, target.instanceId())
                != PaperCrownInventoryProbe.Observation.HEAD_ONLY) return;
        recovery.commitSuccessor(target,definition).thenAcceptAsync(committed -> {
            if (!active.getAsBoolean()) return;
            if (committed) plugin.getServer().broadcast(net.kyori.adventure.text.Component.text(
                    player.getName() + " now wears the Crown of Sorrow."));
            recovery.findEquipped(target.holderId()).thenAcceptAsync(receipt -> {
                if (plugin.getServer().getPlayer(target.holderId()) == player && receipt.isPresent()
                        && receipt.get().reservation().equals(target) && exchange.markedCrown(player).filter(target::equals).isPresent()) {
                    player.getPersistentDataContainer().remove(incoming);
                    player.saveData();
                }
            }, this::onMain).exceptionally(this::reportFailure);
        }, this::onMain).exceptionally(this::reportFailure);
    }

    private void onMain(Runnable action) { plugin.getServer().getScheduler().runTask(plugin, action); }

    private Void reportFailure(Throwable error) {
        plugin.getLogger().log(java.util.logging.Level.WARNING,"Crown succession requires review",error);
        return null;
    }

}
