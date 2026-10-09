package net.enthusia.loreitems.paper;

import java.util.UUID;
import java.util.function.BiConsumer;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Separates creative set-slot validation from delayed disappearance evidence. */
final class PaperCreativeInventoryMutationSupport {
    private PaperCreativeInventoryMutationSupport() {}

    static void handle(
            Plugin plugin,
            InventoryCreativeEvent event,
            PaperCreativeIdentityProtection protection,
            PaperItemIdentityCodec codec,
            PaperCreativeCopyController controller,
            BiConsumer<UUID, LoreItemIdentity> missingObserver) {
        if (protection.shouldCancelInventoryMutation(event)) {
            event.setCancelled(true);
            return;
        }
        if (controller == null || missingObserver == null
                || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack old = event.getCurrentItem();
        if (!mayHaveGoneMissing(codec, old, event.getCursor())) {
            return;
        }
        ItemIdentityReadResult result = codec.readIdentity(old);
        if (result instanceof ItemIdentityReadResult.Tracked tracked) {
            scheduleMissingCheck(plugin, player.getUniqueId(), tracked.identity(),
                    controller, missingObserver);
        }
    }

    private static boolean mayHaveGoneMissing(
            PaperItemIdentityCodec codec, ItemStack original, ItemStack replacement) {
        return original != null && !original.getType().isAir()
                && codec.hasIdentityEvidence(original)
                && replacement.getType().isAir();
    }

    private static void scheduleMissingCheck(
            Plugin plugin, UUID playerId, LoreItemIdentity identity,
            PaperCreativeCopyController controller,
            BiConsumer<UUID, LoreItemIdentity> missingObserver) {
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            Player current = plugin.getServer().getPlayer(playerId);
            if (current != null && !controller.stillHasSource(current, identity)) {
                missingObserver.accept(playerId, identity);
            }
        }, 10L);
    }
}
