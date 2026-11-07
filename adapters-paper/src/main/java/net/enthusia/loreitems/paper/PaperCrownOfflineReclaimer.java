package net.enthusia.loreitems.paper;

import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import net.enthusia.loreitems.application.CrownEquipmentRecovery;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.Plugin;

/** Owns login exclusion and the bounded offline save worker for a single Crown. */
public final class PaperCrownOfflineReclaimer implements Listener, AutoCloseable {
    private static final net.kyori.adventure.text.Component WAIT = net.kyori.adventure.text.Component.text(
            "Crown recovery is checking your saved inventory. Please try again shortly.");
    private final Plugin plugin;
    private final BooleanSupplier active;
    private final Set<UUID> blocked=ConcurrentHashMap.newKeySet();
    private final Set<UUID> running=ConcurrentHashMap.newKeySet();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(task -> {
        Thread thread=new Thread(task,"crown-offline-save"); thread.setDaemon(true); return thread;
    });
    private final Object writeLock=new Object();
    private volatile boolean ready;
    private volatile boolean closed;
    private CrownEquipmentRecovery recovery;

    public PaperCrownOfflineReclaimer(Plugin plugin, BooleanSupplier active) {
        this.plugin=plugin; this.active=active;
        plugin.getServer().getPluginManager().registerEvents(this,plugin);
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void prelogin(AsyncPlayerPreLoginEvent event) {
        if(!ready || blocked.contains(event.getUniqueId())) event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_OTHER,WAIT);
    }
    @EventHandler(priority=EventPriority.HIGHEST)
    public void login(io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent event) {
        UUID holder = switch(event.getConnection()) {
            case io.papermc.paper.connection.PlayerLoginConnection connection -> connection.getAuthenticatedProfile().getId();
            case io.papermc.paper.connection.PlayerConfigurationConnection connection -> connection.getProfile().getId();
            case io.papermc.paper.connection.PlayerGameConnection connection -> connection.getPlayer().getUniqueId();
            default -> null;
        };
        if(!ready || (holder == null ? !blocked.isEmpty() : blocked.contains(holder))) event.kickMessage(WAIT);
    }
    public void start(CrownEquipmentRecovery recovery) {
        this.recovery=recovery;
        recovery.inspectCustody().whenComplete((snapshot,error) -> onMain(() -> {
            if(error!=null) { report(error); return; }
            snapshot.filter(value -> value.reclamationOperation().isPresent() && value.transfer().isEmpty()).ifPresent(value -> reclaim(value.recordedHolder()));
            ready=true;
        }));
    }
    /** Must run on the server thread, so the offline check and login exclusion are established together. */
    public void reclaim(UUID holder) {
        if(closed || !active.getAsBoolean() || recovery==null || plugin.getServer().getPlayer(holder)!=null) return;
        blocked.add(holder);
        if(!running.add(holder)) return;
        if(!plugin.getServer().getMinecraftVersion().equals("26.2")) {
            running.remove(holder);
            report(new IllegalStateException("Offline Crown removal requires the verified Paper 26.2 save layout"));
            return;
        }
        Path directory=PaperCrownSavedPlayer.playerDirectory(
                plugin.getServer().getWorlds().getFirst().getWorldFolder().toPath(),"26.2");
        recovery.inspectCustody().thenCompose(snapshot -> {
            // A recipient grant owns the next step, including when the former holder wins again.
            // Blocking that login would prevent incoming-save recovery after a crash.
            if(snapshot.filter(value -> value.transfer().isPresent()).isPresent()) return CompletableFuture.completedFuture(true);
            return recovery.findReclamation(holder).thenCompose(found -> found.isEmpty()
                    ? CompletableFuture.completedFuture(false) : removeOffline(directory, found.get()));
        }).whenComplete((complete,error) -> onMain(() -> finishReclaim(holder, complete, error)));
    }
    private CompletionStage<Boolean> removeOffline(Path directory, CrownEquipmentRecovery.Reclamation request) {
        return CompletableFuture.supplyAsync(() -> {
            try { return PaperCrownOfflineSave.prepare(directory,request); }
            catch(Exception error) { throw new CompletionException(error); }
        },worker).thenCompose(prepared -> stage(prepared).thenCompose(staged -> staged
                ? applyAndConfirm(prepared, request) : CompletableFuture.completedFuture(false)));
    }
    private CompletionStage<Boolean> applyAndConfirm(PaperCrownOfflineSave.Prepared prepared, CrownEquipmentRecovery.Reclamation request) {
        return CompletableFuture.supplyAsync(() -> applySave(prepared, request),worker)
                .thenCompose(saved -> saved ? recovery.confirmReclamation(request) : CompletableFuture.completedFuture(false));
    }
    private boolean applySave(PaperCrownOfflineSave.Prepared prepared, CrownEquipmentRecovery.Reclamation request) {
        synchronized(writeLock) {
            if(closed || !active.getAsBoolean()) return false;
            try {
                PaperCrownOfflineSave.apply(prepared,plugin.getDataFolder().toPath()
                        .resolve("crown-save-evidence").resolve(request.operation().toString()));
                return true;
            } catch(Exception error) { throw new CompletionException(error); }
        }
    }
    private void finishReclaim(UUID holder, Boolean complete, Throwable error) {
        running.remove(holder);
        if(error==null && Boolean.TRUE.equals(complete)) {
            blocked.remove(holder);
            plugin.getLogger().info("Offline Crown removal confirmed for " + holder);
        } else report(error==null ? new IllegalStateException("Offline Crown removal requires review for " + holder) : error);
    }
    private CompletionStage<Boolean> stage(PaperCrownOfflineSave.Prepared prepared) {
        if(prepared.crown()==null) return CompletableFuture.completedFuture(true);
        var result=new CompletableFuture<Boolean>();
        onMain(() -> stageOnMain(prepared, result));
        return result;
    }
    private void stageOnMain(PaperCrownOfflineSave.Prepared prepared, CompletableFuture<Boolean> result) {
        if(closed || !active.getAsBoolean() || plugin.getServer().getPlayer(prepared.request().source().reservation().holderId())!=null) {
            result.complete(false); return;
        }
        try {
            var item=org.bukkit.inventory.ItemStack.deserializeBytes(prepared.crown());
            var identity=new PaperItemIdentityCodec(PaperItemCodecThreadGuard.system()).readIdentity(item);
            var source=prepared.request().source();
            if(!PaperCrownSlotExchange.singleNetheriteHelmet(item)
                    || !PaperCrownSlotExchange.tracksRequest(identity, source.reservation(), source.definition())) {
                result.complete(false); return;
            }
            recovery.stageReclamation(prepared.request(),item.serializeAsBytes()).whenComplete((staged,error) -> {
                if(error!=null) result.completeExceptionally(error); else result.complete(staged);
            });
        } catch(Exception error) { result.completeExceptionally(error); }
    }
    private void onMain(Runnable task) {
        if(!closed && active.getAsBoolean()) plugin.getServer().getScheduler().runTask(plugin,task);
    }
    private void report(Throwable error) {
        plugin.getLogger().log(java.util.logging.Level.WARNING,"Offline Crown recovery held; login protection remains active",error);
    }
    @Override public void close() {
        synchronized(writeLock) { closed=true; }
        worker.shutdownNow();
    }
}
