package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.enthusia.loreitems.application.CampaignRecipientCounts;
import net.enthusia.loreitems.application.DistributionCampaignStatus;
import net.enthusia.loreitems.application.Page;
import net.enthusia.loreitems.application.PageRequest;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import net.enthusia.loreitems.domain.DistributionCampaign;
import org.bukkit.command.CommandSender;

final class DistributionCampaignCommandSupport {
    static final int FIRST_PAGE = 1;
    private static final String LABEL_SEPARATOR = " — ";

    private DistributionCampaignCommandSupport() {
    }

    static PageRequest request(int pageNumber, int pageSize) {
        int offset = Math.multiplyExact(pageNumber - FIRST_PAGE, pageSize);
        return new PageRequest(offset, pageSize);
    }

    static int parsePage(String[] args, int index) {
        return args.length > index ? parsePositive(args[index], "page") : FIRST_PAGE;
    }

    static int parsePositive(String value, String name) {
        int parsed = Integer.parseInt(value);
        if (parsed < FIRST_PAGE) {
            throw new IllegalArgumentException(name + " must be at least 1");
        }
        return parsed;
    }

    static CampaignRecipientState parseState(String value) {
        if ("all".equalsIgnoreCase(value)) {
            return null;
        }
        return CampaignRecipientState.valueOf(value.toUpperCase(Locale.ROOT));
    }

    static void requireLength(String[] args, int length, String usage) {
        if (args.length != length) {
            throw new IllegalArgumentException("Usage: /loredistribution " + usage);
        }
    }

    static void requireMinimumLength(String[] args, int length, String usage) {
        if (args.length < length) {
            throw new IllegalArgumentException("Usage: /loredistribution " + usage);
        }
    }

    static void showPreview(CommandSender sender, DistributionCampaignPreview preview) {
        LoreItemsMessages.send(sender, "Distribution preview — no delivery has started yet.");
        LoreItemsMessages.send(sender, "Campaign: " + preview.campaignId());
        LoreItemsMessages.send(sender, "Source: " + preview.groupFile().sourceName()
                + " (" + preview.groupFile().displayName() + ")");
        LoreItemsMessages.send(sender, "Recipients: " + preview.startRequest().recipients().size());
        LoreItemsMessages.send(sender, "Definition: " + preview.definition().key().value()
                + " revision " + preview.definition().currentRevision().value());
        LoreItemsMessages.send(sender, "Confirm explicitly with /loredistribution confirm " + preview.campaignId());
    }

    static void showCatalogPage(
            CommandSender sender,
            GroupFileCatalogSnapshot snapshot,
            int pageNumber,
            int pageSize) {
        List<String> lines = new ArrayList<>();
        for (GroupFileDefinition valid : snapshot.validFiles()) {
            lines.add("VALID " + valid.sourceName() + LABEL_SEPARATOR + valid.displayName()
                    + " (" + valid.recipients().size() + " recipients)");
        }
        for (GroupFileValidationFailure invalid : snapshot.invalidFiles()) {
            lines.add("INVALID " + invalid.sourceName() + LABEL_SEPARATOR
                    + String.join("; ", invalid.diagnostics()));
        }
        showStringPage(sender, "Group catalog", lines, pageNumber, pageSize);
    }

    static void showStatus(CommandSender sender, DistributionCampaignStatus status) {
        DistributionCampaign campaign = status.campaign();
        CampaignRecipientCounts counts = status.recipientCounts();
        LoreItemsMessages.send(sender, "Campaign " + campaign.campaignId() + LABEL_SEPARATOR + campaign.state());
        LoreItemsMessages.send(sender, "Source: " + campaign.sourceName() + LABEL_SEPARATOR + campaign.displayName());
        LoreItemsMessages.send(sender, "Definition: " + campaign.definitionId().value()
                + " revision " + campaign.definitionRevision().value());
        LoreItemsMessages.send(sender, "total=" + counts.total() + " remaining=" + counts.remaining()
                + " unresolved=" + counts.unresolved()
                + " offline=" + counts.queuedOffline()
                + " full=" + counts.queuedInventoryFull()
                + " reserved=" + counts.reservedInFlight()
                + " review=" + counts.reviewRequired()
                + " delivered=" + counts.delivered()
                + " cancelled=" + counts.cancelled());
    }

    static void showRecipients(
            CommandSender sender,
            Page<CampaignRecipient> page,
            CampaignRecipientState state,
            int pageNumber) {
        LoreItemsMessages.send(sender, "Campaign recipients "
                + (state == null ? "ALL" : state.name()) + " — page " + pageNumber);
        for (CampaignRecipient recipient : page.items()) {
            LoreItemsMessages.send(sender, "#" + recipient.snapshotIndex() + " " + recipient.originalValue()
                    + " -> " + recipient.state()
                    + " player=" + value(recipient.playerId())
                    + " instance=" + value(recipient.instanceId()));
        }
        sendPageFooter(sender, page.hasMore());
    }

    static void sendPageFooter(CommandSender sender, boolean hasMore) {
        LoreItemsMessages.send(sender, hasMore ? "More results available." : "End of results.");
    }

    static void sendUsage(CommandSender sender, String label) {
        LoreItemsMessages.send(sender, "/" + label + " reload [page] | inspect <group.yml> | "
                + "preview <group.yml> <definition-key> | confirm <campaign-uuid> | "
                + "campaigns [page] | status <campaign-uuid> | "
                + "recipients <campaign-uuid> [state|all] [page] | pause|resume|cancel <uuid> | "
                + "reconcile [page]");
    }

    static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof java.util.concurrent.CompletionException exception
                && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }

    static String safeMessage(Throwable throwable) {
        if (throwable == null) {
            return "unknown failure";
        }
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private static void showStringPage(
            CommandSender sender,
            String title,
            List<String> lines,
            int pageNumber,
            int pageSize) {
        int from = Math.multiplyExact(pageNumber - FIRST_PAGE, pageSize);
        if (from >= lines.size()) {
            LoreItemsMessages.send(sender, title + " — page " + pageNumber + " is empty.");
            return;
        }
        int to = Math.min(lines.size(), Math.addExact(from, pageSize));
        LoreItemsMessages.send(sender, title + " — page " + pageNumber);
        for (String line : lines.subList(from, to)) {
            LoreItemsMessages.send(sender, line);
        }
        LoreItemsMessages.send(sender, to < lines.size() ? "More results available." : "End of results.");
    }

    private static Object value(Object value) {
        return value == null ? "-" : value;
    }
}
