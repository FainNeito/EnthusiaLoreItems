package net.enthusia.loreitems.sqlite;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class MigrationRunner {
    private static final String CREATE_TRIGGER = "CREATE TRIGGER";
    private static final List<Migration> MIGRATIONS = List.of(
            new Migration(1, "foundation", "db/migration/V1__foundation.sql"),
            new Migration(2, "template revision rollout", "db/migration/V2__template_revision_rollout.sql"),
            new Migration(3, "mutation queue controls", "db/migration/V3__mutation_queue_controls.sql"),
            new Migration(4, "template editor confirmations", "db/migration/V4__template_editor_confirmations.sql"),
            new Migration(5, "destructive administration", "db/migration/V5__destructive_administration.sql"),
            new Migration(6, "mass distribution recipient states", "db/migration/V6__mass_distribution_recipient_states.sql"),
            new Migration(7, "mass distribution revision snapshot", "db/migration/V7__mass_distribution_revision_snapshot.sql"),
            new Migration(8, "canonicalize adopted player locations", "db/migration/V8__canonicalize_adopted_player_locations.sql"),
            new Migration(9, "canonicalize player inventory locations", "db/migration/V9__canonicalize_player_inventory_locations.sql"),
            new Migration(10, "item display observation support", "db/migration/V10__item_display_observation_support.sql"),
            new Migration(11, "reconcile terminal void destructive targets", "db/migration/V11__reconcile_void_destroyed_destructive_targets.sql"),
            new Migration(12, "Crown helmet custody", "db/migration/V12__crown_custody.sql"),
            new Migration(13, "Crown PvP succession requests", "db/migration/V13__crown_succession.sql"),
            new Migration(14, "Crown handoff escrow", "db/migration/V14__crown_handoff.sql"),
            new Migration(15, "Crown successor receipts", "db/migration/V15__crown_successor.sql"),
            new Migration(16, "Crown reclamation reservations", "db/migration/V16__crown_reclamation.sql"),
            new Migration(17, "Crown reclamation escrow", "db/migration/V17__crown_reclamation_escrow.sql"),
            new Migration(18, "Crown recovery delivery", "db/migration/V18__crown_recovery_delivery.sql"));
    private static final int LATEST_SCHEMA_VERSION = MIGRATIONS.getLast().version();

    public void migrate(Connection connection) throws SQLException {
        migrateThrough(connection, LATEST_SCHEMA_VERSION);
    }

    void migrateThrough(Connection connection, int targetVersion) throws SQLException {
        if (targetVersion < 1 || targetVersion > LATEST_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported schema version: " + targetVersion);
        }
        ensureHistoryTable(connection);
        rejectFutureSchemaVersion(connection, targetVersion);
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (Migration migration : MIGRATIONS) {
                if (migration.version() > targetVersion) {
                    break;
                }
                applyIfMissing(connection, migration);
            }
            if (targetVersion == LATEST_SCHEMA_VERSION) {
                SQLiteSchemaHealthVerifier.verify(connection);
            }
        } catch (SQLException | RuntimeException exception) {
            rollbackAfterFailure(connection, exception);
            restoreAutoCommitAfterFailure(connection, previousAutoCommit, exception);
            throw exception;
        }
        restoreAutoCommitAfterCommittedSuccess(connection, previousAutoCommit);
    }

    private static void applyIfMissing(Connection connection, Migration migration)
            throws SQLException {
        if (isApplied(connection, migration.version())) {
            return;
        }
        executeScript(connection, readResource(migration.resource()));
        insertHistory(connection, migration);
        connection.commit();
    }

    private static void insertHistory(Connection connection, Migration migration)
            throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO schema_history(version, description, applied_at) VALUES (?, ?, ?)")) {
            insert.setInt(1, migration.version());
            insert.setString(2, migration.description());
            insert.setLong(3, System.currentTimeMillis());
            insert.executeUpdate();
        }
    }

    private static void rollbackAfterFailure(Connection connection, Exception failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void restoreAutoCommitAfterFailure(
            Connection connection, boolean previousAutoCommit, Exception failure) {
        try {
            connection.setAutoCommit(previousAutoCommit);
        } catch (SQLException restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
    }

    private static void restoreAutoCommitAfterCommittedSuccess(
            Connection connection, boolean previousAutoCommit) {
        try {
            connection.setAutoCommit(previousAutoCommit);
        } catch (SQLException ignored) {
            // Every migration and schema-history row has already committed independently.
            // Cleanup failure must not turn durable success into a false migration failure.
            // The storage bootstrap owns and immediately closes this connection.
        }
    }

    private static void ensureHistoryTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS schema_history ("
                            + "version INTEGER PRIMARY KEY,"
                            + "description TEXT NOT NULL,"
                            + "applied_at INTEGER NOT NULL)");
        }
    }

    private static void rejectFutureSchemaVersion(Connection connection, int targetVersion)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                        "SELECT MAX(version) FROM schema_history");
                ResultSet resultSet = query.executeQuery()) {
            if (!resultSet.next()) {
                throw new SQLException("SQLite did not return the schema history version");
            }
            int appliedVersion = resultSet.getInt(1);
            if (!resultSet.wasNull() && appliedVersion > targetVersion) {
                throw new SQLException(
                        "Database schema version " + appliedVersion
                                + " is newer than supported version " + targetVersion);
            }
        }
    }

    private static boolean isApplied(Connection connection, int version) throws SQLException {
        try (PreparedStatement query =
                     connection.prepareStatement("SELECT 1 FROM schema_history WHERE version = ?")) {
            query.setInt(1, version);
            try (ResultSet resultSet = query.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static String readResource(String resource) {
        try (InputStream stream = MigrationRunner.class.getResourceAsStream("/" + resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing migration resource: " + resource);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read migration resource: " + resource, exception);
        }
    }

    private static void executeScript(Connection connection, String script) throws SQLException {
        for (String statementText : splitStatements(script)) {
            try (Statement statement = connection.createStatement()) {
                statement.execute(statementText); // nosemgrep
            }
        }
    }

    private static List<String> splitStatements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean triggerStatement = false;

        for (String line : script.split("\\R", -1)) {
            String trimmed = line.strip();
            if (current.length() == 0 && trimmed.isEmpty()) {
                continue;
            }
            if (current.length() == 0) {
                triggerStatement = trimmed.regionMatches(
                        true, 0, CREATE_TRIGGER, 0, CREATE_TRIGGER.length());
            }
            current.append(line).append('\n');

            boolean complete = triggerStatement
                    ? trimmed.equalsIgnoreCase("END;")
                    : trimmed.endsWith(";");
            if (complete) {
                statements.add(current.toString().strip());
                current.setLength(0);
                triggerStatement = false;
            }
        }

        if (!current.toString().isBlank()) {
            throw new IllegalArgumentException("Migration contains an incomplete SQL statement");
        }
        return List.copyOf(statements);
    }

    private record Migration(int version, String description, String resource) {}
}
