package net.enthusia.loreitems.paper;

import static org.junit.jupiter.api.Assertions.*;

import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Arrow;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

class PaperCrownDeathObservationTest {
    @BeforeEach void start() { MockBukkit.mock(); }
    @AfterEach void stop() { MockBukkit.unmock(); }

    @Test void directPlayerKillAndEnvironmentalDeathAreDistinguished() {
        var victim = MockBukkit.getMock().addPlayer();
        var attacker = MockBukkit.getMock().addPlayer();
        var observer = new PaperCrownDeathObservation();
        var playerDamage = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build();
        assertEquals(new PaperCrownDeathObservation.Observation(
                PaperCrownDeathObservation.Kind.PLAYER_KILL_CANDIDATE, attacker.getUniqueId()),
                observer.observe(victim.getUniqueId(), playerDamage));
        assertEquals(PaperCrownDeathObservation.Kind.NON_PLAYER_DEATH,
                observer.observe(victim.getUniqueId(), DamageSource.builder(DamageType.FALL).build()).kind());
        assertEquals(PaperCrownDeathObservation.Kind.NON_PLAYER_DEATH,
                observer.observe(attacker.getUniqueId(), playerDamage).kind());
        assertEquals(PaperCrownDeathObservation.Kind.REVIEW_REQUIRED,
                observer.observe(victim.getUniqueId(), null).kind());
    }

    @Test void conflictingFatalAttributionRequiresReview() {
        var victim = MockBukkit.getMock().addPlayer();
        var attacker = MockBukkit.getMock().addPlayer();
        var other = MockBukkit.getMock().addPlayer();
        var source = DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(other).build();
        assertEquals(PaperCrownDeathObservation.Kind.REVIEW_REQUIRED,
                new PaperCrownDeathObservation().observe(victim.getUniqueId(), source).kind());
    }

    @Test void playerProjectileMustAgreeWithFatalAttribution() {
        var victim = MockBukkit.getMock().addPlayer();
        var attacker = MockBukkit.getMock().addPlayer();
        var other = MockBukkit.getMock().addPlayer();
        var arrow = attacker.launchProjectile(Arrow.class);
        arrow.setShooter(attacker);
        var source = DamageSource.builder(DamageType.ARROW).withCausingEntity(attacker).withDirectEntity(arrow).build();
        var observer = new PaperCrownDeathObservation();
        assertEquals(new PaperCrownDeathObservation.Observation(
                PaperCrownDeathObservation.Kind.PLAYER_KILL_CANDIDATE, attacker.getUniqueId()),
                observer.observe(victim.getUniqueId(), source));
        var conflicting = DamageSource.builder(DamageType.ARROW).withCausingEntity(other).withDirectEntity(arrow).build();
        assertEquals(PaperCrownDeathObservation.Kind.REVIEW_REQUIRED,
                observer.observe(victim.getUniqueId(), conflicting).kind());
    }
}
