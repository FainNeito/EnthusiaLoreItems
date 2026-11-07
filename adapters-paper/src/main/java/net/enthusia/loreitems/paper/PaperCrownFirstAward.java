package net.enthusia.loreitems.paper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.BooleanSupplier;
import net.enthusia.loreitems.api.v1.CrownFirstAwardV1;
import net.enthusia.loreitems.application.CrownExchangeAdmission;
import net.enthusia.loreitems.application.DefinitionRepository;
import net.enthusia.loreitems.application.EncodedItemTemplate;
import net.enthusia.loreitems.domain.ExclusiveCustodyReservation;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.plugin.Plugin;

public final class PaperCrownFirstAward implements CrownFirstAwardV1 {
    private final Plugin plugin;
    private final DefinitionRepository definitions;
    private final CrownExchangeAdmission admission;
    private final BooleanSupplier active;
    private final LoreDefinitionId definition;
    private final TemplateRevision revision;
    private final PaperItemCodecThreadGuard guard = PaperItemCodecThreadGuard.system();
    private final PaperCrownPreparationFence fence = new PaperCrownPreparationFence(guard);

    public PaperCrownFirstAward(Plugin plugin, DefinitionRepository definitions, CrownExchangeAdmission admission,
            BooleanSupplier active, UUID definition, long revision) {
        this.plugin = plugin;
        this.definitions = definitions;
        this.admission = admission;
        this.active = active;
        this.definition = new LoreDefinitionId(definition);
        this.revision = new TemplateRevision(revision);
    }

    @Override
    public CompletionStage<Boolean> stageVerifiedWinner(UUID match, UUID winner) {
        guard.requirePrimaryThread();
        var player = plugin.getServer().getPlayer(winner);
        if (!active.getAsBoolean() || player == null) return CompletableFuture.completedFuture(false);
        Executor primary = task -> plugin.getServer().getScheduler().runTask(plugin, task);
        var instance = UUID.nameUUIDFromBytes(("loreitems:first-crown:" + match).getBytes(StandardCharsets.UTF_8));
        var request = new ExclusiveCustodyReservation(match, instance, winner, 1, ExclusiveCustodyReservation.State.RESERVED);
        return definitions.findRevision(definition, revision).thenComposeAsync(row -> {
            if (row.isEmpty() || !active.getAsBoolean() || plugin.getServer().getPlayer(winner) != player)
                return CompletableFuture.completedFuture(false);
            var record = row.orElseThrow();
            var template = new EncodedItemTemplate(record.codecVersion(), record.templateBlob());
            return new PaperCrownSlotExchange(guard).stageFirst(player, request, definition.value(), revision.value(),
                    template, admission, fence, plugin, primary).thenApply(result -> {
                if (result != PaperCrownSlotExchange.Result.STAGED) return false;
                player.saveData();
                return true;
            });
        }, primary);
    }
}
