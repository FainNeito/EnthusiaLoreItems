package net.enthusia.loreitems.paper;

import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.decorate;
import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.decorateNested;

import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.evidenceItem;
import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.item;

import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.formatTime;


import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.domain.InstanceAnomaly;
import net.enthusia.loreitems.domain.InstanceCurrentState;
import net.enthusia.loreitems.domain.InstanceObservation;
import net.enthusia.loreitems.domain.LoreDefinition;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstance;
import net.enthusia.loreitems.domain.LoreInstanceId;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

/** Renders immutable tracking administration pages on the Paper thread. */
final class PaperTrackingAdministrationRenderer {
    private static final int SIZE = 54;
    private static final int CONTENT = 45;
    private static final int CONFIRMATION_SIZE = 27;
    private static final int CANCEL = 11;
    private static final int CONTEXT = 13;
    private static final int CONFIRM = 15;

    private final Plugin plugin;
    private final PaperFriendlyLocation places;

    PaperTrackingAdministrationRenderer(Plugin plugin) {
        this.plugin = java.util.Objects.requireNonNull(plugin, "plugin");
        this.places = new PaperFriendlyLocation(plugin);
        new PaperDestructiveAdministrationGuiBridge(plugin);
    }

    void showDefinitions(Player player, int pageNumber, Page<LoreDefinition> page) {
        List<LoreDefinitionId> ids = page.items().stream().map(LoreDefinition::id).toList();
        PaperTrackingAdministrationView view =
                PaperTrackingAdministrationView.definitions(pageNumber, page.hasMore(), ids);
        Inventory inventory = createInventory(view, "Lore definitions");
        for (int index = 0; index < page.items().size() && index < CONTENT; index++) {
            LoreDefinition definition = page.items().get(index);
            inventory.setItem(
                    index,
                    item(
                            Material.BOOK,
                            definition.displayName(),
                            PaperGuiStyle.Tone.PRIMARY,
                            List.of(
                                    "Status: " + (definition.active() ? "Active collection" : "Unavailable"),
                                    "",
                                    "Click to edit or browse tracked copies.",
                                    "Technical details are available in management.")));
        }
        if (ids.isEmpty()) {
            inventory.setItem(
                    22,
                    item(
                            Material.PAPER,
                            "No definitions on this page",
                            PaperGuiStyle.Tone.INFO,
                            List.of("Use the page controls below to continue browsing.")));
        }
        decorate(inventory, pageNumber, page.hasMore(),
                List.of("Browse collections and their tracked copies.",
                        "Shift-click this clock for diagnostics."));
        player.openInventory(inventory);
    }

    void showInstances(
            Player player,
            LoreDefinitionId definitionId,
            int definitionPageNumber,
            int pageNumber,
            Page<LoreInstance> page) {
        List<LoreInstanceId> ids = page.items().stream().map(LoreInstance::id).toList();
        PaperTrackingAdministrationView view = PaperTrackingAdministrationView.instances(
                definitionId, definitionPageNumber, pageNumber, page.hasMore(), ids);
        Inventory inventory = createInventory(view, "Lore instances");
        boolean canRemove =
                player.hasPermission(LoreItemsDestructiveCommandExecutor.REMOVE_PERMISSION);
        for (int index = 0; index < page.items().size() && index < CONTENT; index++) {
            LoreInstance instance = page.items().get(index);
            inventory.setItem(
                    index,
                    item(
                            Material.NETHER_STAR,
                            "Tracked copy #" + ((pageNumber - 1) * 45 + index + 1),
                            PaperGuiStyle.Tone.PRIMARY,
                            instanceLore(instance, canRemove)));
        }
        if (ids.isEmpty()) {
            inventory.setItem(
                    22,
                    item(
                            Material.PAPER,
                            "No tracked instances",
                            PaperGuiStyle.Tone.INFO,
                            List.of("This definition has no instances on this page.")));
        }
        decorateNested(
                inventory,
                pageNumber,
                page.hasMore(),
                "Back to template",
                List.of("Return to template management."),
                List.of("Left-click a copy to see where it is.",
                        "History records moves between places.",
                        "Shift-click a copy for its tracking ID."));
        player.openInventory(inventory);
    }

