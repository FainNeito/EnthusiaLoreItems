package net.enthusia.loreitems.paper;

import java.util.Arrays;
import java.util.Objects;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Explicit private command input for the GUI editor (never public chat). */
final class LoreItemsEditorCommandSupport {
    private static final String SET_ACTION = "set";
    private static final String EDITOR_ACTION = "editor";
    private static final String CANCEL_ACTION = "cancel";
    private static final int SET_VALUE_START = 1;
    private static final int EDITOR_VALUE_START = 2;
    private static final int FIRST_ARGUMENT_INDEX = 0;

    private LoreItemsEditorCommandSupport() {}

    static boolean execute(
            CommandSender sender,
            String[] arguments,
            PaperTemplateEditorManager templateEditor) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(templateEditor, "templateEditor");
        if (!sender.hasPermission(PaperTemplateEditorManager.EDIT_PERMISSION)) {
            LoreItemsMessages.send(sender, "You do not have permission to edit lore-item templates.");
            return true;
        }
        if (!(sender instanceof Player player)) {
            LoreItemsMessages.send(sender, "The template editor requires an in-game player.");
            return true;
        }
        if (isCancel(arguments)) {
            templateEditor.cancelOwnDraft(player);
            return true;
        }
        int start = valueStart(arguments);
        if (start < 0 || arguments.length <= start) {
            LoreItemsMessages.send(sender,
                    "Usage: /loreitems set <value> | /loreitems editor set <value> | /loreitems editor cancel");
            return true;
        }
        if (templateEditor.chatSessionId(player.getUniqueId()) == null) {
            LoreItemsMessages.send(player,
                    "Select an editor field in /loreitems browse before using /loreitems set.");
            return true;
        }
        templateEditor.receiveChat(player.getUniqueId(),
                String.join(" ", Arrays.copyOfRange(arguments, start, arguments.length)));
        return true;
    }

    private static int valueStart(String[] args) {
        if (args.length >= SET_VALUE_START
                && SET_ACTION.equalsIgnoreCase(args[FIRST_ARGUMENT_INDEX])) {
            return SET_VALUE_START;
        }
        if (args.length >= EDITOR_VALUE_START
                && EDITOR_ACTION.equalsIgnoreCase(args[FIRST_ARGUMENT_INDEX])
                && SET_ACTION.equalsIgnoreCase(args[SET_VALUE_START])) {
            return EDITOR_VALUE_START;
        }
        return -1;
    }

    static boolean isCancel(String[] arguments) {
        return arguments.length == EDITOR_VALUE_START
                && EDITOR_ACTION.equalsIgnoreCase(arguments[FIRST_ARGUMENT_INDEX])
                && CANCEL_ACTION.equalsIgnoreCase(arguments[SET_VALUE_START]);
    }
}
