package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.enthusia.loreitems.application.ItemCodecException;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.application.PreparedTemplateUpdate;
import org.bukkit.block.BlockState;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;

public final class PaperTemplateUpdateOperator {
    private final PaperItemTemplateCodec templateCodec;
    private final PaperItemIdentityCodec identityCodec;
    private final PaperTemplateItemComparator itemComparator;

    public PaperTemplateUpdateOperator() {
        this(new PaperItemTemplateCodec(), new PaperItemIdentityCodec());
    }

    PaperTemplateUpdateOperator(
            PaperItemTemplateCodec templateCodec,
            PaperItemIdentityCodec identityCodec) {
        this.templateCodec = Objects.requireNonNull(templateCodec, "templateCodec");
        this.identityCodec = Objects.requireNonNull(identityCodec, "identityCodec");
        this.itemComparator = new PaperTemplateItemComparator(identityCodec);
    }

    ApplyResult apply(
            Plugin plugin,
            PaperTemplateUpdateReference reference,
            PreparedTemplateUpdate update) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(update, "update");
        PaperTemplateUpdateReference.Resolved resolved =
                reference.resolve(plugin).orElse(null);
        return resolved == null
                ? ApplyResult.notAccessible()
                : applyResolved(resolved, update);
    }

    private ApplyResult applyResolved(
            PaperTemplateUpdateReference.Resolved resolved,
            PreparedTemplateUpdate update) {
        ItemStack current = resolved.originalItem();
        String beforeFingerprint;
        try {
            beforeFingerprint = PaperItemFingerprint.of(current);
        } catch (RuntimeException exception) {
            return ApplyResult.reviewRequired(
                    null,
                    null,
                    "Paper could not fingerprint the encountered lore item: "
                            + exception.getClass().getSimpleName());
        }
        ItemIdentityReadResult identityResult;
        try {
            identityResult = identityCodec.readIdentity(current);
        } catch (RuntimeException exception) {
            return ApplyResult.reviewRequired(
                    beforeFingerprint,
                    null,
                    "Paper could not read the encountered lore-item identity: "
                            + exception.getClass().getSimpleName());
        }
        if (!(identityResult instanceof ItemIdentityReadResult.Tracked tracked)) {
            return ApplyResult.reviewRequired(
                    beforeFingerprint,
                    null,
                    "The encountered item no longer has one valid tracked identity.");
        }
        return applyTracked(
                resolved,
                current,
                beforeFingerprint,
                tracked.identity(),
                update);
    }

    private ApplyResult applyTracked(
            PaperTemplateUpdateReference.Resolved resolved,
            ItemStack current,
            String beforeFingerprint,
            LoreItemIdentity currentIdentity,
            PreparedTemplateUpdate update) {
        if (!currentIdentity.equals(update.observedIdentity())
                && !currentIdentity.equals(update.targetIdentity())) {
            return ApplyResult.reviewRequired(
                    beforeFingerprint,
                    null,
                    "The encountered item identity changed after durable preparation.");
        }
        ItemStack expected;
        try {
            expected = expectedTarget(current, update);
        } catch (ItemCodecException | IllegalArgumentException exception) {
            return ApplyResult.reviewRequired(
                    beforeFingerprint, null, safeDetail(exception));
        } catch (RuntimeException exception) {
            return ApplyResult.reviewRequired(
                    beforeFingerprint,
                    null,
                    "Paper could not build the target template: "
                            + exception.getClass().getSimpleName());
        }
        return verifyOrReplace(
                resolved,
                current,
                expected,
                beforeFingerprint,
                currentIdentity,
                update);
    }

    private ApplyResult verifyOrReplace(
            PaperTemplateUpdateReference.Resolved resolved,
            ItemStack current,
            ItemStack expected,
            String beforeFingerprint,
            LoreItemIdentity currentIdentity,
            PreparedTemplateUpdate update) {
        if (currentIdentity.equals(update.targetIdentity())) {
            return samePhysicalItem(current, expected)
                    ? ApplyResult.alreadyApplied(beforeFingerprint)
                    : ApplyResult.reviewRequired(
                            beforeFingerprint,
                            null,
                            "The item carries the target revision identity but its physical "
                                    + "template does not match the queued revision.");
        }
        return replaceAndVerify(
                resolved,
                expected,
                beforeFingerprint,
                update.targetIdentity());
    }

    private ApplyResult replaceAndVerify(
            PaperTemplateUpdateReference.Resolved resolved,
            ItemStack expected,
            String beforeFingerprint,
            LoreItemIdentity targetIdentity) {
        if (!resolved.replace(expected)) {
            return ApplyResult.reviewRequired(
                    beforeFingerprint,
                    null,
                    "The encountered item changed before the template could be replaced.");
        }
        ItemStack stored = resolved.readStored();
        String afterFingerprint = fingerprintOrNull(stored);
        if (stored != null
                && samePhysicalItem(stored, expected)
                && hasIdentity(stored, targetIdentity)) {
            return ApplyResult.applied(beforeFingerprint, afterFingerprint);
        }
        return restoreAfterFailedVerification(resolved, beforeFingerprint, afterFingerprint);
    }

    private static ApplyResult restoreAfterFailedVerification(
            PaperTemplateUpdateReference.Resolved resolved,
            String beforeFingerprint,
            String afterFingerprint) {
        boolean restored;
        try {
            restored = resolved.restore();
        } catch (RuntimeException exception) {
            restored = false;
        }
        String detail = restored
                ? "Paper did not retain the verified target item; the original item was restored."
                : "Paper did not retain the verified target item and the original item could not "
                        + "be proven restored.";
        return ApplyResult.reviewRequired(beforeFingerprint, afterFingerprint, detail);
    }

    private ItemStack expectedTarget(
            ItemStack current,
            PreparedTemplateUpdate update) {
        ItemStack desired = templateCodec.decode(update.targetTemplate());
        ItemStack withContents = preserveMutableContents(current, desired);
        PaperSignaturePreservation.preserve(current, withContents);
        return identityCodec.writeIdentity(withContents, update.targetIdentity());
    }

    private static ItemStack preserveMutableContents(
            ItemStack current,
            ItemStack desired) {
        ItemStack result = desired.clone();
        PaperTemplateItemComparator.clearMutableContents(result);
        preserveShulkerContents(current, result);
        preserveBundleContents(current, result);
        return result;
    }

    private static void preserveShulkerContents(ItemStack current, ItemStack desired) {
        ItemMeta currentMeta = current.getItemMeta();
        if (!(currentMeta instanceof BlockStateMeta currentBlockMeta)) {
            return;
        }
        BlockState currentState = Objects.requireNonNull(
                currentBlockMeta.getBlockState(), "current shulker block state");
        if (!(currentState instanceof ShulkerBox currentShulker)) {
            return;
        }
        Inventory currentInventory = Objects.requireNonNull(
                currentShulker.getInventory(), "current shulker inventory");
        ItemStack[] contents = Objects.requireNonNull(
                currentInventory.getContents(), "current shulker contents");
        ItemMeta desiredMeta = desired.getItemMeta();
        if (!(desiredMeta instanceof BlockStateMeta desiredBlockMeta)) {
            if (containsItem(contents)) {
                throw new IllegalArgumentException(
                        "A non-empty shulker lore item cannot change to an incompatible template");
            }
            return;
        }
        BlockState desiredState = Objects.requireNonNull(
                desiredBlockMeta.getBlockState(), "desired shulker block state");
        if (!(desiredState instanceof ShulkerBox desiredShulker)) {
            if (containsItem(contents)) {
                throw new IllegalArgumentException(
                        "A non-empty shulker lore item cannot change to an incompatible template");
            }
            return;
        }
        Inventory desiredInventory = Objects.requireNonNull(
                desiredShulker.getInventory(), "desired shulker inventory");
        desiredInventory.setContents(cloneContents(contents));
        desiredBlockMeta.setBlockState(desiredShulker);
        if (!desired.setItemMeta(desiredBlockMeta)) {
            throw new IllegalArgumentException("Paper rejected preserved shulker contents");
        }
    }

    private static void preserveBundleContents(ItemStack current, ItemStack desired) {
        ItemMeta currentMeta = current.getItemMeta();
        if (!(currentMeta instanceof BundleMeta currentBundle)) {
            return;
        }
        List<ItemStack> contents = currentBundle.getItems();
        ItemMeta desiredMeta = desired.getItemMeta();
        if (!(desiredMeta instanceof BundleMeta desiredBundle)) {
            if (!contents.isEmpty()) {
                throw new IllegalArgumentException(
                        "A non-empty bundle lore item cannot change to an incompatible template");
            }
            return;
        }
        List<ItemStack> clones = new ArrayList<>(contents.size());
        for (ItemStack item : contents) {
            clones.add(item.clone());
        }
        desiredBundle.setItems(clones);
        if (!desired.setItemMeta(desiredBundle)) {
            throw new IllegalArgumentException("Paper rejected preserved bundle contents");
        }
    }

    private boolean hasIdentity(ItemStack item, LoreItemIdentity expected) {
        ItemIdentityReadResult result = identityCodec.readIdentity(item);
        return result instanceof ItemIdentityReadResult.Tracked tracked
                && expected.equals(tracked.identity());
    }

    private boolean samePhysicalItem(ItemStack first, ItemStack second) {
        if (PaperItemFingerprint.of(first).equals(PaperItemFingerprint.of(second))) {
            return true;
        }
        return itemComparator.matches(first, second);
    }

    private static boolean containsItem(ItemStack[] contents) {
        for (ItemStack item : contents) {
            if (item != null && !item.getType().isAir()) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] clones = new ItemStack[contents.length];
        for (int index = 0; index < contents.length; index++) {
            ItemStack item = contents[index];
            clones[index] = item == null ? null : item.clone();
        }
        return clones;
    }

    private static String fingerprintOrNull(ItemStack item) {
        if (item == null) {
            return null;
        }
        try {
            return PaperItemFingerprint.of(item);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static String safeDetail(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
    }

    record ApplyResult(
            Status status,
            String beforeFingerprint,
            String afterFingerprint,
            String detail) {
        ApplyResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            if (detail.isBlank()) {
                throw new IllegalArgumentException("detail must not be blank");
            }
            boolean successful = status == Status.APPLIED
                    || status == Status.ALREADY_APPLIED;
            if (successful
                    && (beforeFingerprint == null || afterFingerprint == null)) {
                throw new IllegalArgumentException(
                        "Successful template updates require before and after fingerprints");
            }
            if (status == Status.NOT_ACCESSIBLE
                    && (beforeFingerprint != null || afterFingerprint != null)) {
                throw new IllegalArgumentException(
                        "Inaccessible items must not claim physical fingerprints");
            }
        }

        static ApplyResult applied(String beforeFingerprint, String afterFingerprint) {
            return new ApplyResult(
                    Status.APPLIED,
                    beforeFingerprint,
                    afterFingerprint,
                    "The target revision was written and verified at the same physical reference.");
        }

        static ApplyResult alreadyApplied(String fingerprint) {
            return new ApplyResult(
                    Status.ALREADY_APPLIED,
                    fingerprint,
                    fingerprint,
                    "The physical item already contained the verified target revision.");
        }

        static ApplyResult notAccessible() {
            return new ApplyResult(
                    Status.NOT_ACCESSIBLE,
                    null,
                    null,
                    "The item moved or unloaded before the claimed natural encounter ran.");
        }

        static ApplyResult reviewRequired(
                String beforeFingerprint,
                String afterFingerprint,
                String detail) {
            return new ApplyResult(
                    Status.REVIEW_REQUIRED,
                    beforeFingerprint,
                    afterFingerprint,
                    detail);
        }

        enum Status {
            APPLIED,
            ALREADY_APPLIED,
            NOT_ACCESSIBLE,
            REVIEW_REQUIRED
        }
    }
}
