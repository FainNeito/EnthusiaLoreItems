package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationRunnerV11VoidDestructiveReconciliationTest {
    private static final String DEFINITION_ID = "10000000-0000-0000-0000-000000000011";
    private static final String STATE_ACTIVE = "ACTIVE";
    private static final String EFFECT_UNKNOWN = "UNKNOWN";
    private static final String STATE_CLAIMED = "CLAIMED";
    private static final String VOID_PENDING = "20000000-0000-0000-0000-000000000011";
    private static final String ACTIVE_PENDING = "20000000-0000-0000-0000-000000000012";
    private static final String VOID_CLAIMED = "20000000-0000-0000-0000-000000000013";
    private static final String VOID_REVIEW = "20000000-0000-0000-0000-000000000014";
    private static final String OP_COMPLETE = "30000000-0000-0000-0000-000000000011";
    private static final String OP_ACTIVE = "30000000-0000-0000-0000-000000000012";
    private static final String OP_CLAIMED = "30000000-0000-0000-0000-000000000013";
    private static final String OP_REVIEW = "30000000-0000-0000-0000-000000000014";

    @TempDir
    Path temporaryDirectory;

    @Test
    void v11ReconcilesOnlyAuthoritativeLegacyVoidPendingTargets() throws SQLException {
        SQLiteConnectionFactory factory = new SQLiteConnectionFactory(
                temporaryDirectory.resolve("v11-void-reconcile.db"), 5_000);
        MigrationRunner runner = new MigrationRunner();
        try (Connection connection = factory.open()) {
            runner.migrateThrough(connection, 10);
            seedDefinition(connection);
            seedInstance(connection, VOID_PENDING, "VOID_DESTROYED", 2_000L);
            seedInstance(connection, ACTIVE_PENDING, STATE_ACTIVE, null);
            seedInstance(connection, VOID_CLAIMED, "VOID_DESTROYED", 2_100L);
            seedInstance(connection, VOID_REVIEW, "VOID_DESTROYED", 2_200L);
            seedOperation(connection, OP_COMPLETE, "PAUSED");
            seedOperation(connection, OP_ACTIVE, STATE_ACTIVE);
            seedOperation(connection, OP_CLAIMED, STATE_ACTIVE);
            seedOperation(connection, OP_REVIEW, "PAUSED");
            seedTarget(connection, OP_COMPLETE, VOID_PENDING, "PENDING", EFFECT_UNKNOWN);
            seedTarget(connection, OP_ACTIVE, ACTIVE_PENDING, "PENDING", EFFECT_UNKNOWN);
            seedTarget(connection, OP_CLAIMED, VOID_CLAIMED, STATE_CLAIMED, EFFECT_UNKNOWN);
            seedTarget(connection, OP_REVIEW, VOID_REVIEW, "REVIEW_REQUIRED", "AMBIGUOUS");

            runner.migrateThrough(connection, 11);

            assertEquals(11, schemaVersion(connection));
            assertTarget(connection, OP_COMPLETE, "COMPLETED", "REMOVED_OBSERVED");
            assertEquals("COMPLETED", operationState(connection, OP_COMPLETE));
            assertEquals(1, migrationAuditCount(connection, OP_COMPLETE));
            assertTarget(connection, OP_ACTIVE, "PENDING", EFFECT_UNKNOWN);
            assertEquals(STATE_ACTIVE, operationState(connection, OP_ACTIVE));
            assertTarget(connection, OP_CLAIMED, STATE_CLAIMED, EFFECT_UNKNOWN);
            assertEquals(STATE_ACTIVE, operationState(connection, OP_CLAIMED));
            assertTarget(connection, OP_REVIEW, "REVIEW_REQUIRED", "AMBIGUOUS");
            assertEquals("PAUSED", operationState(connection, OP_REVIEW));

            runner.migrateThrough(connection, 11);
            assertEquals(1, migrationAuditCount(connection, OP_COMPLETE));
        }
    }

    private static void seedDefinition(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lore_definitions(definition_id, lookup_key, display_name, "
                        + "current_revision, created_at, deleted_at) VALUES (?, ?, ?, 1, 500, NULL)")) {
            statement.setString(1, DEFINITION_ID);
            statement.setString(2, "v11_void_reconcile");
            statement.setString(3, "V11 Void Reconcile");
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lore_definition_revisions(definition_id, revision, codec_version, "
                        + "template_blob, created_at) VALUES (?, 1, 1, ?, 500)")) {
            statement.setString(1, DEFINITION_ID);
            statement.setBytes(2, new byte[] {1});
            statement.executeUpdate();
        }
    }

    private static void seedInstance(
            Connection connection, String instanceId, String lifecycle, Long terminalAt)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO lore_instances(instance_id, definition_id, applied_revision, "
                        + "desired_revision, lifecycle_state, created_at, terminal_at) "
                        + "VALUES (?, ?, 1, 1, ?, 600, ?)")) {
            statement.setString(1, instanceId);
            statement.setString(2, DEFINITION_ID);
            statement.setString(3, lifecycle);
            if (terminalAt == null) {
                statement.setNull(4, java.sql.Types.BIGINT);
            } else {
                statement.setLong(4, terminalAt);
            }
            statement.executeUpdate();
        }
    }

    private static void seedOperation(Connection connection, String operationId, String state)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO destructive_operations(operation_id, operation_type, definition_id, "
                        + "exact_instance_id, expected_revision, state, actor_id, idempotency_key, "
                        + "confirmation_token, target_count, accepted_at, updated_at, terminal_at) "
                        + "VALUES (?, 'PURGE_DEFINITION', ?, NULL, 1, ?, 'tester', ?, ?, 1, "
                        + "1000, 1000, NULL)")) {
            statement.setString(1, operationId);
            statement.setString(2, DEFINITION_ID);
            statement.setString(3, state);
            statement.setString(4, "idempotency-" + operationId);
            statement.setString(5, "confirm-" + operationId);
            statement.executeUpdate();
        }
    }

    private static void seedTarget(
            Connection connection,
            String operationId,
            String instanceId,
            String state,
            String effectState) throws SQLException {
        String claimToken = STATE_CLAIMED.equals(state) ? "claim-" + instanceId : null;
        Long claimExpiresAt = STATE_CLAIMED.equals(state) ? 30_000L : null;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO destructive_targets(operation_id, instance_id, definition_id, "
                        + "expected_applied_revision, expected_location_type, expected_location_key, "
                        + "expected_container_path, expected_fingerprint, state, effect_state, "
                        + "claim_token, claim_expires_at, attempt_count, before_fingerprint, "
                        + "after_fingerprint, last_error, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 1, 'PLAYER_INVENTORY', 'player:test', NULL, NULL, "
                        + "?, ?, ?, ?, 0, NULL, NULL, NULL, 1100, 1100)")) {
            statement.setString(1, operationId);
            statement.setString(2, instanceId);
            statement.setString(3, DEFINITION_ID);
            statement.setString(4, state);
            statement.setString(5, effectState);
            if (claimToken == null) {
                statement.setNull(6, java.sql.Types.VARCHAR);
                statement.setNull(7, java.sql.Types.BIGINT);
            } else {
                statement.setString(6, claimToken);
                statement.setLong(7, claimExpiresAt);
            }
            statement.executeUpdate();
        }
    }

    private static void assertTarget(
            Connection connection, String operationId, String state, String effectState)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state, effect_state FROM destructive_targets "
                        + "WHERE operation_id = ?")) {
            statement.setString(1, operationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                assertEquals(state, resultSet.getString("state"));
                assertEquals(effectState, resultSet.getString("effect_state"));
            }
        }
    }

    private static String operationState(Connection connection, String operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT state FROM destructive_operations WHERE operation_id = ?")) {
            statement.setString(1, operationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getString("state");
            }
        }
    }

    private static int migrationAuditCount(Connection connection, String operationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM audit_events WHERE aggregate_type = 'destructive_operation' "
                        + "AND aggregate_id = ? "
                        + "AND event_type = 'destructive_target_satisfied_by_void_loss_migration'")) {
            statement.setString(1, operationId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static int schemaVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                     "SELECT MAX(version) FROM schema_history");
                ResultSet resultSet = statement.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }
}
