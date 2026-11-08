package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationRunnerTest {
    private static final int EXPECTED_SCHEMA_VERSION_COUNT = 18;
    private static final String DEFINITION_ID = "10000000-0000-0000-0000-000000000001";
    private static final String INSTANCE_ID = "20000000-0000-0000-0000-000000000001";
    private static final String PENDING_MUTATION_ID =
            "30000000-0000-0000-0000-000000000001";
    private static final String COMPLETED_MUTATION_ID =
            "30000000-0000-0000-0000-000000000002";

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsTheCurrentSchemaAndCanRunAgain() throws SQLException {
        SQLiteConnectionFactory factory =
                new SQLiteConnectionFactory(temporaryDirectory.resolve("loreitems.db"), 5_000);
        MigrationRunner runner = new MigrationRunner();

        try (Connection connection = factory.open()) {
            runner.migrate(connection);
            runner.migrate(connection);

            assertEquals(EXPECTED_SCHEMA_VERSION_COUNT, countSchemaHistory(connection));
            assertEquals(1, countTable(connection, "direct_deliveries"));
            assertEquals(1, countTable(connection, "template_edit_confirmations"));
            assertEquals(1, countTable(connection, "destructive_operations"));
            assertEquals(1, countTable(connection, "destructive_targets"));
            assertEquals(1, countIndex(connection, "uq_template_update_instance_revision"));
            assertEquals(1, countIndex(connection, "idx_mutations_type_claimable"));
            assertEquals(1, countIndex(connection, "idx_mutations_type_review"));
            assertEquals(1, countIndex(connection, "uq_destructive_target_active_instance"));
        }
    }

    @Test
    void rejectsDatabaseFromNewerSchemaVersion() throws SQLException {
        SQLiteConnectionFactory factory = new SQLiteConnectionFactory(
                temporaryDirectory.resolve("future-schema.db"), 5_000);
        MigrationRunner runner = new MigrationRunner();
        int futureVersion = EXPECTED_SCHEMA_VERSION_COUNT + 1;

        try (Connection connection = factory.open()) {
            runner.migrate(connection);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO schema_history(version, description, applied_at) "
                            + "VALUES (?, 'future schema', 1)")) {
                insert.setInt(1, futureVersion);
                insert.executeUpdate();
            }

            SQLException failure = assertThrows(SQLException.class, () -> runner.migrate(connection));

            assertEquals(
                    "Database schema version " + futureVersion
                            + " is newer than supported version "
                            + EXPECTED_SCHEMA_VERSION_COUNT,
                    failure.getMessage());
            assertEquals(futureVersion, countSchemaHistory(connection));
        }
    }

    @Test
    void upgradesV2MutationQueueAndAllowsCancelledState() throws SQLException {
        SQLiteConnectionFactory factory =
                new SQLiteConnectionFactory(temporaryDirectory.resolve("v2-upgrade.db"), 5_000);
        MigrationRunner runner = new MigrationRunner();

        try (Connection connection = factory.open()) {
            runner.migrate(connection);
            downgradePendingMutationsToV2(connection);

            runner.migrate(connection);
            insertMutationWithState(connection, PENDING_MUTATION_ID, "CANCELLED");

            assertEquals(EXPECTED_SCHEMA_VERSION_COUNT, countSchemaHistory(connection));
            assertEquals(1, countMutationState(connection, "CANCELLED"));
            assertEquals(1, countIndex(connection, "idx_mutations_type_claimable"));
            assertEquals(1, countIndex(connection, "idx_mutations_type_review"));
            assertEquals(1, countTable(connection, "destructive_operations"));
        }
    }

    @Test
    void replaysRolloutMigrationsAndConsolidatesDuplicateTemplateUpdates()
            throws SQLException {
        SQLiteConnectionFactory factory =
                new SQLiteConnectionFactory(temporaryDirectory.resolve("rollout-replay.db"), 5_000);
        MigrationRunner runner = new MigrationRunner();

        try (Connection connection = factory.open()) {
            runner.migrate(connection);
            removeRolloutMigrationHistory(connection);
            insertDefinitionGraph(connection);
            insertTemplateUpdate(connection, PENDING_MUTATION_ID, "PENDING", 2_000L);
            insertTemplateUpdate(connection, COMPLETED_MUTATION_ID, "COMPLETED", 1_000L);

            runner.migrate(connection);

            assertEquals(EXPECTED_SCHEMA_VERSION_COUNT, countSchemaHistory(connection));
            assertEquals(1, countIndex(connection, "idx_instances_revision_rollout"));
            assertEquals(1, countIndex(connection, "uq_template_update_instance_revision"));
            assertEquals(1, countIndex(connection, "idx_template_update_mutations"));
            assertEquals(1, countTemplateUpdates(connection));
            assertEquals(COMPLETED_MUTATION_ID, findTemplateUpdateMutationId(connection));
            assertEquals(1, countTable(connection, "destructive_targets"));
        }
    }

    @Test
    void enforcesExternalIdempotencyKeys() throws SQLException {
        SQLiteConnectionFactory factory =
                new SQLiteConnectionFactory(temporaryDirectory.resolve("idempotency.db"), 5_000);
        MigrationRunner runner = new MigrationRunner();

        try (Connection connection = factory.open()) {
            runner.migrate(connection);
            insertExternalRequest(connection, "reward-claim-42");
            assertThrows(
                    SQLException.class,
                    () -> insertExternalRequest(connection, "reward-claim-42"));
        }
    }

    private static void downgradePendingMutationsToV2(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE pending_mutations_v2_legacy ("
                            + "mutation_id TEXT PRIMARY KEY,"
                            + "mutation_type TEXT NOT NULL,"
                            + "definition_id TEXT,"
                            + "instance_id TEXT,"
                            + "desired_revision INTEGER,"
                            + "state TEXT NOT NULL CHECK (state IN ("
                            + "'PENDING','CLAIMED','APPLIED','VERIFIED','COMPLETED','REVIEW_REQUIRED'"
                            + ")),"
                            + "claim_token TEXT,"
                            + "claim_expires_at INTEGER,"
                            + "attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),"
                            + "next_attempt_at INTEGER,"
                            + "created_at INTEGER NOT NULL,"
                            + "updated_at INTEGER NOT NULL,"
                            + "FOREIGN KEY (definition_id) REFERENCES lore_definitions(definition_id),"
                            + "FOREIGN KEY (instance_id) REFERENCES lore_instances(instance_id)"
                            + ")");
            statement.executeUpdate(
                    "INSERT INTO pending_mutations_v2_legacy SELECT * FROM pending_mutations");
            statement.executeUpdate("DROP TABLE pending_mutations");
            statement.executeUpdate(
                    "ALTER TABLE pending_mutations_v2_legacy RENAME TO pending_mutations");
            statement.executeUpdate(
                    "CREATE INDEX idx_mutations_claimable "
                            + "ON pending_mutations(state, next_attempt_at, created_at)");
            statement.executeUpdate(
                    "CREATE UNIQUE INDEX uq_template_update_instance_revision "
                            + "ON pending_mutations(instance_id, desired_revision) "
                            + "WHERE mutation_type = 'TEMPLATE_UPDATE' "
                            + "AND instance_id IS NOT NULL AND desired_revision IS NOT NULL");
            statement.executeUpdate(
                    "CREATE INDEX idx_template_update_mutations "
                            + "ON pending_mutations(definition_id, desired_revision, state, "
                            + "created_at, mutation_id) WHERE mutation_type = 'TEMPLATE_UPDATE'");
        }
        try (PreparedStatement removeHistory = connection.prepareStatement(
                "DELETE FROM schema_history WHERE version = 3")) {
            removeHistory.executeUpdate();
        }
    }

    private static void removeRolloutMigrationHistory(Connection connection) throws SQLException {
        try (PreparedStatement removeHistory = connection.prepareStatement(
                "DELETE FROM schema_history WHERE version IN (2, 3)")) {
            removeHistory.executeUpdate();
        }
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP INDEX idx_instances_revision_rollout");
            statement.executeUpdate("DROP INDEX uq_template_update_instance_revision");
            statement.executeUpdate("DROP INDEX idx_template_update_mutations");
        }
    }

    private static void insertDefinitionGraph(Connection connection) throws SQLException {
        try (PreparedStatement insertDefinition = connection.prepareStatement(
                "INSERT INTO lore_definitions("
                        + "definition_id, lookup_key, display_name, current_revision, created_at"
                        + ") VALUES (?, ?, ?, ?, ?)")) {
            insertDefinition.setString(1, DEFINITION_ID);
            insertDefinition.setString(2, "migration-test");
            insertDefinition.setString(3, "Migration Test");
            insertDefinition.setLong(4, 1L);
            insertDefinition.setLong(5, 1L);
            insertDefinition.executeUpdate();
        }
        try (PreparedStatement insertRevision = connection.prepareStatement(
                "INSERT INTO lore_definition_revisions("
                        + "definition_id, revision, codec_version, template_blob, created_at"
                        + ") VALUES (?, ?, ?, ?, ?)")) {
            insertRevision.setString(1, DEFINITION_ID);
            insertRevision.setLong(2, 1L);
            insertRevision.setInt(3, 1);
            insertRevision.setBytes(4, new byte[] {1});
            insertRevision.setLong(5, 1L);
            insertRevision.executeUpdate();
        }
        try (PreparedStatement insertInstance = connection.prepareStatement(
                "INSERT INTO lore_instances("
                        + "instance_id, definition_id, applied_revision, desired_revision, "
                        + "lifecycle_state, created_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?)")) {
            insertInstance.setString(1, INSTANCE_ID);
            insertInstance.setString(2, DEFINITION_ID);
            insertInstance.setLong(3, 1L);
            insertInstance.setLong(4, 1L);
            insertInstance.setString(5, "ACTIVE");
            insertInstance.setLong(6, 1L);
            insertInstance.executeUpdate();
        }
    }

    private static void insertTemplateUpdate(
            Connection connection, String mutationId, String state, long updatedAt)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO pending_mutations("
                        + "mutation_id, mutation_type, definition_id, instance_id, "
                        + "desired_revision, state, attempt_count, created_at, updated_at"
                        + ") VALUES (?, 'TEMPLATE_UPDATE', ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, mutationId);
            insert.setString(2, DEFINITION_ID);
            insert.setString(3, INSTANCE_ID);
            insert.setLong(4, 1L);
            insert.setString(5, state);
            insert.setInt(6, 0);
            insert.setLong(7, 1_000L);
            insert.setLong(8, updatedAt);
            insert.executeUpdate();
        }
    }

    private static void insertMutationWithState(
            Connection connection, String mutationId, String state) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO pending_mutations("
                        + "mutation_id, mutation_type, state, attempt_count, created_at, updated_at"
                        + ") VALUES (?, 'TEMPLATE_UPDATE', ?, 0, 1, 1)")) {
            insert.setString(1, mutationId);
            insert.setString(2, state);
            insert.executeUpdate();
        }
    }

    private static int countMutationState(Connection connection, String state) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM pending_mutations WHERE state = ?")) {
            query.setString(1, state);
            try (ResultSet resultSet = query.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static int countTemplateUpdates(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM pending_mutations "
                             + "WHERE mutation_type = 'TEMPLATE_UPDATE' "
                             + "AND instance_id = ? AND desired_revision = 1")) {
            query.setString(1, INSTANCE_ID);
            try (ResultSet resultSet = query.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static String findTemplateUpdateMutationId(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                     "SELECT mutation_id FROM pending_mutations "
                             + "WHERE mutation_type = 'TEMPLATE_UPDATE' "
                             + "AND instance_id = ? AND desired_revision = 1")) {
            query.setString(1, INSTANCE_ID);
            try (ResultSet resultSet = query.executeQuery()) {
                resultSet.next();
                return resultSet.getString(1);
            }
        }
    }

    private static int countSchemaHistory(Connection connection) throws SQLException {
        try (PreparedStatement query =
                     connection.prepareStatement("SELECT COUNT(*) FROM schema_history");
             ResultSet resultSet = query.executeQuery()) {
            resultSet.next();
            return resultSet.getInt(1);
        }
    }

    private static int countTable(Connection connection, String tableName) throws SQLException {
        return countSchemaObject(connection, "table", tableName);
    }

    private static int countIndex(Connection connection, String indexName) throws SQLException {
        return countSchemaObject(connection, "index", indexName);
    }

    private static int countSchemaObject(
            Connection connection, String type, String name) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                     "SELECT COUNT(*) FROM sqlite_master WHERE type = ? AND name = ?")) {
            query.setString(1, type);
            query.setString(2, name);
            try (ResultSet resultSet = query.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private static void insertExternalRequest(Connection connection, String operationId)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO external_delivery_requests("
                        + "external_operation_id, definition_key, player_id, outcome, created_at, updated_at"
                        + ") VALUES (?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, operationId);
            insert.setString(2, "example");
            insert.setString(3, "00000000-0000-0000-0000-000000000001");
            insert.setString(4, "ACCEPTED_QUEUED");
            insert.setLong(5, 1L);
            insert.setLong(6, 1L);
            insert.executeUpdate();
        }
    }
}
