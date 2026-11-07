package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

final class SQLiteSchemaHealthVerifier {
    private static final String SQLITE_OK = "ok";
    private static final List<SchemaObject> REQUIRED_OBJECTS = List.of(
            table("schema_history"),
            table("crown_reclamation"),
            table("crown_reclamation_escrow"),
            table("crown_recovery_delivery"),
            table("crown_custody"),
            table("crown_custody_audit"),
            table("crown_helmet_evidence"),
            table("lore_definitions"),
            table("lore_definition_revisions"),
            table("lore_instances"),
            table("instance_observations"),
            table("instance_current_state"),
            table("instance_anomalies"),
            table("pending_mutations"),
            table("direct_deliveries"),
            table("distribution_campaigns"),
            table("distribution_recipients"),
            table("external_delivery_requests"),
            table("deleted_definition_markers"),
            table("audit_events"),
            table("template_edit_confirmations"),
            table("destructive_operations"),
            table("destructive_targets"),
            table("distribution_campaign_revision_snapshots"),
            index("uq_active_definition_lookup_key"),
            index("uq_anomalies_active_identity"),
            index("uq_template_update_instance_revision"),
            index("uq_distribution_recipient_player"),
            index("uq_distribution_recipient_instance"),
            index("uq_destructive_target_active_instance"),
            trigger("distribution_campaign_identity_is_immutable"),
            trigger("distribution_recipient_snapshot_is_immutable"),
            trigger("distribution_recipient_requires_draft_campaign"),
            trigger("deleted_definition_marker_is_immutable"),
            trigger("deleted_definition_marker_cannot_be_deleted"),
            trigger("destructive_operation_identity_is_immutable"),
            trigger("destructive_target_identity_is_immutable"),
            trigger("distribution_campaign_revision_is_immutable"),
            trigger("distribution_campaign_revision_cannot_be_deleted"),
            trigger("canonicalize_player_inventory_observation_insert"),
            trigger("canonicalize_player_inventory_current_insert"),
            trigger("canonicalize_player_inventory_current_update"));
    private static final List<SchemaDefinition> REQUIRED_SCHEMA_DEFINITIONS = List.of(
            indexDefinition(
                    "uq_active_definition_lookup_key",
                    "CREATE UNIQUE INDEX uq_active_definition_lookup_key "
                            + "ON lore_definitions(lookup_key) WHERE deleted_at IS NULL"),
            indexDefinition(
                    "uq_anomalies_active_identity",
                    "CREATE UNIQUE INDEX uq_anomalies_active_identity "
                            + "ON instance_anomalies(anomaly_type, COALESCE(instance_id, ''), definition_id) "
                            + "WHERE status IN ('OPEN', 'ACKNOWLEDGED')"),
            indexDefinition(
                    "uq_template_update_instance_revision",
                    "CREATE UNIQUE INDEX uq_template_update_instance_revision "
                            + "ON pending_mutations(instance_id, desired_revision) "
                            + "WHERE mutation_type = 'TEMPLATE_UPDATE' "
                            + "AND instance_id IS NOT NULL AND desired_revision IS NOT NULL"),
            indexDefinition(
                    "uq_distribution_recipient_player",
                    "CREATE UNIQUE INDEX uq_distribution_recipient_player "
                            + "ON distribution_recipients(campaign_id, player_id) "
                            + "WHERE player_id IS NOT NULL"),
            indexDefinition(
                    "uq_distribution_recipient_instance",
                    "CREATE UNIQUE INDEX uq_distribution_recipient_instance "
                            + "ON distribution_recipients(instance_id) WHERE instance_id IS NOT NULL"),
            indexDefinition(
                    "uq_destructive_target_active_instance",
                    "CREATE UNIQUE INDEX uq_destructive_target_active_instance "
                            + "ON destructive_targets(instance_id) "
                            + "WHERE state NOT IN ('COMPLETED', 'ABORTED')"),
            triggerDefinition(
                    "distribution_campaign_identity_is_immutable",
                    "CREATE TRIGGER distribution_campaign_identity_is_immutable "
                            + "BEFORE UPDATE OF campaign_id, source_fingerprint, source_name, display_name, definition_id, created_at "
                            + "ON distribution_campaigns BEGIN "
                            + "SELECT RAISE(ABORT, 'distribution campaign identity is immutable'); END"),
            triggerDefinition(
                    "distribution_recipient_snapshot_is_immutable",
                    "CREATE TRIGGER distribution_recipient_snapshot_is_immutable "
                            + "BEFORE UPDATE OF campaign_id, recipient_key, snapshot_index, original_value "
                            + "ON distribution_recipients BEGIN "
                            + "SELECT RAISE(ABORT, 'distribution recipient snapshot is immutable'); END"),
            triggerDefinition(
                    "distribution_recipient_requires_draft_campaign",
                    "CREATE TRIGGER distribution_recipient_requires_draft_campaign "
                            + "BEFORE INSERT ON distribution_recipients WHEN NOT EXISTS ("
                            + "SELECT 1 FROM distribution_campaigns campaign "
                            + "WHERE campaign.campaign_id = NEW.campaign_id AND campaign.state = 'DRAFT'"
                            + ") BEGIN SELECT RAISE(ABORT, 'distribution recipient snapshot is sealed'); END"),
            triggerDefinition(
                    "deleted_definition_marker_is_immutable",
                    "CREATE TRIGGER deleted_definition_marker_is_immutable "
                            + "BEFORE UPDATE ON deleted_definition_markers BEGIN "
                            + "SELECT RAISE(ABORT, 'deleted definition marker is immutable'); END"),
            triggerDefinition(
                    "deleted_definition_marker_cannot_be_deleted",
                    "CREATE TRIGGER deleted_definition_marker_cannot_be_deleted "
                            + "BEFORE DELETE ON deleted_definition_markers BEGIN "
                            + "SELECT RAISE(ABORT, 'deleted definition marker cannot be deleted'); END"),
            triggerDefinition(
                    "destructive_operation_identity_is_immutable",
                    "CREATE TRIGGER destructive_operation_identity_is_immutable "
                            + "BEFORE UPDATE OF operation_id, operation_type, definition_id, exact_instance_id, "
                            + "expected_revision, actor_id, idempotency_key, confirmation_token, accepted_at "
                            + "ON destructive_operations BEGIN "
                            + "SELECT RAISE(ABORT, 'destructive operation identity is immutable'); END"),
            triggerDefinition(
                    "destructive_target_identity_is_immutable",
                    "CREATE TRIGGER destructive_target_identity_is_immutable "
                            + "BEFORE UPDATE OF operation_id, instance_id, definition_id, expected_applied_revision, "
                            + "expected_location_type, expected_location_key, expected_container_path, created_at "
                            + "ON destructive_targets BEGIN "
                            + "SELECT RAISE(ABORT, 'destructive target snapshot identity is immutable'); END"),
            triggerDefinition(
                    "distribution_campaign_revision_is_immutable",
                    "CREATE TRIGGER distribution_campaign_revision_is_immutable "
                            + "BEFORE UPDATE ON distribution_campaign_revision_snapshots BEGIN "
                            + "SELECT RAISE(ABORT, 'distribution campaign revision snapshot is immutable'); END"),
            triggerDefinition(
                    "distribution_campaign_revision_cannot_be_deleted",
                    "CREATE TRIGGER distribution_campaign_revision_cannot_be_deleted "
                            + "BEFORE DELETE ON distribution_campaign_revision_snapshots BEGIN "
                            + "SELECT RAISE(ABORT, 'distribution campaign revision snapshot cannot be deleted'); END"),
            triggerDefinition(
                    "canonicalize_player_inventory_observation_insert",
                    "CREATE TRIGGER canonicalize_player_inventory_observation_insert "
                            + "AFTER INSERT ON instance_observations "
                            + "WHEN NEW.location_type = 'PLAYER_INVENTORY' "
                            + "AND NEW.location_key NOT LIKE 'player:%' BEGIN "
                            + "UPDATE instance_observations SET location_key = 'player:' || NEW.location_key "
                            + "WHERE observation_id = NEW.observation_id; END"),
            triggerDefinition(
                    "canonicalize_player_inventory_current_insert",
                    "CREATE TRIGGER canonicalize_player_inventory_current_insert "
                            + "AFTER INSERT ON instance_current_state "
                            + "WHEN NEW.location_type = 'PLAYER_INVENTORY' "
                            + "AND NEW.location_key NOT LIKE 'player:%' BEGIN "
                            + "UPDATE instance_current_state SET location_key = 'player:' || NEW.location_key "
                            + "WHERE instance_id = NEW.instance_id; END"),
            triggerDefinition(
                    "canonicalize_player_inventory_current_update",
                    "CREATE TRIGGER canonicalize_player_inventory_current_update "
                            + "AFTER UPDATE OF location_type, location_key ON instance_current_state "
                            + "WHEN NEW.location_type = 'PLAYER_INVENTORY' "
                            + "AND NEW.location_key NOT LIKE 'player:%' BEGIN "
                            + "UPDATE instance_current_state SET location_key = 'player:' || NEW.location_key "
                            + "WHERE instance_id = NEW.instance_id; END"));

