package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.enthusia.loreitems.application.AnomalyWarningSink;
import net.enthusia.loreitems.application.TrackingMetrics;
import net.enthusia.loreitems.application.TrackingMetricsSource;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LocationDescriptor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

/** Shared rendering primitives for the tracking administration inventories. */
final class PaperTrackingAdministrationItems {
    static final int BACK = 45;
    static final int CURRENT_LOCATION = 46;
    static final int PREVIOUS = 48;
    static final int STATUS = 49;
    static final int NEXT = 50;
    private static final int FIRST_PAGE = 1;

    private PaperTrackingAdministrationItems() {}

    static void decorate(
            Inventory inventory,
            int pageNumber,
            boolean hasMore,
            List<String> statusLore) {
        if (pageNumber > FIRST_PAGE) {
            inventory.setItem(
                    PREVIOUS,
                    item(
                            Material.ARROW,
                            "Previous page",
                            PaperGuiStyle.Tone.NAVIGATION,
                            List.of("Go to page " + (pageNumber - FIRST_PAGE) + '.')));
        }
        if (hasMore) {
            inventory.setItem(
                    NEXT,
                    item(
                            Material.ARROW,
                            "Next page",
                            PaperGuiStyle.Tone.NAVIGATION,
                            List.of("Go to page " + (pageNumber + FIRST_PAGE) + '.')));
        }
        inventory.setItem(
                STATUS,
                item(
                        Material.CLOCK,
                        "Location & tracking help",
                        PaperGuiStyle.Tone.PRIMARY,
                        statusLore));
        for (int slot = BACK; slot < inventory.getSize() && slot < 54; slot++) {
            if (inventory.getItem(slot) == null) {
                inventory.setItem(slot, item(
                        slot % 2 == 0 ? Material.CYAN_STAINED_GLASS_PANE
                                : Material.PURPLE_STAINED_GLASS_PANE,
                        " ", PaperGuiStyle.Tone.NAVIGATION, List.of()));
            }
        }
    }

    static void decorateNested(
            Inventory inventory,
            int pageNumber,
            boolean hasMore,
            String backLabel,
            List<String> backLore,
            List<String> statusLore) {
        inventory.setItem(
                BACK,
                item(
                        Material.ARROW,
                        backLabel,
                        PaperGuiStyle.Tone.NAVIGATION,
                        backLore));
        decorate(inventory, pageNumber, hasMore, statusLore);
    }

    static ItemStack evidenceItem(
            PaperFriendlyLocation location, ObservationChoice choice, DuplicateChoice duplicate) {
        String place = location.describe(choice.location());
        boolean conflicting = choice.confidence() == InstanceObservation.Confidence.CONFLICTING;
        List<String> lore = new ArrayList<>();
        lore.add("Location: " + place);
        lore.add("When: " + formatTime(choice.observedAt()));
        if (conflicting) {
            lore.add("Warning: Another copy may exist");
        } else if (choice.confidence() == InstanceObservation.Confidence.LAST_CONFIRMED) {
            lore.add("Status: Last known location");
        }
        if (selectable(choice, duplicate)) {
            lore.add("");
            lore.add("Click to review this location.");
        }
        lore.add("Shift-click for technical details.");
        Material material = conflicting ? Material.REDSTONE : Material.COMPASS;
        PaperGuiStyle.Tone tone = conflicting
                ? PaperGuiStyle.Tone.WARNING : PaperGuiStyle.Tone.PRIMARY;
        return item(material, compact("Visited: " + place), tone, lore);
    }

    static String formatTime(long epochMillis) {
        return java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(epochMillis));
    }

    private static String compact(String text) {
        return text.length() <= 52 ? text : text.substring(0, 49) + "...";
    }

    static List<String> trackingMetricsLore(Plugin plugin) {
        AnomalyWarningSink sink = plugin.getServer().getServicesManager()
                .load(AnomalyWarningSink.class);
        if (!(sink instanceof TrackingMetricsSource source)) {
            return List.of("Tracking metrics unavailable.");
        }
        TrackingMetrics.Snapshot snapshot = source.trackingMetrics();
        return List.of(
                "Persistence queued: " + snapshot.queued(),
                "Persistence in flight: " + snapshot.inFlight(),
                "Scan backlog: " + snapshot.scanBacklog(),
                "Truncated bounded scans: " + snapshot.scanTruncated(),
                "Accepted/completed: " + snapshot.accepted() + '/' + snapshot.completed(),
                "Rejected/failed/conflicts: " + snapshot.rejected() + '/'
                        + snapshot.failed() + '/' + snapshot.conflicts());
    }

    static ItemStack item(Material material, String name, List<String> lore) {
        return item(material, name, PaperGuiStyle.Tone.INFO, lore);
    }

    static ItemStack item(
            Material material,
            String name,
            PaperGuiStyle.Tone tone,
            List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(PaperGuiStyle.name(name, tone));
        meta.lore(lore.stream().map(PaperGuiStyle::lore).toList());
        if (!item.setItemMeta(meta)) {
            throw new IllegalStateException("Could not apply lore-item administration metadata");
        }
        return item;
    }

    static boolean selectable(ObservationChoice observation, DuplicateChoice duplicate) {
        return duplicate != null
                && observation.observedAt() >= duplicate.firstSeenAt()
                && observation.confidence() == InstanceObservation.Confidence.CONFLICTING
                && switch (observation.location().type()) {
                    case PLAYER_INVENTORY,
                            PLAYER_ENDER_CHEST,
                            BLOCK_CONTAINER,
                            DROPPED_ITEM,
                            ITEM_FRAME,
                            ARMOR_STAND,
                            NESTED_CONTAINER -> true;
                    default -> false;
                };
    }

    /** Detailed raw evidence is for staff troubleshooting only, never default lore. */
    static String describe(LocationDescriptor location) {
        return humanize(location.type().name()) + ": " + location.locationKey();
    }

    static String shortId(java.util.UUID id) {
        return id.toString().substring(0, 8);
    }

    private static String humanize(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1);
    }
}
