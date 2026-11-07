package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import net.enthusia.loreitems.application.CrownEquipmentRecovery;
import net.enthusia.loreitems.application.MetricsPort;
import net.enthusia.loreitems.domain.ExclusiveCustodyReservation;
import net.enthusia.loreitems.application.StorageState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SQLiteCrownIdentityAuditTest {
    @TempDir Path temp;

    @Test void matchingDatabaseRecordsStillRequirePhysicalProofAndConflictsAreReported() {
        var runtime = start(); var definition = UUID.randomUUID(); var instance = UUID.randomUUID();
        var audit = new SQLiteCrownIdentityAudit(runtime);
        try {
            var absent = audit.inspect(definition, instance).toCompletableFuture().join();
            assertTrue(absent.contains(SQLiteCrownIdentityAudit.Finding.DEFINITION_UNAVAILABLE));
            assertTrue(absent.contains(SQLiteCrownIdentityAudit.Finding.EXPECTED_INSTANCE_UNAVAILABLE));
            assertTrue(absent.contains(SQLiteCrownIdentityAudit.Finding.LIVE_PHYSICAL_PROOF_REQUIRED));
            seedBase(runtime, definition, instance);
            var snapshot = audit.inspect(definition, instance).toCompletableFuture().join();
            assertEquals(Set.of(SQLiteCrownIdentityAudit.Finding.LIVE_PHYSICAL_PROOF_REQUIRED), snapshot);
            var wrongInstance = audit.inspect(definition, UUID.randomUUID()).toCompletableFuture().join();
            assertTrue(wrongInstance.contains(SQLiteCrownIdentityAudit.Finding.UNEXPECTED_ACTIVE_INSTANCE));
            assertFalse(wrongInstance.contains(SQLiteCrownIdentityAudit.Finding.MULTIPLE_ACTIVE_INSTANCES));
            assertThrows(UnsupportedOperationException.class, () -> snapshot.add(SQLiteCrownIdentityAudit.Finding.PENDING_DELIVERY));
            seedConflicts(runtime, definition, instance);
            var conflicts = audit.inspect(definition, instance).toCompletableFuture().join();
            assertTrue(conflicts.containsAll(Set.of(SQLiteCrownIdentityAudit.Finding.MULTIPLE_ACTIVE_INSTANCES,
                    SQLiteCrownIdentityAudit.Finding.UNRESOLVED_IDENTITY_ANOMALY,
                    SQLiteCrownIdentityAudit.Finding.PENDING_DELIVERY,
                    SQLiteCrownIdentityAudit.Finding.EXTERNAL_DELIVERY_HISTORY,
                    SQLiteCrownIdentityAudit.Finding.OPEN_DISTRIBUTION,
                    SQLiteCrownIdentityAudit.Finding.PENDING_MUTATION,
                    SQLiteCrownIdentityAudit.Finding.DESTRUCTIVE_OPERATION,
                    SQLiteCrownIdentityAudit.Finding.LIVE_PHYSICAL_PROOF_REQUIRED)));
            for (String change : java.util.List.of(
                    "UPDATE destructive_operations SET state='ABORTED',terminal_at=1 WHERE operation_id='destructive'",
                    "UPDATE destructive_targets SET effect_state='UNKNOWN' WHERE operation_id='destructive'",
                    "UPDATE destructive_targets SET state='ABORTED' WHERE operation_id='destructive'",
                    "UPDATE destructive_targets SET effect_state='AMBIGUOUS' WHERE operation_id='destructive'")) {
                sql(runtime, change);
                assertTrue(audit.inspect(definition, instance).toCompletableFuture().join()
                        .contains(SQLiteCrownIdentityAudit.Finding.DESTRUCTIVE_OPERATION), change);
            }
            var missingWithTwo = audit.inspect(definition, UUID.randomUUID()).toCompletableFuture().join();
            assertTrue(missingWithTwo.contains(SQLiteCrownIdentityAudit.Finding.UNEXPECTED_ACTIVE_INSTANCE));
            assertTrue(missingWithTwo.contains(SQLiteCrownIdentityAudit.Finding.MULTIPLE_ACTIVE_INSTANCES));
        } finally { assertTrue(runtime.close(Duration.ofSeconds(5))); }
        assertThrows(CompletionException.class, () -> audit.inspect(definition, instance).toCompletableFuture().join());
    }

    @Test void physicalAdmissionRejectsMissingIdentityWrongHolderAndConflictingOperationsWithoutReserving() {
        var runtime = start();
        var definition = UUID.randomUUID();
        var instance = UUID.randomUUID();
        var holder = UUID.randomUUID();
        var request = new ExclusiveCustodyReservation(UUID.randomUUID(), instance, holder,
                1, ExclusiveCustodyReservation.State.RESERVED);
        var store = new SQLiteCrownHelmetEvidenceStore(runtime);
        try {
            assertFalse(store.admit(request, definition, true, new byte[0]).toCompletableFuture().join());
            seedBase(runtime, definition, instance);
            assertFalse(store.admit(request, definition, true, new byte[0]).toCompletableFuture().join());
            runtime.execute(c -> {
                try (var update = c.prepareStatement("UPDATE instance_current_state SET location_key=? WHERE instance_id=?")) {
                    update.setString(1, "player:" + holder);
                    update.setString(2, instance.toString());
                    assertEquals(1, update.executeUpdate());
                }
                return null;
            }).toCompletableFuture().join();
            seedConflicts(runtime, definition, instance);
            assertFalse(store.admit(request, definition, true, new byte[0]).toCompletableFuture().join());
            runtime.execute(c -> {
                try (var s = c.createStatement(); var rows = s.executeQuery("SELECT (SELECT count(*) FROM crown_custody)"
                        + "+(SELECT count(*) FROM crown_custody_audit)+(SELECT count(*) FROM crown_helmet_evidence)")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getInt(1));
                }
                return null;
            }).toCompletableFuture().join();
        } finally { assertTrue(runtime.close(Duration.ofSeconds(5))); }
    }
    @Test void firstIssueCommitsOnlyOnceAndStillFencesOtherWritersAfterReopen() {
        var definition = UUID.randomUUID();
        var request = new ExclusiveCustodyReservation(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, ExclusiveCustodyReservation.State.RESERVED);
        var template = new net.enthusia.loreitems.application.EncodedItemTemplate(1, new byte[]{1});
        byte[] original = { 3, 7 };
        var runtime = start();
        try {
            runtime.execute(c -> {
                try (var s = c.prepareStatement("INSERT INTO lore_definitions VALUES (?,'first_crown','Crown',1,1,NULL)")) {
                    s.setString(1, definition.toString()); s.executeUpdate();
                }
                try (var s = c.prepareStatement("INSERT INTO lore_definition_revisions VALUES (?,1,1,X'01',1)")) {
                    s.setString(1, definition.toString()); s.executeUpdate();
                }
                return null;
            }).toCompletableFuture().join();
            var store = new SQLiteCrownHelmetEvidenceStore(runtime);
            assertFalse(store.reserveFirstIssue(request, definition, 2, template, original).toCompletableFuture().join());
            assertTrue(store.reserveFirstIssue(request, definition, 1, template, original).toCompletableFuture().join());
        } finally { runtime.close(Duration.ofSeconds(5)); }
        var reopened = start();
        try {
            var store = new SQLiteCrownHelmetEvidenceStore(reopened);
            var killer = UUID.randomUUID();
            var equipped = equipAfterFencedReopen(reopened, store, request, definition, original, killer);
            byte[] crown = {1, 3, 5};
            var transfer = releaseToKiller(store, request, definition, equipped, killer, crown);
            commitSuccessorAfterInjectedFailure(reopened, store, request, definition, equipped, transfer, killer);
            UUID reclamation = UUID.randomUUID();
            UUID recoveryMatch = UUID.randomUUID();
            reclaimFromInactiveSuccessor(reopened, store, request, killer, reclamation, recoveryMatch, crown, transfer);
            redeliverToRecoveryWinner(reopened, store, request, killer, reclamation, recoveryMatch, crown);
        } finally { reopened.close(Duration.ofSeconds(5)); }
    }

    private static CrownEquipmentRecovery.Receipt equipAfterFencedReopen(SQLiteStorageRuntime reopened,
            SQLiteCrownHelmetEvidenceStore store, ExclusiveCustodyReservation request, UUID definition, byte[] original, UUID killer) {
        var template = new net.enthusia.loreitems.application.EncodedItemTemplate(1, new byte[]{1});
        assertFalse(store.reserveFirstIssue(request, definition, 1, template, original).toCompletableFuture().join());
        assertTrue(store.matchesSavedHelmet(request, original).toCompletableFuture().join());
        assertThrows(CompletionException.class, () -> reopened.execute(c -> {
            insertInstance(c, definition, UUID.randomUUID());
            return null;
        }).toCompletableFuture().join());
        assertFalse(store.holdInterruptedOnStartup().toCompletableFuture().join());
        assertTrue(store.confirmLoaded(request, UUID.randomUUID(), original).toCompletableFuture().join().isEmpty());
        assertTrue(store.confirmLoaded(request, definition, new byte[]{8}).toCompletableFuture().join().isEmpty());
        assertTrue(store.matchesSavedHelmet(request, original).toCompletableFuture().join());
        assertTrue(store.recordPlayerKill(request, killer).toCompletableFuture().join());
        assertTrue(store.matchesSavedHelmet(request, original).toCompletableFuture().join());
        var equipped = store.confirmLoaded(request, definition, original).toCompletableFuture().join().orElseThrow();
        assertEquals(request, equipped.reservation());
        assertEquals(equipped, store.findEquipped(request.holderId()).toCompletableFuture().join().orElseThrow());
        assertTrue(store.recordPlayerKill(request, killer).toCompletableFuture().join());
        assertFalse(store.recordPlayerKill(request, UUID.randomUUID()).toCompletableFuture().join());
        assertFalse(store.recordPlayerKill(request, request.holderId()).toCompletableFuture().join());
        return equipped;
    }

    private static CrownEquipmentRecovery.Delivery releaseToKiller(SQLiteCrownHelmetEvidenceStore store,
            ExclusiveCustodyReservation request, UUID definition, CrownEquipmentRecovery.Receipt equipped, UUID killer, byte[] crown) {
        assertTrue(store.escrowForHandoff(equipped, crown).toCompletableFuture().join());
        assertFalse(store.escrowForHandoff(equipped, new byte[]{4}).toCompletableFuture().join());
        assertTrue(store.takeReleased(killer).toCompletableFuture().join().isEmpty());
        assertTrue(store.confirmSourceReleased(killer, request.operationId(), request.instanceId(), definition)
                .toCompletableFuture().join().isEmpty());
        assertEquals(killer, store.confirmSourceReleased(request.holderId(), request.operationId(), request.instanceId(), definition)
                .toCompletableFuture().join().orElseThrow());
        assertTrue(store.takeReleased(UUID.randomUUID()).toCompletableFuture().join().isEmpty());
        assertFalse(store.hasUnconfirmedDelivery(killer).toCompletableFuture().join());
        assertEquals(net.enthusia.loreitems.application.CrownEquipmentRecovery.ReclamationReservation.CONFLICT,
                store.reserveReclamation(UUID.randomUUID(), request.instanceId(), request.holderId(), 1).toCompletableFuture().join());
        var transfer = store.takeReleased(killer).toCompletableFuture().join().orElseThrow();
        assertTrue(store.hasUnconfirmedDelivery(killer).toCompletableFuture().join());
        var inspection = store.inspectCustody().toCompletableFuture().join().orElseThrow();
        assertEquals(request.holderId(), inspection.recordedHolder());
        assertEquals(killer, inspection.transfer().orElseThrow().recipient());
        assertEquals(transfer.destination().operationId(), inspection.transfer().orElseThrow().deliveryOperation().orElseThrow());
        assertTrue(inspection.transfer().orElseThrow().sourceReleased());
        assertFalse(store.hasUnconfirmedDelivery(UUID.randomUUID()).toCompletableFuture().join());
        assertTrue(java.util.Arrays.equals(crown, transfer.crown()));
        assertTrue(store.takeReleased(killer).toCompletableFuture().join().isEmpty());
        return transfer;
    }

    private static void commitSuccessorAfterInjectedFailure(SQLiteStorageRuntime reopened, SQLiteCrownHelmetEvidenceStore store,
            ExclusiveCustodyReservation request, UUID definition, CrownEquipmentRecovery.Receipt equipped,
            CrownEquipmentRecovery.Delivery transfer, UUID killer) {
        assertFalse(store.commitSuccessor(transfer.destination(), UUID.randomUUID()).toCompletableFuture().join());
        sql(reopened, "CREATE TRIGGER reject_successor_audit BEFORE INSERT ON crown_custody_audit "
                + "BEGIN SELECT RAISE(ABORT,'injected successor audit failure'); END");
        assertThrows(CompletionException.class,
                () -> store.commitSuccessor(transfer.destination(), definition).toCompletableFuture().join());
        assertEquals(equipped, store.findEquipped(request.holderId()).toCompletableFuture().join().orElseThrow());
        assertTrue(store.findEquipped(killer).toCompletableFuture().join().isEmpty());
        assertTrue(store.takeReleased(killer).toCompletableFuture().join().isEmpty());
        sql(reopened, "DROP TRIGGER reject_successor_audit");
        assertTrue(store.commitSuccessor(transfer.destination(), definition).toCompletableFuture().join());
        assertFalse(store.commitSuccessor(transfer.destination(), definition).toCompletableFuture().join());
        assertFalse(store.hasUnconfirmedDelivery(killer).toCompletableFuture().join());
        var completedInspection = store.inspectCustody().toCompletableFuture().join().orElseThrow();
        assertEquals(killer, completedInspection.recordedHolder());
        assertEquals(2, completedInspection.generation());
        assertTrue(completedInspection.transfer().isEmpty());
        assertEquals(transfer.destination(), store.findEquipped(killer).toCompletableFuture().join().orElseThrow().reservation());
        assertTrue(store.findEquipped(request.holderId()).toCompletableFuture().join().isEmpty());
    }

    private static void reclaimFromInactiveSuccessor(SQLiteStorageRuntime reopened, SQLiteCrownHelmetEvidenceStore store,
            ExclusiveCustodyReservation request, UUID killer, UUID reclamation, UUID recoveryMatch, byte[] crown,
            CrownEquipmentRecovery.Delivery transfer) {
        var reserved = CrownEquipmentRecovery.ReclamationReservation.RESERVED;
        var conflict = CrownEquipmentRecovery.ReclamationReservation.CONFLICT;
        assertEquals(conflict, store.reserveReclamation(reclamation, request.instanceId(), request.holderId(), 1).toCompletableFuture().join());
        assertEquals(reserved, store.reserveReclamation(reclamation, request.instanceId(), killer, 2).toCompletableFuture().join());
        assertEquals(net.enthusia.loreitems.application.CrownEquipmentRecovery.ReclamationReservation.ALREADY_RESERVED,
                store.reserveReclamation(reclamation, request.instanceId(), killer, 2).toCompletableFuture().join());
        assertEquals(conflict, store.reserveReclamation(reclamation, request.instanceId(), killer, 3).toCompletableFuture().join());
        assertEquals(conflict, store.reserveReclamation(UUID.randomUUID(), request.instanceId(), killer, 2).toCompletableFuture().join());
        assertFalse(store.recordPlayerKill(transfer.destination(), UUID.randomUUID()).toCompletableFuture().join());
        assertEquals(reclamation, store.inspectCustody().toCompletableFuture().join().orElseThrow().reclamationOperation().orElseThrow());
        var reclaim = store.findReclamation(killer).toCompletableFuture().join().orElseThrow();
        assertFalse(store.confirmReclamation(reclaim).toCompletableFuture().join());
        assertEquals(conflict,store.reserveRecoveryWinner(reclamation,request.instanceId(),killer,2,recoveryMatch,request.holderId()).toCompletableFuture().join());
        assertTrue(store.stageReclamation(reclaim, crown).toCompletableFuture().join());
        assertTrue(store.stageReclamation(reclaim, crown).toCompletableFuture().join());
        assertFalse(store.stageReclamation(reclaim, new byte[]{9}).toCompletableFuture().join());
        sql(reopened, "CREATE TRIGGER reject_reclaim_confirmation BEFORE UPDATE ON crown_reclamation_escrow "
                + "BEGIN SELECT RAISE(ABORT,'injected reclaim confirmation failure'); END");
        assertThrows(CompletionException.class, () -> store.confirmReclamation(reclaim).toCompletableFuture().join());
        assertFalse(store.findReclamation(killer).toCompletableFuture().join().orElseThrow().held());
        sql(reopened, "DROP TRIGGER reject_reclaim_confirmation");
        assertTrue(store.confirmReclamation(reclaim).toCompletableFuture().join());
        assertTrue(store.confirmReclamation(reclaim).toCompletableFuture().join());
        assertTrue(store.findReclamation(killer).toCompletableFuture().join().orElseThrow().held());
        assertEquals("HELD",store.inspectCustody().toCompletableFuture().join().orElseThrow().reclamationState());
        assertFalse(store.stageReclamation(reclaim, crown).toCompletableFuture().join());
        sql(reopened, "UPDATE crown_custody SET generation=3");
        assertEquals(conflict, store.reserveReclamation(reclamation, request.instanceId(), killer, 2).toCompletableFuture().join());
        sql(reopened, "UPDATE crown_custody SET generation=2");
        assertEquals(transfer.destination(), store.findEquipped(killer).toCompletableFuture().join().orElseThrow().reservation());
    }

    private static void redeliverToRecoveryWinner(SQLiteStorageRuntime reopened, SQLiteCrownHelmetEvidenceStore store,
            ExclusiveCustodyReservation request, UUID killer, UUID reclamation, UUID recoveryMatch, byte[] crown) {
        var reserved = CrownEquipmentRecovery.ReclamationReservation.RESERVED;
        var conflict = CrownEquipmentRecovery.ReclamationReservation.CONFLICT;
        assertEquals(conflict,store.reserveRecoveryWinner(reclamation,request.instanceId(),killer,1,recoveryMatch,request.holderId()).toCompletableFuture().join());
        sql(reopened, "CREATE TRIGGER reject_recovery_grant BEFORE INSERT ON crown_recovery_delivery BEGIN SELECT RAISE(ABORT,'injected grant failure'); END");
        assertThrows(CompletionException.class,()->store.reserveRecoveryWinner(reclamation,request.instanceId(),killer,2,recoveryMatch,request.holderId()).toCompletableFuture().join());
        assertTrue(store.takeReleased(request.holderId()).toCompletableFuture().join().isEmpty());
        sql(reopened, "DROP TRIGGER reject_recovery_grant");
        assertEquals(reserved,store.reserveRecoveryWinner(reclamation,request.instanceId(),killer,2,recoveryMatch,request.holderId()).toCompletableFuture().join());
        assertEquals(net.enthusia.loreitems.application.CrownEquipmentRecovery.ReclamationReservation.ALREADY_RESERVED,
                store.reserveRecoveryWinner(reclamation,request.instanceId(),killer,2,recoveryMatch,request.holderId()).toCompletableFuture().join());
        assertEquals(conflict,store.reserveRecoveryWinner(reclamation,request.instanceId(),killer,2,UUID.randomUUID(),request.holderId()).toCompletableFuture().join());
        assertEquals(conflict,store.reserveRecoveryWinner(reclamation,request.instanceId(),killer,2,recoveryMatch,UUID.randomUUID()).toCompletableFuture().join());
        assertTrue(store.releasedReclamation(killer,reclamation).toCompletableFuture().join().isPresent());
        var recovered=store.takeReleased(request.holderId()).toCompletableFuture().join().orElseThrow();
        assertArrayEquals(crown,recovered.crown());
        assertEquals(3,recovered.destination().generation());
        assertTrue(store.takeReleased(request.holderId()).toCompletableFuture().join().isEmpty());
        assertTrue(store.hasUnconfirmedDelivery(request.holderId()).toCompletableFuture().join());
        assertFalse(store.commitSuccessor(recovered.destination(),UUID.randomUUID()).toCompletableFuture().join());
        assertTrue(store.commitSuccessor(recovered.destination(),recovered.definition()).toCompletableFuture().join());
        assertFalse(store.hasUnconfirmedDelivery(request.holderId()).toCompletableFuture().join());
        assertTrue(store.takeReleased(request.holderId()).toCompletableFuture().join().isEmpty());
        assertEquals(recovered.destination(),store.findEquipped(request.holderId()).toCompletableFuture().join().orElseThrow().reservation());
    }

    private static void sql(SQLiteStorageRuntime runtime, String statement) {
        runtime.execute(c -> {
            try (var s = c.createStatement()) { s.execute(statement); }
            return null;
        }).toCompletableFuture().join();
    }

    private SQLiteStorageRuntime start() {
        var metrics = MetricsPort.noOp();
        var runtime = new SQLiteStorageRuntime(new SQLiteConnectionFactory(temp.resolve("identity-audit.db"), 5000),
                new MigrationRunner(), new BoundedDatabaseExecutor("crown-identity-audit", 16, metrics), metrics);
        assertEquals(StorageState.READ_WRITE, runtime.start().toCompletableFuture().join().state());
        return runtime;
    }

    private static void seedBase(SQLiteStorageRuntime runtime, UUID definition, UUID instance) {
        runtime.execute(c -> {
            try (var s = c.prepareStatement("INSERT INTO lore_definitions VALUES (?,'crown_test','Crown',1,1,NULL)")) {
                s.setString(1, definition.toString()); s.executeUpdate();
            }
            try (var s = c.prepareStatement("INSERT INTO lore_definition_revisions VALUES (?,1,1,X'01',1)")) {
                s.setString(1, definition.toString()); s.executeUpdate();
            }
            insertInstance(c, definition, instance);
            try (var s = c.prepareStatement("INSERT INTO instance_observations "
                    + "(observation_id,instance_id,definition_id,location_type,location_key,confidence,source,observed_at) "
                    + "VALUES (1,?,?,'PLAYER_INVENTORY','player:test','CONFIRMED_NOW','test',1)")) {
                s.setString(1, instance.toString()); s.setString(2, definition.toString()); s.executeUpdate();
            }
            try (var s = c.prepareStatement("INSERT INTO instance_current_state "
                    + "(instance_id,state,location_type,location_key,last_observation_id,state_revision,updated_at) "
                    + "VALUES (?,'CONFIRMED_NOW','PLAYER_INVENTORY','player:test',1,0,1)")) {
                s.setString(1, instance.toString()); s.executeUpdate();
            }
            return null;
        }).toCompletableFuture().join();
    }

    private static void insertInstance(java.sql.Connection c, UUID definition, UUID instance) throws java.sql.SQLException {
        try (var s = c.prepareStatement("INSERT INTO lore_instances VALUES (?,?,1,1,'ACTIVE',1,NULL)")) {
            s.setString(1, instance.toString()); s.setString(2, definition.toString()); s.executeUpdate();
        }
    }

    private static void seedConflicts(SQLiteStorageRuntime runtime, UUID definition, UUID instance) {
        runtime.execute(c -> {
            insertInstance(c, definition, UUID.randomUUID());
            try (var s = c.prepareStatement("INSERT INTO instance_anomalies "
                    + "(anomaly_id,instance_id,definition_id,anomaly_type,status,detail,first_seen_at,last_seen_at,state_revision) "
                    + "VALUES ('a',?,?,'DUPLICATE_INSTANCE','OPEN','test',1,1,0)")) {
                s.setString(1, instance.toString()); s.setString(2, definition.toString()); s.executeUpdate();
            }
            try (var s = c.prepareStatement("INSERT INTO direct_deliveries "
                    + "(delivery_id,instance_id,player_id,state,idempotency_key,created_at,updated_at) VALUES ('d',?,'p','PENDING','op',1,1)")) {
                s.setString(1, instance.toString()); s.executeUpdate();
            }
            try (var s = c.prepareStatement("INSERT INTO distribution_campaigns VALUES ('campaign','fp','source','name',?,'DRAFT',1,1,NULL)")) {
                s.setString(1, definition.toString()); s.executeUpdate();
            }
            try (var s = c.prepareStatement("INSERT INTO pending_mutations "
                    + "(mutation_id,mutation_type,definition_id,instance_id,state,created_at,updated_at) VALUES ('m','test',?,?,'PENDING',1,1)")) {
                s.setString(1, definition.toString()); s.setString(2, instance.toString()); s.executeUpdate();
            }
            try (var s = c.prepareStatement("INSERT INTO destructive_operations "
                    + "(operation_id,operation_type,definition_id,exact_instance_id,expected_revision,state,actor_id,"
                    + "idempotency_key,confirmation_token,target_count,accepted_at,updated_at) "
                    + "VALUES ('destructive','EXACT_INSTANCE_REMOVAL',?,?,1,'ACTIVE','actor','destructive-op','token',1,1,1)")) {
                s.setString(1, definition.toString()); s.setString(2, instance.toString()); s.executeUpdate();
            }
            try (var s = c.prepareStatement("INSERT INTO destructive_targets "
                    + "(operation_id,instance_id,definition_id,expected_applied_revision,state,effect_state,created_at,updated_at) "
                    + "VALUES ('destructive',?,?,1,'REVIEW_REQUIRED','AMBIGUOUS',1,1)")) {
                s.setString(1, instance.toString()); s.setString(2, definition.toString()); s.executeUpdate();
            }
            try (var s = c.prepareStatement("INSERT INTO external_delivery_requests VALUES ('external','crown_test','p',NULL,'UNKNOWN_DEFINITION',1,1)")) {
                s.executeUpdate();
            }
            return null;
        }).toCompletableFuture().join();
    }
}
