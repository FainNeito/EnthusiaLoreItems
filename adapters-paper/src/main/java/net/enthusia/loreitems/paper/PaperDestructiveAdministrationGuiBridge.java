package net.enthusia.loreitems.paper;

import java.util.Objects;
import net.enthusia.loreitems.domain.LoreInstanceId;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

/** Adds destructive previews to the existing management browsers without duplicating command logic. */
final class PaperDestructiveAdministrationGuiBridge implements Listener {
    private static final int INVENTORY_SIZE = 54;

    private final Plugin plugin;

    PaperDestructiveAdministrationGuiBridge(Plugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)
                || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= INVENTORY_SIZE) {
            return;
        }
        Object holder = event.getInventory().getHolder();
        if (holder instanceof PaperTemplateEditorView view) {
            handleDefinitionAction(player, view, slot);
        } else if (holder instanceof PaperTrackingAdministrationView view) {
            handleInstanceAction(player, view, slot, event.isRightClick());
        }
    }

    private void handleDefinitionAction(
            Player player, PaperTemplateEditorView view, int slot) {
        if (view.screen != PaperTemplateEditorView.Screen.MANAGEMENT) {
            return;
        }
        if (slot == PaperTemplateEditorRenderer.MANAGEMENT_PURGE) {
            preview(
                    player,
                    LoreItemsDestructiveCommandExecutor.PURGE_PERMISSION,
                    "purge " + view.definitionId().value());
        } else if (slot == PaperTemplateEditorRenderer.MANAGEMENT_DELETE) {
            preview(
                    player,
                    LoreItemsDestructiveCommandExecutor.DELETE_PERMISSION,
                    "delete " + view.definitionId().value());
        }
    }

    private void handleInstanceAction(
            Player player,
            PaperTrackingAdministrationView view,
            int slot,
            boolean rightClick) {
        if (!rightClick
                || view.screen != PaperTrackingAdministrationView.Screen.INSTANCES
                || slot >= view.instanceIds.size()) {
            return;
        }
        LoreInstanceId instanceId = view.instanceIds.get(slot);
        preview(
                player,
                LoreItemsDestructiveCommandExecutor.REMOVE_PERMISSION,
                "remove " + view.definitionId.value() + ' ' + instanceId.value());
    }

    private void preview(Player player, String permission, String command) {
        if (!player.hasPermission(permission)) {
            LoreItemsMessages.send(player, "You do not have permission: " + permission);
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                player.closeInventory();
                player.performCommand("loreitems " + command);
            });
        } catch (IllegalPluginAccessException ignored) {
            // Plugin shutdown invalidates the GUI action before it can create durable intent.
        }
    }
}
