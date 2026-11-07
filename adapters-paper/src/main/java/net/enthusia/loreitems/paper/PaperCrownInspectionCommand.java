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
            sender.sendMessage("You do not have permission to inspect Crown custody.");
            return true;
        }
        if (arguments.length != 1) {
            sender.sendMessage("Use /loreitems crown to inspect recorded custody.");
            return true;
        }
        if (!active.getAsBoolean()) {
            sender.sendMessage("Crown custody inspection is unavailable.");
            return true;
        }
        recovery.inspectCustody().whenComplete((result, error) -> {
            if (!active.getAsBoolean()) return;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!active.getAsBoolean()
                        || !sender.hasPermission(LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION)) return;
                if (error != null) {
                    sender.sendMessage("Crown custody inspection failed. Check the server log.");
                    plugin.getLogger().log(java.util.logging.Level.WARNING, "Crown inspection failed", error);
                } else if (result.isEmpty()) {
                    sender.sendMessage("No Crown custody record exists.");
                } else {
                    var record = result.get();
                    sender.sendMessage("Recorded Crown state " + record.state() + ", generation " + record.generation());
                    sender.sendMessage("Recorded holder " + record.recordedHolder());
                    sender.sendMessage("Crown " + record.instance() + ", custody operation " + record.operation());
                    record.reclamationOperation().ifPresent(operation -> sender.sendMessage(
                            "Reclamation " + operation + " state " + record.reclamationState()
                                    + ". Recovery-event release remains pending."));
                    record.transfer().ifPresent(transfer -> {
                        sender.sendMessage("Pending recipient " + transfer.recipient()
                                + ", source removal confirmed " + transfer.sourceReleased());
                        transfer.deliveryOperation().ifPresent(operation -> sender.sendMessage(
                                "Delivery " + operation + " awaits matching saved recipient evidence. Review required."));
                    });
                }
            });
        });
        return true;
    }
}
