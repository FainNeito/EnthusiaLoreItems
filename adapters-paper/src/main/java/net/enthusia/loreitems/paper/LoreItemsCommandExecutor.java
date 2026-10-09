package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

@SuppressWarnings({"PMD.AvoidLiteralsInIfCondition"})
public final class LoreItemsCommandExecutor implements CommandExecutor, TabCompleter {
    private static final String CREATE_SUBCOMMAND = "create";
    private static final String ADOPT_SUBCOMMAND = "adopt";
    private static final String GIVE_SUBCOMMAND = "give";
    private static final String COPY_SUBCOMMAND = "copy";
    private static final String RELOAD_SUBCOMMAND = "reload";
    private static final String SET_SUBCOMMAND = "set";
    private static final String ANOMALIES_SUBCOMMAND = "anomalies";
    private static final String AUDIT_SUBCOMMAND = "audit";
    private static final String RECOVERY_SUBCOMMAND = "recovery";
    private static final String BROWSE_SUBCOMMAND = "browse";
    private static final String EDITOR_SUBCOMMAND = "editor";
    private static final String USAGE =
            "Usage: /loreitems create|adopt|give|reload|browse|set <value>|editor cancel|anomalies|audit|recovery|"
                    + "remove|purge|delete|operations|targets|destructive-metrics|"
                    + "pause-operation|resume-operation|resolve-removal ...";

    private final CreateDefinitionCommandExecutor createExecutor;
    private final AdoptHeldItemCommandExecutor adoptExecutor;
    private final GiveLoreItemCommandExecutor giveExecutor;
    private final LoreItemsAdministrationCommandExecutor administrationExecutor;
    private final LoreItemsDestructiveCommandExecutor destructiveExecutor;
    private final FoundationConfigurationReloadCommandExecutor reloadExecutor;
    private PaperCreativeCopyController creativeCopyController;

    public void setCreativeCopyController(PaperCreativeCopyController controller) {
        this.creativeCopyController = Objects.requireNonNull(controller, "controller");
    }

    public LoreItemsCommandExecutor(
            CreateDefinitionCommandExecutor createExecutor,
            AdoptHeldItemCommandExecutor adoptExecutor,
            GiveLoreItemCommandExecutor giveExecutor) {
        this(createExecutor, adoptExecutor, giveExecutor, null, null, null);
    }

    public LoreItemsCommandExecutor(
            CreateDefinitionCommandExecutor createExecutor,
            AdoptHeldItemCommandExecutor adoptExecutor,
            GiveLoreItemCommandExecutor giveExecutor,
            LoreItemsAdministrationCommandExecutor administrationExecutor) {
        this(
                createExecutor,
                adoptExecutor,
                giveExecutor,
                administrationExecutor,
                administrationExecutor == null
                        ? null
                        : administrationExecutor.destructiveCommandExecutor(),
                null);
    }

    public LoreItemsCommandExecutor(
            CreateDefinitionCommandExecutor createExecutor,
            AdoptHeldItemCommandExecutor adoptExecutor,
            GiveLoreItemCommandExecutor giveExecutor,
            LoreItemsAdministrationCommandExecutor administrationExecutor,
            FoundationConfigurationReloadCommandExecutor reloadExecutor) {
        this(
                createExecutor,
                adoptExecutor,
                giveExecutor,
                administrationExecutor,
                administrationExecutor == null
                        ? null
                        : administrationExecutor.destructiveCommandExecutor(),
                reloadExecutor);
    }

    public LoreItemsCommandExecutor(
            CreateDefinitionCommandExecutor createExecutor,
            AdoptHeldItemCommandExecutor adoptExecutor,
            GiveLoreItemCommandExecutor giveExecutor,
            LoreItemsAdministrationCommandExecutor administrationExecutor,
            LoreItemsDestructiveCommandExecutor destructiveExecutor) {
        this(
                createExecutor,
                adoptExecutor,
                giveExecutor,
                administrationExecutor,
                destructiveExecutor,
                null);
    }