    private SQLiteSchemaHealthVerifier() {
    }

    static void verify(Connection connection) throws SQLException {
        verifyQuickCheck(connection);
        verifyForeignKeys(connection);
        verifyRequiredObjects(connection);
        verifyRequiredDefinitions(connection);
    }

    private static void verifyQuickCheck(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("PRAGMA quick_check(1)")) {
            if (!resultSet.next() || !SQLITE_OK.equalsIgnoreCase(resultSet.getString(1))) {
                throw new SQLException("SQLite quick_check reported database corruption");
            }
        }
    }

    private static void verifyForeignKeys(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (resultSet.next()) {
                throw new SQLException(
                        "SQLite foreign_key_check reported invalid durable references in table "
                                + resultSet.getString("table"));
            }
        }
    }

    private static void verifyRequiredObjects(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM sqlite_master WHERE type = ? AND name = ?")) {
            for (SchemaObject object : REQUIRED_OBJECTS) {
                statement.setString(1, object.type());
                statement.setString(2, object.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new SQLException(
                                "SQLite schema is missing required " + object.type()
                                        + " " + object.name());
                    }
                }
            }
        }
    }

    private static void verifyRequiredDefinitions(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sql FROM sqlite_master WHERE type = ? AND name = ?")) {
            for (SchemaDefinition definition : REQUIRED_SCHEMA_DEFINITIONS) {
                statement.setString(1, definition.type());
                statement.setString(2, definition.name());
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new SQLException(
                                "SQLite schema is missing required " + definition.type()
                                        + " " + definition.name());
                    }
                    String actualSql = resultSet.getString("sql");
                    if (!normalizeSql(definition.sql()).equals(normalizeSql(actualSql))) {
                        throw new SQLException(
                                "SQLite schema has an invalid required " + definition.type()
                                        + " " + definition.name());
                    }
                }
            }
        }
    }

    private static String normalizeSql(String sql) {
        if (sql == null) {
            return "";
        }
        String normalized = sql.strip()
                .replaceAll("\\s+", " ")
                .replaceAll("\\s*([(),])\\s*", "$1")
                .toLowerCase(Locale.ROOT);
        return normalized.endsWith(";")
                ? normalized.substring(0, normalized.length() - 1).stripTrailing()
                : normalized;
    }

    private static SchemaObject table(String name) {
        return new SchemaObject("table", name);
    }

    private static SchemaObject index(String name) {
        return new SchemaObject("index", name);
    }

    private static SchemaObject trigger(String name) {
        return new SchemaObject("trigger", name);
    }

    private static SchemaDefinition indexDefinition(String name, String sql) {
        return new SchemaDefinition("index", name, sql);
    }

    private static SchemaDefinition triggerDefinition(String name, String sql) {
        return new SchemaDefinition("trigger", name, sql);
    }

    private record SchemaObject(String type, String name) {}

    private record SchemaDefinition(String type, String name, String sql) {}
}