    void showEvidence(
            Player player,
            LoreDefinitionId definitionId,
            int definitionPageNumber,
            int instancePageNumber,
            LoreInstanceId instanceId,
            int pageNumber,
            EvidenceData data) {
        DuplicateChoice duplicate = activeDuplicate(data.anomalies());
        List<ObservationChoice> choices = observationChoices(data.observations());
        PaperTrackingAdministrationView view = PaperTrackingAdministrationView.evidence(
                definitionId,
                definitionPageNumber,
                instancePageNumber,
                instanceId,
                pageNumber,
                data.observations().hasMore(),
                choices,
                duplicate);
        Inventory inventory = createInventory(view, "Lore location evidence");
        populateEvidence(inventory, choices, duplicate);
        inventory.setItem(PaperTrackingAdministrationItems.CURRENT_LOCATION,
                currentLocationItem(data.current(), duplicate));
        if (choices.isEmpty()) {
            inventory.setItem(
                    22,
                    item(
                            Material.MAP,
                            "No location observations",
                            PaperGuiStyle.Tone.INFO,
                            List.of("No location evidence is available on this page.")));
        }
        decorateNested(
                inventory,
                pageNumber,
                data.observations().hasMore(),
                "Back to instances",
                List.of("Return to instance page " + instancePageNumber + '.'),
                evidenceStatus(data.current(), duplicate));
        player.openInventory(inventory);
    }

    Inventory confirmationInventory(
            LoreDefinitionId definitionId,
            int definitionPageNumber,
            int instancePageNumber,
            LoreInstanceId instanceId,
            DuplicateChoice duplicate,
            ObservationChoice observation,
            int returnPage) {
        PaperTrackingAdministrationView view = PaperTrackingAdministrationView.confirmation(
                definitionId,
                definitionPageNumber,
                instancePageNumber,
                instanceId,
                duplicate,
                observation,
                returnPage);
        Inventory inventory = createInventory(view, "Confirm lore location", CONFIRMATION_SIZE);
        inventory.setItem(
                CANCEL,
                item(
                        Material.BARRIER,
                        "Cancel",
                        PaperGuiStyle.Tone.NAVIGATION,
                        List.of("Return to the evidence page without changing anything.")));
        inventory.setItem(
                CONTEXT,
                item(
                        Material.MAP,
                        "Selected location",
                        PaperGuiStyle.Tone.METADATA,
                        List.of(
                                "Location: " + places.describe(observation.location()),
                                "Status: Review potential duplicate",
                                "",
                                "No physical copy will be deleted.")));
        inventory.setItem(
                CONFIRM,
                item(
                        Material.LIME_CONCRETE,
                        "Confirm this location",
                        PaperGuiStyle.Tone.POSITIVE,
                        List.of(
                                "Use this observation to resolve the active conflict.",
                                "A later scan can reopen it if copies conflict again.")));
        return inventory;
    }

    private static List<String> instanceLore(LoreInstance instance, boolean canRemove) {
        List<String> lore = new ArrayList<>(List.of(
                "Status: " + switch (instance.lifecycle()) {
                    case ACTIVE -> "Tracked and active";
                    case VOID_DESTROYED -> "Destroyed in void";
                    case REMOVED -> "Removed";
                },
                "Created: " + formatTime(instance.createdAtEpochMillis()),
                "",
                "Left-click to see current location and history."));
        if (canRemove) {
            lore.add("Use removal tools for safe deletion.");
        }
        lore.add("Shift-click for technical details.");
        return lore;
    }

    private DuplicateChoice activeDuplicate(Page<InstanceAnomaly> anomalies) {
        return anomalies.items().stream()
                .filter(anomaly -> anomaly.type() == InstanceAnomaly.Type.DUPLICATE_INSTANCE)
                .filter(anomaly -> anomaly.status() == InstanceAnomaly.Status.OPEN
                        || anomaly.status() == InstanceAnomaly.Status.ACKNOWLEDGED)
                .findFirst()
                .map(anomaly -> new DuplicateChoice(
                        anomaly.anomalyId(),
                        anomaly.stateRevision(),
                        anomaly.firstSeenAtEpochMillis()))
                .orElse(null);
    }

