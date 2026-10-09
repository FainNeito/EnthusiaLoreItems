package net.enthusia.loreitems.paper;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import net.enthusia.loreitems.application.DistributionDeliveryExecutionUseCase;
import net.enthusia.loreitems.application.PreparedDistributionDelivery;
import net.enthusia.loreitems.domain.CampaignRecipient;
import net.enthusia.loreitems.domain.CampaignRecipientState;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

final class PaperDistributionDeliveryOutcomeHandler {
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    private final Plugin plugin;
    private final DistributionDeliveryExecutionUseCase useCase;
    private final BooleanSupplier closed;

    PaperDistributionDeliveryOutcomeHandler(
            Plugin plugin,
            DistributionDeliveryExecutionUseCase useCase,
            BooleanSupplier closed) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.useCase = Objects.requireNonNull(useCase, "useCase");
        this.closed = Objects.requireNonNull(closed, "closed");
    }

    void handleApplyResult(
            PreparedDistributionDelivery delivery,
            PaperDirectDeliveryOperator.ApplyResult result) {
        switch (result.status()) {
            case APPLIED -> complete(delivery, result);
            case NO_SPACE -> defer(delivery, CampaignRecipientState.QUEUED_INVENTORY_FULL);
            case REVIEW_REQUIRED -> requireReview(delivery, result.detail(), null);
            default -> requireReview(
                    delivery,
                    "The campaign delivery operator returned an unsupported result state.",
                    null);
        }
    }

    void defer(
            CampaignRecipient recipient,
            CampaignRecipientState target) {
        try {
            useCase.defer(recipient, target, RETRY_DELAY)
                    .whenComplete((deferred, throwable) -> {
                        if (throwable != null || !Boolean.TRUE.equals(deferred)) {
                            requireReview(
                                    recipient,
                                    "The campaign recipient could not be safely deferred.",
                                    throwable);
                        }
                    });
        } catch (RuntimeException exception) {
            requireReview(
                    recipient,
                    "The campaign recipient deferral could not be submitted.",
                    exception);
        }
    }

    void defer(
            PreparedDistributionDelivery delivery,
            CampaignRecipientState target) {
        try {
            useCase.defer(delivery, target, RETRY_DELAY)
                    .whenComplete((deferred, throwable) -> {
                        if (throwable != null || !Boolean.TRUE.equals(deferred)) {
                            requireReview(
                                    delivery,
                                    "The unused prepared campaign instance could not be safely deferred.",
                                    throwable);
                        }
                    });
        } catch (RuntimeException exception) {
            requireReview(
                    delivery,
                    "The prepared campaign deferral could not be submitted.",
                    exception);
        }
    }

    void cancel(CampaignRecipient recipient) {
        try {
            useCase.cancel(recipient).whenComplete((cancelled, throwable) -> {
                if (throwable != null || !Boolean.TRUE.equals(cancelled)) {
                    logUnsafeCancellation(
                            recipient.campaignId(),
                            recipient.recipientKey().value(),
                            throwable);
                }
            });
        } catch (RuntimeException exception) {
            logUnsafeCancellation(
                    recipient.campaignId(),
                    recipient.recipientKey().value(),
                    exception);
        }
    }

    void cancel(PreparedDistributionDelivery delivery) {
        try {
            useCase.cancel(delivery).whenComplete((cancelled, throwable) -> {
                if (throwable != null || !Boolean.TRUE.equals(cancelled)) {
                    requireReview(
                            delivery,
                            "The cancelled campaign's unused prepared delivery "
                                    + "could not be safely discarded.",
                            throwable);
                }
            });
        } catch (RuntimeException exception) {
            requireReview(
                    delivery,
                    "The cancelled campaign's prepared delivery cancellation "
                            + "could not be submitted.",
                    exception);
        }
    }

    void requireReview(
            CampaignRecipient recipient,
            String reason,
            Throwable precedingFailure) {
        if (precedingFailure != null) {
            logFailure(
                    "Campaign recipient entered review after an operational failure.",
                    precedingFailure);
        }
        try {
            useCase.requireReview(recipient, reason).whenComplete((reviewed, throwable) -> {
                if (throwable != null) {
                    logFailure(
                            "Could not persist campaign-recipient review state.",
                            throwable);
                } else if (!Boolean.TRUE.equals(reviewed)) {
                    plugin.getLogger().severe(
                            "Campaign recipient could not reach a safe durable state: "
                                    + recipient.campaignId() + '/'
                                    + recipient.recipientKey().value());
                }
            });
        } catch (RuntimeException exception) {
            logFailure(
                    "Could not submit campaign-recipient review persistence.",
                    exception);
        }
    }

    void requireReview(
            PreparedDistributionDelivery delivery,
            String reason,
            Throwable precedingFailure) {
        if (precedingFailure != null) {
            logFailure(
                    "Prepared campaign delivery entered review after an operational failure.",
                    precedingFailure);
        }
        try {
            useCase.requireReview(delivery, reason).whenComplete((reviewed, throwable) -> {
                if (throwable != null) {
                    logFailure(
                            "Could not persist prepared campaign-delivery review state.",
                            throwable);
                } else if (!Boolean.TRUE.equals(reviewed)) {
                    plugin.getLogger().severe(
                            "Prepared campaign delivery could not reach a safe durable state: "
                                    + delivery.campaignId() + '/'
                                    + delivery.recipientKey().value());
                }
            });
        } catch (RuntimeException exception) {
            logFailure(
                    "Could not submit prepared campaign-delivery review persistence.",
                    exception);
        }
    }

    void logFailure(String message, Throwable throwable) {
        plugin.getLogger().log(Level.SEVERE, message, unwrap(throwable));
    }

    private void complete(
            PreparedDistributionDelivery delivery,
            PaperDirectDeliveryOperator.ApplyResult result) {
        try {
            useCase.complete(
                            delivery,
                            Objects.requireNonNull(result.inventorySlot(), "inventorySlot"),
                            Objects.requireNonNull(result.afterFingerprint(), "afterFingerprint"))
                    .whenComplete((completed, throwable) -> {
                        if (throwable != null || !Boolean.TRUE.equals(completed)) {
                            requireReview(
                                    delivery,
                                    "The item was inserted but campaign completion "
                                            + "could not be persisted.",
                                    throwable);
                        } else {
                            notifyPlayer(
                                    delivery.playerId(),
                                    "A lore-item campaign reward was delivered to your inventory.");
                        }
                    });
        } catch (RuntimeException exception) {
            requireReview(
                    delivery,
                    "The item was inserted but campaign completion could not be submitted.",
                    exception);
        }
    }

    private void logUnsafeCancellation(
            UUID campaignId,
            String recipientKey,
            Throwable throwable) {
        String message = "A cancelled campaign claim could not be terminalized immediately: "
                + campaignId + '/' + recipientKey
                + ". No physical insertion occurred; bounded expiry recovery will retry.";
        if (throwable == null) {
            plugin.getLogger().warning(message);
        } else {
            plugin.getLogger().log(Level.WARNING, message, unwrap(throwable));
        }
    }

    private void notifyPlayer(UUID playerId, String message) {
        scheduleMain(() -> {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null) {
                LoreItemsMessages.send(player, message);
            }
        });
    }

    private boolean scheduleMain(Runnable task) {
        if (closed.getAsBoolean()) {
            return false;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
            return true;
        } catch (IllegalPluginAccessException exception) {
            plugin.getLogger().log(
                    Level.FINE,
                    "Could not schedule campaign-delivery notification during shutdown.",
                    exception);
            return false;
        }
    }

    private static Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException exception
                && exception.getCause() != null) {
            return exception.getCause();
        }
        return throwable;
    }
}
