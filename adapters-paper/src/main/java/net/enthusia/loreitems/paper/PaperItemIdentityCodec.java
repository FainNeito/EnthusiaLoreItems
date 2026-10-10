package net.enthusia.loreitems.paper;

import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import net.enthusia.loreitems.application.ItemCodecException;
import net.enthusia.loreitems.application.ItemCodecFailure;
import net.enthusia.loreitems.application.ItemIdentityCodec;
import net.enthusia.loreitems.application.ItemIdentityFailure;
import net.enthusia.loreitems.application.ItemIdentityReadResult;
import net.enthusia.loreitems.application.LoreItemIdentity;
import net.enthusia.loreitems.domain.LoreDefinitionId;
import net.enthusia.loreitems.domain.LoreInstanceId;
import net.enthusia.loreitems.domain.TemplateRevision;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

public final class PaperItemIdentityCodec implements ItemIdentityCodec<ItemStack> {
    public static final int CURRENT_VERSION = 1;

    private static final int UUID_BYTE_COUNT = 16;
    private static final long NO_IDENTITY_KEYS = 0L;
    private static final NamespacedKey VERSION_KEY = key("identity_version");
    private static final NamespacedKey DEFINITION_KEY = key("definition_id");
    private static final NamespacedKey INSTANCE_KEY = key("instance_id");
    private static final NamespacedKey REVISION_KEY = key("applied_revision");
    private static final Set<NamespacedKey> IDENTITY_KEYS =
            Set.of(VERSION_KEY, DEFINITION_KEY, INSTANCE_KEY, REVISION_KEY);

    private final PaperItemCodecThreadGuard threadGuard;

    public PaperItemIdentityCodec() {
        this(PaperItemCodecThreadGuard.system());
    }

    PaperItemIdentityCodec(PaperItemCodecThreadGuard threadGuard) {
        this.threadGuard = Objects.requireNonNull(threadGuard, "threadGuard");
    }

    @Override
    public int currentVersion() {
        return CURRENT_VERSION;
    }

    @Override
    public ItemStack writeIdentity(ItemStack item, LoreItemIdentity identity) {
        threadGuard.requirePrimaryThread();
        Objects.requireNonNull(identity, "identity");
        ItemStack result = requireUsableClone(item);
        result.setAmount(1);

        ItemMeta meta = requireItemMeta(result);
        meta.setMaxStackSize(1);
        PersistentDataContainer data = meta.getPersistentDataContainer();
        data.set(VERSION_KEY, PersistentDataType.INTEGER, CURRENT_VERSION);
        data.set(DEFINITION_KEY, PersistentDataType.BYTE_ARRAY, uuidBytes(identity.definitionId().value()));
        data.set(INSTANCE_KEY, PersistentDataType.BYTE_ARRAY, uuidBytes(identity.instanceId().value()));
        data.set(REVISION_KEY, PersistentDataType.LONG, identity.appliedRevision().value());
        applyMeta(result, meta);
        return result;
    }

    @Override
    public ItemStack clearIdentity(ItemStack item) {
        threadGuard.requirePrimaryThread();
        ItemStack result = requireUsableClone(item);
        ItemMeta meta = requireItemMeta(result);
        PersistentDataContainer data = meta.getPersistentDataContainer();
        IDENTITY_KEYS.forEach(data::remove);
        applyMeta(result, meta);
        return result;
    }

    public boolean hasIdentityEvidence(ItemStack item) {
        threadGuard.requirePrimaryThread();
        if (item == null || item.getType().isAir()) {
            return false;
        }
        // Ordinary items with no metadata cannot carry any persistent identity
        // keys. Avoid materializing ItemMeta on the hot-path evidence probe.
        if (!item.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return false;
        }
        Set<NamespacedKey> presentKeys = meta.getPersistentDataContainer().getKeys();
        return IDENTITY_KEYS.stream().anyMatch(presentKeys::contains);
    }

