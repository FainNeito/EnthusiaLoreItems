package net.enthusia.loreitems.paper;

import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/** Shared visual language for staff-facing inventory GUIs. */
final class PaperGuiStyle {
    enum Tone {
        PRIMARY(NamedTextColor.AQUA),
        INFO(NamedTextColor.WHITE),
        POSITIVE(NamedTextColor.GREEN),
        WARNING(NamedTextColor.GOLD),
        DESTRUCTIVE(NamedTextColor.RED),
        NAVIGATION(NamedTextColor.GRAY),
        METADATA(NamedTextColor.GOLD);

        private final NamedTextColor color;

        Tone(NamedTextColor color) {
            this.color = color;
        }
    }

    private PaperGuiStyle() {}

    static Component inventoryTitle(String text) {
        return text(text, NamedTextColor.GOLD);
    }

    static Component name(String text, Tone tone) {
        Objects.requireNonNull(tone, "tone");
        return text(text, tone.color);
    }

    static Component lore(String text) {
        if (text.isEmpty()) {
            return Component.empty().decoration(TextDecoration.ITALIC, false);
        }
        int colon = text.indexOf(": ");
        if (colon > 0 && colon < 24) {
            String label = text.substring(0, colon + 2);
            String value = text.substring(colon + 2);
            NamedTextColor labelColor = switch (text.substring(0, colon).toLowerCase(java.util.Locale.ROOT)) {
                case "location", "now", "holder", "where", "current" -> NamedTextColor.AQUA;
                case "warning", "conflict" -> NamedTextColor.GOLD;
                case "status", "created", "updated", "last seen", "history" -> NamedTextColor.GREEN;
                default -> NamedTextColor.GRAY;
            };
            return text(label, labelColor).append(text(value, NamedTextColor.WHITE));
        }
        if (text.startsWith("Left-click") || text.startsWith("Click to")) {
            return text(text, NamedTextColor.AQUA);
        }
        if (text.startsWith("Shift-click")) {
            return text(text, NamedTextColor.DARK_GRAY);
        }
        return text(text, NamedTextColor.GRAY);
    }

    private static Component text(String text, NamedTextColor color) {
        return Component.text(Objects.requireNonNull(text, "text"), color)
                .decoration(TextDecoration.ITALIC, false);
    }
}
