package net.enthusia.loreitems.paper;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.command.CommandSender;

/** Common styling for staff-facing messages. */
final class LoreItemsMessages {
    private static final List<String> ERRORS = List.of(
            "failed", "invalid", "permission", "unavailable", "rejected", "not found");
    private static final List<String> WARNINGS = List.of(
            "review", "pending", "already", "cancelled");
    private static final List<String> SUCCESSES = List.of(
            "created", "adopted", "accepted", "completed");

    private LoreItemsMessages() {}

    static void send(CommandSender sender, String message) {
        Objects.requireNonNull(sender, "sender").sendMessage(format(message));
    }

    static Component format(String message) {
        String lowered = Objects.requireNonNull(message, "message").toLowerCase(Locale.ROOT);
        return Component.text("✦ ", TextColor.color(0x67E8F9))
                .append(Component.text("LORE ITEMS", TextColor.color(0xA78BFA),
                        TextDecoration.BOLD))
                .append(Component.text("  »  ", NamedTextColor.DARK_GRAY))
                .append(Component.text(message, tone(lowered))
                        .decoration(TextDecoration.ITALIC, false));
    }

    private static NamedTextColor tone(String lowered) {
        if (containsAny(lowered, ERRORS)) {
            return NamedTextColor.RED;
        }
        if (containsAny(lowered, WARNINGS)) {
            return NamedTextColor.YELLOW;
        }
        if (containsAny(lowered, SUCCESSES)) {
            return NamedTextColor.GREEN;
        }
        return NamedTextColor.GRAY;
    }

    private static boolean containsAny(String text, List<String> phrases) {
        for (String phrase : phrases) {
            if (text.contains(phrase)) {
                return true;
            }
        }
        return false;
    }
}
