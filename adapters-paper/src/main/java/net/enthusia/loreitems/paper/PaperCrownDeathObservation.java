package net.enthusia.loreitems.paper;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;

/** Fatal-damage observation only. No outcome authorizes a Crown mutation. */
final class PaperCrownDeathObservation {
    enum Kind { PLAYER_KILL_CANDIDATE, NON_PLAYER_DEATH, REVIEW_REQUIRED }
    record Observation(Kind kind, UUID attacker) {
        Observation {
            Objects.requireNonNull(kind, "kind");
            if ((kind == Kind.PLAYER_KILL_CANDIDATE) != (attacker != null)) {
                throw new IllegalArgumentException("Only a player-kill candidate has an attacker");
            }
        }
    }

    Observation observe(UUID victim, DamageSource fatalDamage) {
        Objects.requireNonNull(victim, "victim");
        if (fatalDamage == null) return new Observation(Kind.REVIEW_REQUIRED, null);
        try {
            var causing = fatalDamage.getCausingEntity();
            var direct = fatalDamage.getDirectEntity();
            if (!(causing instanceof Player player)) {
                // A player direct entity with conflicting attribution is not a safe non-PvP proof.
                return new Observation(hasPlayerAttribution(direct) ? Kind.REVIEW_REQUIRED : Kind.NON_PLAYER_DEATH, null);
            }
            UUID attacker = player.getUniqueId();
            if (victim.equals(attacker)) return new Observation(Kind.NON_PLAYER_DEATH, null);
            if (directlyFrom(direct, attacker)) return new Observation(Kind.PLAYER_KILL_CANDIDATE, attacker);
            // Explosions, indirect environmental deaths, and inconsistent attribution require review.
            return new Observation(Kind.REVIEW_REQUIRED, null);
        } catch (RuntimeException unavailable) {
            return new Observation(Kind.REVIEW_REQUIRED, null);
        }
    }

    private static boolean hasPlayerAttribution(Entity direct) {
        return direct instanceof Player
                || (direct instanceof Projectile projectile && projectile.getShooter() instanceof Player);
    }

    /** The fatal blow itself, or the projectile that delivered it, came from {@code attacker}. */
    private static boolean directlyFrom(Entity direct, UUID attacker) {
        if (direct instanceof Player directPlayer) return attacker.equals(directPlayer.getUniqueId());
        return direct instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter
                && attacker.equals(shooter.getUniqueId());
    }
}
