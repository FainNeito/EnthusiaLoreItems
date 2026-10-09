package net.enthusia.loreitems.paper;

import static net.enthusia.loreitems.paper.PaperTrackingAdministrationItems.item;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.enthusia.loreitems.application.TemplateManagementSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

final class PaperTemplateEditorRenderer {
    static final int MANAGEMENT_EDIT = 29;
    static final int MANAGEMENT_REPLACE = 31;
    static final int MANAGEMENT_INSTANCES = 33;
    static final int MANAGEMENT_PURGE = 38;
    static final int MANAGEMENT_DELETE = 42;
    static final int MANAGEMENT_BACK = 45;
    static final int MANAGEMENT_REFRESH = 53;
    static final int EDITOR_CANCEL = 45;
    static final int EDITOR_PREVIEW = 53;
    static final int PREVIEW_BACK = 45;
    static final int PREVIEW_CONFIRM = 49;
    static final int PREVIEW_CANCEL = 53;

    private static final int SIZE = 54;
    private static final int MANAGEMENT_PREVIEW = 13;
    private static final int MANAGEMENT_STATUS = 22;
    private static final int EDITOR_DRAFT = 4;
    private static final int EDITOR_HELP = 49;
    private static final int PREVIEW_CURRENT = 11;
    private static final int PREVIEW_STATUS = 13;
    private static final int PREVIEW_DRAFT = 15;
    private static final Map<Integer, ActionSpec> EDITOR_ACTIONS = actions();

    void showManagement(
            Player player,
            TemplateManagementSnapshot snapshot,
            ItemStack preview,
            int returnPage) {
        PaperTemplateEditorView view = PaperTemplateEditorView.management(snapshot, returnPage);
        Inventory inventory = create(view, "Template management");
        populateManagementSummary(inventory, snapshot, preview);
        populateManagementActions(player, inventory);
        player.openInventory(inventory);
    }

    private static void populateManagementSummary(
            Inventory inventory,
            TemplateManagementSnapshot snapshot,
            ItemStack preview) {
        inventory.setItem(MANAGEMENT_PREVIEW, preview.clone());
        inventory.setItem(MANAGEMENT_STATUS, item(
                Material.CLOCK,
                snapshot.definition().displayName(),
                PaperGuiStyle.Tone.METADATA,
                List.of(
                        "Status: Active collection",
                        "Tracked copies: " + snapshot.activeInstanceCount(),
                        "Open issues: " + snapshot.anomalyCount(),
                        "Pending updates: " + snapshot.pendingUpdateCount(),
                        "Updates: " + (snapshot.rolloutActive() ? "In progress" : "Up to date"),
                        "",
                        "Use edit or browse to manage this collection.")));
    }

    private static void populateManagementActions(Player player, Inventory inventory) {
        populateStandardManagementActions(inventory);
        populateDestructiveManagementActions(player, inventory);
        populateManagementNavigation(inventory);
    }

    private static void populateStandardManagementActions(Inventory inventory) {
        inventory.setItem(MANAGEMENT_EDIT, item(
                Material.WRITABLE_BOOK,
                "Edit template",
                PaperGuiStyle.Tone.PRIMARY,
                List.of(
                        "Open a private draft of the current template.",
                        "Nothing is saved until preview and confirmation.")));
        inventory.setItem(MANAGEMENT_REPLACE, item(
                Material.STRUCTURE_VOID,
                "Replace from held item",
                PaperGuiStyle.Tone.PRIMARY,
                List.of(
                        "Copy all supported components from your held item.",
                        "LoreItems identity and stackability are stripped.",
                        "You will review the complete result before saving.")));
        inventory.setItem(MANAGEMENT_INSTANCES, item(
                Material.PLAYER_HEAD,
                "Browse instances",
                PaperGuiStyle.Tone.PRIMARY,
                List.of(
                        "Inspect tracked copies and their lifecycle state.",
                        "Open location evidence and recovery controls.")));
    }