    private List<ObservationChoice> observationChoices(Page<InstanceObservation> observations) {
        // Existing databases may contain thousands of historical slot moves.
        // Hide consecutive observations inside one inventory; retain transfers
        // between holders and all conflicting evidence for staff review.
        List<ObservationChoice> visible = new ArrayList<>();
        for (InstanceObservation observation : observations.items()) {
            ObservationChoice entry = toChoice(observation);
            if (!visible.isEmpty() && isRepeatedPlace(visible.getLast(), entry)) {
                continue;
            }
            visible.add(entry);
        }
        return List.copyOf(visible);
    }

    private static ObservationChoice toChoice(InstanceObservation observation) {
        return new ObservationChoice(
                observation.observationId(),
                observation.location(),
                observation.confidence(),
                observation.source(),
                observation.observedAtEpochMillis());
    }

    private static boolean isRepeatedPlace(ObservationChoice previous, ObservationChoice current) {
        if (previous.confidence() != current.confidence()
                || previous.confidence() == InstanceObservation.Confidence.CONFLICTING
                || previous.location().type() != current.location().type()
                || !previous.location().locationKey().equals(current.location().locationKey())) {
            return false;
        }
        return switch (current.location().type()) {
            case PLAYER_INVENTORY, PLAYER_ENDER_CHEST, BLOCK_CONTAINER -> true;
            default -> false;
        };
    }

    private void populateEvidence(
            Inventory inventory,
            List<ObservationChoice> choices,
            DuplicateChoice duplicate) {
        for (int index = 0; index < choices.size() && index < CONTENT; index++) {
            inventory.setItem(index, evidenceItem(places, choices.get(index), duplicate));
        }
    }

    private List<String> evidenceStatus(
            Optional<InstanceCurrentState> current, DuplicateChoice duplicate) {
        return List.of(
                "Now: " + current.map(this::currentSummary).orElse("Location not known"),
                duplicate == null ? "Status: No known duplicate conflict"
                        : "Warning: Duplicate locations need review",
                "Shift-click history entries for raw details.");
    }

    private String currentSummary(InstanceCurrentState state) {
        if (state.state() == InstanceCurrentState.State.MISSING_UNRESOLVED) {
            return "Possibly deleted - location unresolved";
        }
        if (state.location() == null) {
            return "Not currently located";
        }
        return places.describe(state.location());
    }

    private org.bukkit.inventory.ItemStack currentLocationItem(
            Optional<InstanceCurrentState> current, DuplicateChoice duplicate) {
        List<String> lore = new ArrayList<>();
        lore.add("Now: " + current.map(this::currentSummary).orElse("Unknown"));
        if (current.isPresent()) {
            InstanceCurrentState state = current.orElseThrow();
            if (state.state() == InstanceCurrentState.State.MISSING_UNRESOLVED) {
                lore.add("Warning: Item may have been deleted in Creative");
                lore.add("History is preserved in case it is found again.");
            } else if (state.state() == InstanceCurrentState.State.LAST_CONFIRMED) {
                lore.add("Status: Last seen here (not verified live)");
            } else if (state.state() == InstanceCurrentState.State.CONFLICTING) {
                lore.add("Warning: More than one location reported");
            } else if (state.state() == InstanceCurrentState.State.CONFIRMED_NOW) {
                lore.add("Status: Location confirmed");
            }
            lore.add("Updated: " + formatTime(state.updatedAtEpochMillis()));
        }
        if (duplicate != null) {
            lore.add("Warning: Duplicate review needed");
        }
        lore.add("");
        lore.add("Click to refresh the current location.");
        return item(Material.RECOVERY_COMPASS, "CURRENT LOCATION",
                duplicate != null ? PaperGuiStyle.Tone.WARNING : PaperGuiStyle.Tone.POSITIVE, lore);
    }

    private static Inventory createInventory(
            PaperTrackingAdministrationView view, String title) {
        return createInventory(view, title, SIZE);
    }

    private static Inventory createInventory(
            PaperTrackingAdministrationView view, String title, int size) {
        Inventory inventory = Bukkit.createInventory(view, size, PaperGuiStyle.inventoryTitle(title));
        view.attach(inventory);
        return inventory;
    }
}
