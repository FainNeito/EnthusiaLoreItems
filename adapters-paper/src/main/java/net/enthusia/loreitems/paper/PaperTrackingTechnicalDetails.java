package net.enthusia.loreitems.paper;

import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Explicit diagnostic details that are hidden from the ordinary browser. */
final class PaperTrackingTechnicalDetails {
    private PaperTrackingTechnicalDetails() {}

    static void show(Plugin plugin, Player player, PaperTrackingAdministrationView view, int slot) {
        if (slot == PaperTrackingAdministrationItems.STATUS) {
            player.sendMessage("LoreItems diagnostics: " + String.join(" | ",
                    PaperTrackingAdministrationItems.trackingMetricsLore(plugin)));
            return;
        }
        switch (view.screen) {
            case DEFINITIONS -> showDefinition(player, view, slot);
            case INSTANCES -> showInstance(player, view, slot);
            case EVIDENCE -> showObservation(player, view, slot);
            default -> { }
        }
    }

    private static void showDefinition(Player player, PaperTrackingAdministrationView view, int slot) {
        if (slot < view.definitionIds.size()) {
            player.sendMessage("Definition UUID: " + view.definitionIds.get(slot).value());
        }
    }

    private static void showInstance(Player player, PaperTrackingAdministrationView view, int slot) {
        if (slot < view.instanceIds.size()) {
            player.sendMessage("Tracked instance UUID: " + view.instanceIds.get(slot).value());
        }
    }

    private static void showObservation(Player player, PaperTrackingAdministrationView view, int slot) {
        if (slot >= view.observations.size()) {
            return;
        }
        ObservationChoice choice = view.observations.get(slot);
        player.sendMessage("Observation #" + choice.observationId()
                + " | " + choice.confidence().name() + " | source: " + choice.source());
        player.sendMessage("Raw location: " + choice.location().type().name()
                + " / " + choice.location().locationKey()
                + " / " + choice.location().containerPath());
    }
}