    public LoreItemsCommandExecutor(
            CreateDefinitionCommandExecutor createExecutor,
            AdoptHeldItemCommandExecutor adoptExecutor,
            GiveLoreItemCommandExecutor giveExecutor,
            LoreItemsAdministrationCommandExecutor administrationExecutor,
            LoreItemsDestructiveCommandExecutor destructiveExecutor,
            FoundationConfigurationReloadCommandExecutor reloadExecutor) {
        this.createExecutor = Objects.requireNonNull(createExecutor, "createExecutor");
        this.adoptExecutor = Objects.requireNonNull(adoptExecutor, "adoptExecutor");
        this.giveExecutor = Objects.requireNonNull(giveExecutor, "giveExecutor");
        this.administrationExecutor = administrationExecutor;
        this.destructiveExecutor = destructiveExecutor;
        this.reloadExecutor = reloadExecutor;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] arguments) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(arguments, "arguments");
        if (arguments.length == 0) {
            LoreItemsMessages.send(sender, USAGE);
            return true;
        }
        String subcommand = arguments[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case CREATE_SUBCOMMAND -> createExecutor.onCommand(sender, command, label, arguments);
            case ADOPT_SUBCOMMAND -> adoptExecutor.onCommand(sender, command, label, arguments);
            case GIVE_SUBCOMMAND -> giveExecutor.onCommand(sender, command, label, arguments);
            case COPY_SUBCOMMAND -> executeCopy(sender, arguments);
            case RELOAD_SUBCOMMAND -> executeReload(sender, arguments);
            case EDITOR_SUBCOMMAND, SET_SUBCOMMAND -> executeEditor(sender, arguments);
            case BROWSE_SUBCOMMAND, ANOMALIES_SUBCOMMAND, AUDIT_SUBCOMMAND,
                    RECOVERY_SUBCOMMAND ->
                    executeAdministration(sender, command, label, arguments);
            default -> executeDestructiveOrUsage(sender, command, label, arguments, subcommand);
        };
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] arguments) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(arguments, "arguments");
        if (arguments.length == 1) {
            return topLevelCompletions(
                    sender,
                    arguments[0],
                    administrationExecutor != null,
                    destructiveExecutor != null,
                    reloadExecutor != null);
        }
        if (destructiveExecutor != null
                && arguments.length > 1
                && destructiveExecutor.handles(arguments[0])) {
            return destructiveExecutor.onTabComplete(sender, command, alias, arguments);
        }
        return List.of();
    }

    static List<String> topLevelCompletions(CommandSender sender, String input) {
        return topLevelCompletions(sender, input, true, true, true);
    }

    static List<String> topLevelCompletions(
            CommandSender sender, String input, boolean administrationAvailable) {
        return topLevelCompletions(sender, input, administrationAvailable, false, false);
    }

    static List<String> topLevelCompletions(
            CommandSender sender,
            String input,
            boolean administrationAvailable,
            boolean destructiveAvailable) {
        return topLevelCompletions(
                sender, input, administrationAvailable, destructiveAvailable, false);
    }

    static List<String> topLevelCompletions(
            CommandSender sender,
            String input,
            boolean administrationAvailable,
            boolean destructiveAvailable,
            boolean reloadAvailable) {
        Objects.requireNonNull(sender, "sender");
        String prefix = Objects.requireNonNull(input, "input").toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();
        addIfAllowed(candidates, sender, CREATE_SUBCOMMAND, "enthusia.loreitems.admin.create");
        addIfAllowed(candidates, sender, ADOPT_SUBCOMMAND, "enthusia.loreitems.admin.adopt");
        addIfAllowed(candidates, sender, GIVE_SUBCOMMAND, "enthusia.loreitems.admin.give");
        addIfAllowed(candidates, sender, COPY_SUBCOMMAND, "enthusia.loreitems.admin.give");
        if (administrationAvailable) {
            addIfAllowed(candidates, sender, SET_SUBCOMMAND, PaperTemplateEditorManager.EDIT_PERMISSION);
            addIfAllowed(candidates, sender, EDITOR_SUBCOMMAND, PaperTemplateEditorManager.EDIT_PERMISSION);
        }
        if (reloadAvailable) {
            addIfAllowed(
                    candidates,
                    sender,
                    RELOAD_SUBCOMMAND,
                    FoundationConfigurationReloadCommandExecutor.RELOAD_PERMISSION);
        }
        if (administrationAvailable) {
            if (LoreItemsAdministrationCommandExecutor.canBrowse(sender)) {
                candidates.add(BROWSE_SUBCOMMAND);
            }
            addIfAllowed(
                    candidates,
                    sender,
                    ANOMALIES_SUBCOMMAND,
                    LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION);
            addIfAllowed(
                    candidates,
                    sender,
                    AUDIT_SUBCOMMAND,
                    LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION);
            addIfAllowed(
                    candidates,
                    sender,
                    RECOVERY_SUBCOMMAND,
                    LoreItemsAdministrationCommandExecutor.AUDIT_PERMISSION);
        }
        if (destructiveAvailable) {
            candidates.addAll(LoreItemsDestructiveCommandExecutor.topLevelCompletions(sender, ""));
        }
        return candidates.stream().filter(value -> value.startsWith(prefix)).toList();
    }

    private static void addIfAllowed(
            List<String> values,
            CommandSender sender,
            String value,
            String permission) {
        if (sender.hasPermission(permission)) {
            values.add(value);
        }
    }

    private boolean executeCopy(CommandSender sender, String[] arguments) {
        if (creativeCopyController == null) {
            sender.sendMessage("Creative item copies are unavailable.");
            return true;
        }
        return creativeCopyController.execute(sender, arguments);
    }

    private boolean executeReload(CommandSender sender, String[] arguments) {
        if (reloadExecutor == null || arguments.length != 1) {
            LoreItemsMessages.send(sender, "Usage: /loreitems reload");
            return true;
        }
        return reloadExecutor.execute(sender);
    }

    private boolean executeEditor(CommandSender sender, String[] arguments) {
        if (administrationExecutor == null) {
            LoreItemsMessages.send(sender, USAGE);
            return true;
        }
        return administrationExecutor.executeEditorCommand(sender, arguments);
    }

    private boolean executeAdministration(
            CommandSender sender,
            Command command,
            String label,
            String[] arguments) {
        if (administrationExecutor == null) {
            LoreItemsMessages.send(sender, USAGE);
            return true;
        }
        return administrationExecutor.onCommand(sender, command, label, arguments);
    }

    private boolean executeDestructiveOrUsage(
            CommandSender sender,
            Command command,
            String label,
            String[] arguments,
            String subcommand) {
        if (destructiveExecutor != null && destructiveExecutor.handles(subcommand)) {
            return destructiveExecutor.onCommand(sender, command, label, arguments);
        }
        LoreItemsMessages.send(sender, USAGE);
        return true;
    }
}
