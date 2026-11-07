package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Read-only conflict screen over existing LoreItems tables; never grants custody or delivery. */
final class SQLiteCrownIdentityAudit {
    enum Finding {
        DEFINITION_UNAVAILABLE, EXPECTED_INSTANCE_UNAVAILABLE, UNEXPECTED_ACTIVE_INSTANCE, MULTIPLE_ACTIVE_INSTANCES,
        UNRESOLVED_IDENTITY_ANOMALY, PENDING_DELIVERY, EXTERNAL_DELIVERY_HISTORY, OPEN_DISTRIBUTION,
        PENDING_MUTATION, DESTRUCTIVE_OPERATION, LOCATION_UNCERTAIN, LIVE_PHYSICAL_PROOF_REQUIRED
    }

    private final SQLiteStorageRuntime storage;

    SQLiteCrownIdentityAudit(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    CompletionStage<Set<Finding>> inspect(UUID definition, UUID instance) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(instance, "instance");
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            try {
                Set<Finding> result = inspect(connection, definition.toString(), instance.toString());
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        });
    }

    static Set<Finding> inspect(Connection c, String definition, String instance) throws SQLException {
        EnumSet<Finding> findings = EnumSet.of(Finding.LIVE_PHYSICAL_PROOF_REQUIRED);
        inspectIdentity(c, definition, instance, findings);
        inspectOpenWork(c, definition, instance, findings);
        if (!exists(c.prepareStatement("SELECT 1 FROM instance_current_state WHERE instance_id=? AND state='CONFIRMED_NOW'"), instance)) {
            findings.add(Finding.LOCATION_UNCERTAIN);
        }
        return Collections.unmodifiableSet(findings);
    }

    private static void inspectIdentity(Connection c, String definition, String instance, Set<Finding> findings) throws SQLException {
        if (!exists(c.prepareStatement("SELECT 1 FROM lore_definitions WHERE definition_id=? AND deleted_at IS NULL"), definition)) {
            findings.add(Finding.DEFINITION_UNAVAILABLE);
        }
        boolean expectedActive = exists(c.prepareStatement("SELECT 1 FROM lore_instances WHERE definition_id=? AND instance_id=? AND lifecycle_state='ACTIVE'"), definition, instance);
        if (!expectedActive) {
            findings.add(Finding.EXPECTED_INSTANCE_UNAVAILABLE);
        }
        if (exists(c.prepareStatement("SELECT 1 FROM lore_instances WHERE definition_id=? AND lifecycle_state='ACTIVE' AND instance_id<>? LIMIT 1"), definition, instance)) {
            if (!expectedActive) findings.add(Finding.UNEXPECTED_ACTIVE_INSTANCE);
            if (expectedActive || exists(c.prepareStatement("SELECT 1 FROM lore_instances WHERE definition_id=? AND lifecycle_state='ACTIVE' LIMIT 1 OFFSET 1"), definition)) {
                findings.add(Finding.MULTIPLE_ACTIVE_INSTANCES);
            }
        }
        if (exists(c.prepareStatement("SELECT 1 FROM instance_anomalies WHERE definition_id=? AND status IN ('OPEN','ACKNOWLEDGED') LIMIT 1"), definition)) {
            findings.add(Finding.UNRESOLVED_IDENTITY_ANOMALY);
        }
    }

    /** Deliveries, distributions, mutations and destructive operations that could still move or change the item. */
    private static void inspectOpenWork(Connection c, String definition, String instance, Set<Finding> findings) throws SQLException {
        if (exists(c.prepareStatement("SELECT 1 FROM direct_deliveries d JOIN lore_instances i ON i.instance_id=d.instance_id "
                + "WHERE i.definition_id=? AND d.state NOT IN ('COMPLETED','CANCELLED') LIMIT 1"), definition)) {
            findings.add(Finding.PENDING_DELIVERY);
        }
        if (exists(c.prepareStatement("SELECT 1 FROM external_delivery_requests r JOIN lore_definitions d ON d.lookup_key=r.definition_key "
                + "WHERE d.definition_id=? LIMIT 1"), definition)) {
            findings.add(Finding.EXTERNAL_DELIVERY_HISTORY);
        }
        if (exists(c.prepareStatement("SELECT 1 FROM distribution_campaigns c WHERE c.definition_id=? AND "
                + "(c.state IN ('DRAFT','ACTIVE','PAUSED') OR EXISTS (SELECT 1 FROM distribution_recipients r "
                + "WHERE r.campaign_id=c.campaign_id AND r.state NOT IN ('DELIVERED','CANCELLED'))) LIMIT 1"), definition)) {
            findings.add(Finding.OPEN_DISTRIBUTION);
        }
        if (exists(c.prepareStatement("SELECT 1 FROM pending_mutations m WHERE (m.definition_id=? OR m.instance_id=?) "
                + "AND m.state NOT IN ('COMPLETED','CANCELLED') LIMIT 1"), definition, instance)) {
            findings.add(Finding.PENDING_MUTATION);
        }
        if (exists(c.prepareStatement("SELECT 1 FROM destructive_operations o WHERE o.definition_id=? AND "
                + "(o.state IN ('ACTIVE','PAUSED') OR EXISTS (SELECT 1 FROM destructive_targets t "
                + "WHERE t.operation_id=o.operation_id AND (t.state NOT IN ('COMPLETED','ABORTED') "
                + "OR t.effect_state IN ('UNKNOWN','AMBIGUOUS')))) LIMIT 1"), definition)) {
            findings.add(Finding.DESTRUCTIVE_OPERATION);
        }
    }

    private static boolean exists(PreparedStatement query, String... values) throws SQLException {
        try (query) {
            for (int i = 0; i < values.length; i++) query.setString(i + 1, values[i]);
            try (ResultSet rows = query.executeQuery()) { return rows.next(); }
        }
    }
}