    @Override
    public ItemIdentityReadResult readIdentity(ItemStack item) {
        threadGuard.requirePrimaryThread();
        Objects.requireNonNull(item, "item");
        if (item.getType().isAir()) {
            return invalid(ItemIdentityFailure.MALFORMED_DATA, "Air cannot carry lore-item identity");
        }

        // Identity is stored exclusively in ItemMeta PDC. A non-air stack
        // without ItemMeta has no identity bytes to validate or decode.
        // The primary-thread guard and malformed air behavior remain above.
        if (!item.hasItemMeta()) {
            return new ItemIdentityReadResult.Untracked();
        }

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return invalid(ItemIdentityFailure.MALFORMED_DATA, "Item metadata is unavailable");
        }
        return readIdentity(item, meta.getPersistentDataContainer());
    }

    private static ItemIdentityReadResult readIdentity(
            ItemStack item, PersistentDataContainer data) {
        long presentIdentityKeys = IDENTITY_KEYS.stream().filter(data.getKeys()::contains).count();
        if (presentIdentityKeys == NO_IDENTITY_KEYS) {
            return new ItemIdentityReadResult.Untracked();
        }
        if (presentIdentityKeys != IDENTITY_KEYS.size()) {
            return invalid(
                    ItemIdentityFailure.PARTIAL_DATA,
                    "Lore-item identity fields are incomplete",
                    readIdentityEvidence(data));
        }
        return readCompleteIdentity(item, data);
    }

    private static ItemIdentityReadResult readCompleteIdentity(
            ItemStack item, PersistentDataContainer data) {
        byte[] definitionBytes = data.get(DEFINITION_KEY, PersistentDataType.BYTE_ARRAY);
        byte[] instanceBytes = data.get(INSTANCE_KEY, PersistentDataType.BYTE_ARRAY);
        Long revision = data.get(REVISION_KEY, PersistentDataType.LONG);
        if (definitionBytes == null || instanceBytes == null || revision == null) {
            return invalid(ItemIdentityFailure.MALFORMED_DATA, "Lore-item identity fields use invalid data types");
        }

        LoreItemIdentity identity;
        try {
            identity = decodeIdentity(definitionBytes, instanceBytes, revision);
        } catch (IllegalArgumentException exception) {
            return invalid(ItemIdentityFailure.MALFORMED_DATA, "Lore-item identity values are invalid");
        }
        Integer version = data.get(VERSION_KEY, PersistentDataType.INTEGER);
        if (version == null) {
            return invalid(
                    ItemIdentityFailure.MALFORMED_DATA,
                    "Lore-item identity version uses an invalid data type",
                    identity);
        }
        if (version != CURRENT_VERSION) {
            return invalid(
                    ItemIdentityFailure.UNSUPPORTED_VERSION,
                    "Unsupported lore-item identity version " + version,
                    identity);
        }
        if (!isUnstackableSingleItem(item)) {
            return invalid(
                    ItemIdentityFailure.STACKING_VIOLATION,
                    "Tracked lore items must have amount and maximum stack size equal to one",
                    identity);
        }
        return new ItemIdentityReadResult.Tracked(identity);
    }

    private static LoreItemIdentity readIdentityEvidence(PersistentDataContainer data) {
        byte[] definitionBytes = data.get(DEFINITION_KEY, PersistentDataType.BYTE_ARRAY);
        byte[] instanceBytes = data.get(INSTANCE_KEY, PersistentDataType.BYTE_ARRAY);
        Long revision = data.get(REVISION_KEY, PersistentDataType.LONG);
        if (definitionBytes == null || instanceBytes == null || revision == null) {
            return null;
        }
        try {
            return decodeIdentity(definitionBytes, instanceBytes, revision);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private static boolean isUnstackableSingleItem(ItemStack item) {
        return item.getAmount() == 1 && item.getMaxStackSize() == 1;
    }

    private static LoreItemIdentity decodeIdentity(
            byte[] definitionBytes, byte[] instanceBytes, long revision) {
        return new LoreItemIdentity(
                new LoreDefinitionId(uuidFromBytes(definitionBytes)),
                new LoreInstanceId(uuidFromBytes(instanceBytes)),
                new TemplateRevision(revision));
    }

    private static ItemStack requireUsableClone(ItemStack item) {
        Objects.requireNonNull(item, "item");
        if (item.getType().isAir()) {
            throw new ItemCodecException(ItemCodecFailure.INVALID_ITEM, "Air cannot be encoded as a lore item");
        }
        return item.clone();
    }

    private static ItemMeta requireItemMeta(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            throw new ItemCodecException(ItemCodecFailure.INVALID_ITEM, "Item metadata is unavailable");
        }
        return meta;
    }

    private static void applyMeta(ItemStack item, ItemMeta meta) {
        if (!item.setItemMeta(meta)) {
            throw new ItemCodecException(ItemCodecFailure.PLATFORM_FAILURE, "Paper rejected item metadata");
        }
    }

    private static byte[] uuidBytes(UUID value) {
        return ByteBuffer.allocate(UUID_BYTE_COUNT)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static UUID uuidFromBytes(byte[] value) {
        if (value.length != UUID_BYTE_COUNT) {
            throw new IllegalArgumentException("UUID payload must contain 16 bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private static ItemIdentityReadResult.Invalid invalid(
            ItemIdentityFailure failure, String detail) {
        return new ItemIdentityReadResult.Invalid(failure, detail);
    }

    private static ItemIdentityReadResult.Invalid invalid(
            ItemIdentityFailure failure,
            String detail,
            LoreItemIdentity identityEvidence) {
        return new ItemIdentityReadResult.Invalid(failure, detail, identityEvidence);
    }

    private static NamespacedKey key(String value) {
        return Objects.requireNonNull(
                NamespacedKey.fromString("enthusialoreitems:" + value),
                "Static LoreItems namespaced key must be valid");
    }
}
