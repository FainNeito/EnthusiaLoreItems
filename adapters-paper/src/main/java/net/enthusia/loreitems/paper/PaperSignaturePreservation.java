package net.enthusia.loreitems.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Carries an EnthusiaSignature signature across a template update. EnthusiaSignature keeps its
 * state under the {@code itemsignature} namespace and appends its rendered lines at the end of the
 * lore, each tagged with the {@code itemsignature:managed} insertion; both are copied onto the
 * rebuilt item so a template edit never erases a player's signature.
 */
final class PaperSignaturePreservation {
    private static final String NAMESPACE = "itemsignature";
    private static final String MANAGED_INSERTION = "itemsignature:managed";
    private static final List<PersistentDataType<?, ?>> TYPES = List.of(
            PersistentDataType.STRING,
            PersistentDataType.LONG,
            PersistentDataType.DOUBLE,
            PersistentDataType.INTEGER,
            PersistentDataType.BYTE);

    private PaperSignaturePreservation() {
    }

    static void preserve(ItemStack current, ItemStack desired) {
        ItemMeta from = current.getItemMeta();
        if (from == null) {
            return;
        }
        List<NamespacedKey> keys = from.getPersistentDataContainer().getKeys().stream()
                .filter(key -> NAMESPACE.equals(key.getNamespace()))
                .toList();
        List<Component> managed = managedLines(from.lore());
        if (keys.isEmpty() && managed.isEmpty()) {
            return;
        }
        ItemMeta to = Objects.requireNonNull(desired.getItemMeta(), "desired item metadata");
        PersistentDataContainer target = to.getPersistentDataContainer();
        for (NamespacedKey key : keys) {
            copy(from.getPersistentDataContainer(), target, key);
        }
        if (!managed.isEmpty()) {
            List<Component> lore = new ArrayList<>(unmanagedLines(to.lore()));
            lore.addAll(managed);
            to.lore(lore);
        }
        if (!desired.setItemMeta(to)) {
            throw new IllegalArgumentException("Paper rejected the preserved signature");
        }
    }

    private static List<Component> managedLines(List<Component> lore) {
        return lore == null ? List.of()
                : lore.stream().filter(line -> MANAGED_INSERTION.equals(line.insertion())).toList();
    }

    private static List<Component> unmanagedLines(List<Component> lore) {
        return lore == null ? List.of()
                : lore.stream().filter(line -> !MANAGED_INSERTION.equals(line.insertion())).toList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void copy(PersistentDataContainer from, PersistentDataContainer to, NamespacedKey key) {
        for (PersistentDataType type : TYPES) {
            if (from.has(key, type)) {
                to.set(key, type, from.get(key, type));
                return;
            }
        }
    }
}
