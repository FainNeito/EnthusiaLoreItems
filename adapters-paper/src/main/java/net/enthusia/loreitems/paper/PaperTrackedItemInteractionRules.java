package net.enthusia.loreitems.paper;
import java.util.Set;
import org.bukkit.Material;
final class PaperTrackedItemInteractionRules {
    private static final Set<String> CONSUMPTIVE_INTERACTION_MATERIALS = Set.of(
            "BONE_MEAL", "BOWL", "ENDER_EYE", "FIRE_CHARGE", "GLASS_BOTTLE",
            "GLOW_INK_SAC", "HONEYCOMB", "INK_SAC", "MAP", "OMINOUS_TRIAL_KEY",
            "RESIN_CLUMP", "TRIAL_KEY");
    private static final Set<String> CONSUMPTIVE_ENTITY_MATERIALS = Set.of(
            "AMETHYST_SHARD", "BAMBOO", "BROWN_MUSHROOM", "CHEST", "DANDELION",
            "HAY_BLOCK", "LEAD", "NAME_TAG", "POPPY", "RED_MUSHROOM", "SADDLE",
            "SEAGRASS", "SLIME_BALL", "WHEAT", "WOLF_ARMOR");
    private PaperTrackedItemInteractionRules() {}
    static boolean losesIdentityOnInteraction(Material material) {
        String name = material.name();
        return material.isEdible() || CONSUMPTIVE_INTERACTION_MATERIALS.contains(name)
                || name.endsWith("_DYE") || name.endsWith("_SPAWN_EGG");
    }
    static boolean losesIdentityOnEntityInteraction(Material material) {
        String name = material.name();
        return losesIdentityOnInteraction(material) || material.isEdible()
                || CONSUMPTIVE_ENTITY_MATERIALS.contains(name)
                || name.endsWith("_CARPET") || name.endsWith("_HORSE_ARMOR")
                || name.endsWith("_SEEDS");
    }
}