    private static void populateDestructiveManagementActions(
            Player player, Inventory inventory) {
        if (player.hasPermission(LoreItemsDestructiveCommandExecutor.PURGE_PERMISSION)) {
            inventory.setItem(MANAGEMENT_PURGE, item(
                    Material.LAVA_BUCKET,
                    "Purge every instance",
                    PaperGuiStyle.Tone.DESTRUCTIVE,
                    List.of(
                            "Preview physical removal of every tracked copy.",
                            "The definition and template remain active.",
                            "A separate fixed confirmation is required.")));
        }
        if (player.hasPermission(LoreItemsDestructiveCommandExecutor.DELETE_PERMISSION)) {
            inventory.setItem(MANAGEMENT_DELETE, item(
                    Material.TNT,
                    "Delete definition and items",
                    PaperGuiStyle.Tone.DESTRUCTIVE,
                    List.of(
                            "Preview deletion of this definition and all tracked copies.",
                            "Returning copies remain scheduled for removal.",
                            "A separate fixed confirmation is required.")));
        }
    }

    private static void populateManagementNavigation(Inventory inventory) {
        inventory.setItem(MANAGEMENT_BACK, item(
                Material.ARROW,
                "Back to definitions",
                PaperGuiStyle.Tone.NAVIGATION,
                List.of("Return to the definition page you came from.")));
        inventory.setItem(MANAGEMENT_REFRESH, item(
                Material.COMPASS,
                "Refresh status",
                PaperGuiStyle.Tone.INFO,
                List.of("Reload revision, instance, anomaly, and rollout counts.")));
    }

    void showEditor(Player player, PaperTemplateEditorSession session) {
        PaperTemplateEditorView view = PaperTemplateEditorView.editor(
                session.snapshot, session.sessionId, session.returnPage);
        Inventory inventory = create(view, "Template editor");
        inventory.setItem(EDITOR_DRAFT, session.draft.clone());
        EDITOR_ACTIONS.forEach((slot, action) -> inventory.setItem(
                slot,
                item(
                        action.icon(),
                        action.title(),
                        PaperGuiStyle.Tone.PRIMARY,
                        action.help())));
        inventory.setItem(EDITOR_CANCEL, item(
                Material.BARRIER,
                "Cancel draft",
                PaperGuiStyle.Tone.NAVIGATION,
                List.of("Discard every unconfirmed edit and return to management.")));
        inventory.setItem(EDITOR_HELP, item(
                Material.BOOK,
                "Draft workspace",
                PaperGuiStyle.Tone.METADATA,
                List.of(
                        "The item above is your complete current draft.",
                        "Choose a component, then use /loreitems set <value>.",
                        "",
                        "Nothing is durable until preview and confirmation.")));
        inventory.setItem(EDITOR_PREVIEW, item(
                Material.LIME_CONCRETE,
                "Preview and confirm",
                PaperGuiStyle.Tone.POSITIVE,
                List.of("Compare the complete draft with the current template.")));
        player.openInventory(inventory);
    }

    void showPreview(Player player, PaperTemplateEditorSession session) {
        PaperTemplateEditorView view = PaperTemplateEditorView.preview(
                session.snapshot, session.sessionId, session.returnPage);
        Inventory inventory = create(view, "Confirm template revision");
        inventory.setItem(PREVIEW_CURRENT, session.before.clone());
        inventory.setItem(PREVIEW_DRAFT, session.draft.clone());
        inventory.setItem(PREVIEW_STATUS, item(
                Material.BOOK,
                "Revision " + session.snapshot.definition().currentRevision().value()
                        + " → " + session.snapshot.definition().currentRevision().next().value(),
                PaperGuiStyle.Tone.METADATA,
                List.of(
                        "Left: current template",
                        "Right: complete draft",
                        "",
                        "Confirmation creates one immutable revision",
                        "and queues rollout for every active instance.")));
        inventory.setItem(PREVIEW_BACK, item(
                Material.ARROW,
                "Back to editor",
                PaperGuiStyle.Tone.NAVIGATION,
                List.of("Return to the draft without discarding it.")));
        inventory.setItem(PREVIEW_CONFIRM, item(
                Material.LIME_CONCRETE,
                "Confirm revision",
                PaperGuiStyle.Tone.POSITIVE,
                List.of("Persist this revision and its rollout atomically.")));
        inventory.setItem(PREVIEW_CANCEL, item(
                Material.BARRIER,
                "Cancel draft",
                PaperGuiStyle.Tone.NAVIGATION,
                List.of("Discard the draft without changing the template.")));
        player.openInventory(inventory);
    }

