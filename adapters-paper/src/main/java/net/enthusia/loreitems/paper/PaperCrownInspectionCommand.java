package net.enthusia.loreitems.paper;

import java.util.function.BooleanSupplier;
import net.enthusia.loreitems.application.CrownEquipmentRecovery;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;

/** Staff inspection of recorded custody, without item or journal mutation. */
public final class PaperCrownInspectionCommand implements CommandExecutor {
    private final Plugin plugin;
    private final CrownEquipmentRecovery recovery;
    private final BooleanSupplier active;

    public PaperCrownInspectionCommand(Plugin plugin, CrownEquipmentRecovery recovery, BooleanSupplier active) {
        this.plugin = java.util.Objects.requireNonNull(plugin);
        this.recovery = java.util.Objects.requireNonNull(recovery);
        this.active = java.util.Objects.requireNonNull(active);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] arguments) {
        if (!sender.hasPermission(LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION)) {
            LoreItemsMessages.send(sender, "You do not have permission to inspect Crown custody.");
            return true;
        }
        if (arguments.length != 1) {
            LoreItemsMessages.send(sender, "Use /loreitems crown to inspect recorded custody.");
            return true;
        }
        if (!active.getAsBoolean()) {
            LoreItemsMessages.send(sender, "Crown custody inspection is unavailable.");
            return true;
        }
        recovery.inspectCustody().whenComplete((result, error) -> {
            if (!active.getAsBoolean()) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!active.getAsBoolean()
                        || !sender.hasPermission(LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION)) return;
                if (error != null) {
                    LoreItemsMessages.send(sender, "Crown custody inspection failed. Check the server log.");
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Crown inspection failed", error);
                } else if (result.isEmpty()) {
                    LoreItemsMessages.send(sender, "No Crown custody record exists.");
                } else {
                    var record = result.get();
                    LoreItemsMessages.send(sender, "Recorded Crown state " + record.state() + ", generation " + record.generation());
                    LoreItemsMessages.send(sender, "Recorded holder " + record.recordedHolder());
                    LoreItemsMessages.send(sender, "Crown " + record.instance() + ", custody operation " + record.operation());
                    record.reclamationOperation().ifPresent(operation -> LoreItemsMessages.send(sender,
                            "Reclamation " + operation + " state " + record.reclamationState()
                                    + ". Recovery-event release remains pending."));
                    record.transfer().ifPresent(transfer -> {
                        LoreItemsMessages.send(sender, "Pending recipient " + transfer.recipient()
                                + ", source removal confirmed " + transfer.sourceReleased());
                        transfer.deliveryOperation().ifPresent(operation -> LoreItemsMessages.send(sender,
                                "Delivery " + operation + " awaits matching saved recipient evidence. Review required."));
                    });
                }
            });
        });
        return true;
    }
}