    static ActionSpec action(int slot) {
        return EDITOR_ACTIONS.get(slot);
    }

    private static Inventory create(PaperTemplateEditorView view, String title) {
        Inventory inventory = Bukkit.createInventory(view, SIZE, PaperGuiStyle.inventoryTitle(title));
        view.attach(inventory);
        return inventory;
    }

    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private static Map<Integer, ActionSpec> actions() {
        Map<Integer, ActionSpec> actions = new LinkedHashMap<>();

        // Core item identity and presentation: centered seven-wide row.
        add(actions, 10, "material", Material.STONE, "Base material",
                "/loreitems set minecraft:diamond_sword");
        add(actions, 11, "custom-name", Material.NAME_TAG, "Custom name",
                "/loreitems set clear | literal <text> | solid <hex> <text> | gradient <colors> <text>");
        add(actions, 12, "item-name", Material.PAPER, "Item name",
                "/loreitems set clear | literal/solid/gradient ...");
        add(actions, 13, "lore", Material.WRITABLE_BOOK, "Lore lines",
                "/loreitems set add/edit/remove/move/clear ...");
        add(actions, 14, "enchant", Material.ENCHANTED_BOOK, "Enchantments",
                "/loreitems set set/remove/clear/tooltip ...");
        add(actions, 15, "glint", Material.GLOWSTONE_DUST, "Glint override",
                "/loreitems set true | false | unset");
        add(actions, 16, "durability", Material.ANVIL, "Damage and unbreakable",
                "/loreitems set damage <value> | unbreakable true|false");

        // Model, combat and appearance components: second centered row.
        add(actions, 19, "attribute", Material.IRON_CHESTPLATE, "Attributes",
                "/loreitems set set/remove/clear ... stable modifier key required");
        add(actions, 20, "item-model", Material.ITEM_FRAME, "Item model",
                "/loreitems set <namespaced-key> | clear");
        add(actions, 21, "max-stack", Material.BUNDLE, "Maximum stack size",
                "/loreitems set 1 (tracked items are always normalized to one)");
        add(actions, 22, "custom-model-data", Material.COMMAND_BLOCK, "Custom model data",
                "/loreitems set floats/flags/strings/colors ... | clear");
        add(actions, 23, "dye", Material.LEATHER_CHESTPLATE, "Dyed color",
                "/loreitems set #RRGGBB | clear");
        add(actions, 24, "potion", Material.POTION, "Potion components",
                "/loreitems set base/set-effect/remove-effect/clear-effects/color/clear-color ...");
        add(actions, 25, "trim", Material.NETHERITE_CHESTPLATE, "Armor trim",
                "/loreitems set <material-key> <pattern-key> | clear");

        // Specialized components: compact centered five-wide row.
        add(actions, 29, "banner", Material.WHITE_BANNER, "Banner patterns",
                "/loreitems set add/set/remove/clear ...");
        add(actions, 30, "profile", Material.PLAYER_HEAD, "Player profile",
                "/loreitems set <uuid> [name] | clear");
        add(actions, 31, "firework", Material.FIREWORK_ROCKET, "Firework effects",
                "/loreitems set power/add/remove/clear (rocket) or set/clear (star)");
        add(actions, 32, "flags", Material.REDSTONE_TORCH, "Item flags",
                "/loreitems set add <flag> | remove <flag> | clear");
        add(actions, 33, "tooltip", Material.KNOWLEDGE_BOOK, "Tooltip controls",
                "/loreitems set hide true|false | style <key|clear>");
        return Map.copyOf(actions);
    }

    private static void add(
            Map<Integer, ActionSpec> actions,
            int slot,
            String action,
            Material icon,
            String title,
            String help) {
        actions.put(slot, new ActionSpec(action, icon, title, List.of(help)));
    }

    record ActionSpec(String action, Material icon, String title, List<String> help) {
        ActionSpec {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(icon, "icon");
            Objects.requireNonNull(title, "title");
            help = List.copyOf(help);
        }
    }
}
