package net.enthusia.loreitems.sqlite;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;
import net.enthusia.loreitems.domain.ExclusiveCustodyReservation;

/**
 * Read and audit checks over the Crown custody journal tables, shared by the journal and the helmet
 * evidence store. Every check fails closed: a missing or inconsistent row throws or returns false.
 */
final class SQLiteCustodyEvidence {
    static final int MAX_HELMET_EVIDENCE_BYTES = 1024 * 1024;

    private SQLiteCustodyEvidence() {}

    static String fingerprint(byte[] evidence) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(evidence));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    static void bindIdentity(PreparedStatement statement, ExclusiveCustodyReservation request) throws SQLException {
        statement.setString(1, request.operationId().toString());
        statement.setString(2, request.instanceId().toString());
        statement.setString(3, request.holderId().toString());
        statement.setLong(4, request.generation());
    }

    static void requireNoOrphanEvidence(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM crown_custody_audit UNION ALL SELECT 1 FROM crown_helmet_evidence LIMIT 1");
             ResultSet rows = query.executeQuery()) {
            if (rows.next()) throw new SQLException("Orphaned Crown evidence requires review");
        }
    }

    static void verifyHelmetEvidenceIfPresent(Connection connection, ExclusiveCustodyReservation request)
            throws SQLException {
        requireNoForeignHelmetEvidence(connection, request);
        boolean present;
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT instance_id,holder_id,generation,payload,payload_sha256,is_empty FROM crown_helmet_evidence WHERE operation_id=?")) {
            query.setString(1, request.operationId().toString());
            try (ResultSet row = query.executeQuery()) {
                present = row.next();
                if (present) requireConsistentEvidence(row, request);
            }
        }
        if (present) {
            requireAudit(connection, request, "HELMET_EVIDENCE");
        } else {
            requireNoEvidenceAudit(connection, request);
        }
    }

    /** Column reads keep their original order, so each {@code wasNull} refers to the preceding read. */
    private static void requireConsistentEvidence(ResultSet row, ExclusiveCustodyReservation request) throws SQLException {
        byte[] bytes = row.getBytes(4);
        int empty = row.getInt(6);
        if (row.wasNull() || (empty != 0 && empty != 1)) throw new SQLException("Invalid empty-helmet evidence marker");
        if (!request.instanceId().toString().equals(row.getString(1))
                || !request.holderId().toString().equals(row.getString(2))
                || request.generation() != row.getLong(3) || row.wasNull()
                || !validPayload(bytes, empty, row.getString(5))) {
            throw new SQLException("Helmet evidence is invalid or inconsistent");
        }
    }

    private static boolean validPayload(byte[] bytes, int empty, String digest) {
        return bytes != null && (bytes.length == 0) == (empty == 1) && bytes.length <= MAX_HELMET_EVIDENCE_BYTES
                && fingerprint(bytes).equals(digest);
    }

    private static void requireNoEvidenceAudit(Connection connection, ExclusiveCustodyReservation request) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM crown_custody_audit WHERE operation_id=? AND transition='HELMET_EVIDENCE'")) {
            query.setString(1, request.operationId().toString());
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) throw new SQLException("Helmet evidence is missing");
            }
        }
    }

    static void requireNoForeignHelmetEvidence(Connection connection, ExclusiveCustodyReservation request)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM crown_helmet_evidence WHERE operation_id<>? "
                + "UNION ALL SELECT 1 FROM crown_custody_audit "
                + "WHERE operation_id<>? OR instance_id<>? OR holder_id<>? OR generation<>? "
                + "OR transition NOT IN ('RESERVED','FIRST_ISSUE','HELMET_EVIDENCE','REVIEW_REQUIRED') LIMIT 1")) {
            query.setString(1, request.operationId().toString());
            query.setString(2, request.operationId().toString());
            query.setString(3, request.instanceId().toString());
            query.setString(4, request.holderId().toString());
            query.setLong(5, request.generation());
            try (ResultSet rows = query.executeQuery()) {
                if (rows.next()) throw new SQLException("Unexpected Crown helmet evidence requires review");
            }
        }
    }

    static boolean firstIssue(Connection connection, ExclusiveCustodyReservation request) throws SQLException {
        try (var query = connection.prepareStatement("SELECT 1 FROM crown_custody_audit WHERE transition='FIRST_ISSUE'");
             var rows = query.executeQuery()) {
            if (!rows.next()) return false;
            requireAudit(connection, request, "FIRST_ISSUE");
            return true;
        }
    }

    static java.util.Optional<String> equippedDigest(Connection connection, ExclusiveCustodyReservation request)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT e.payload_sha256,e.is_empty FROM crown_custody s JOIN crown_helmet_evidence e "
                + "USING(operation_id,instance_id,holder_id,generation) WHERE singleton=1 AND operation_id=? AND instance_id=? "
                + "AND holder_id=? AND generation=? AND state='EQUIPPED' AND e.payload=X'' "
                + "AND (SELECT count(*) FROM crown_custody_audit)=? "
                + "AND (SELECT count(*) FROM crown_helmet_evidence)=1")) {
            bindIdentity(query, request);
            query.setInt(5, firstIssue(connection, request) ? 4 : 3);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) return java.util.Optional.empty();
                String digest = rows.getString(1);
                int empty = rows.getInt(2);
                if (rows.wasNull() || (empty != 0 && empty != 1) || digest == null || !digest.matches("[0-9a-f]{64}")
                        || fingerprint(new byte[0]).equals(digest) != (empty == 1)) throw new SQLException("Invalid equipped digest");
                requireAudit(connection, request, "RESERVED");
                requireAudit(connection, request, "HELMET_EVIDENCE");
                requireAudit(connection, request, "EQUIPPED");
                return java.util.Optional.of(digest);
            }
        }
    }

    /** A retry only confirms the same durable hold; it never releases or transfers custody. */
    static boolean isExactReviewHold(Connection connection, ExclusiveCustodyReservation request)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM crown_custody WHERE singleton=1 AND operation_id=? "
                + "AND instance_id=? AND holder_id=? AND generation=? AND state='REVIEW_REQUIRED'")) {
            query.setString(1, request.operationId().toString());
            query.setString(2, request.instanceId().toString());
            query.setString(3, request.holderId().toString());
            query.setLong(4, request.generation());
            try (ResultSet row = query.executeQuery()) { return row.next(); }
        }
    }

    static boolean hasReservation(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement("SELECT 1 FROM crown_custody WHERE singleton=1");
             ResultSet rows = query.executeQuery()) {
            return rows.next();
        }
    }

    static void requireAudit(Connection connection, ExclusiveCustodyReservation request, String transition)
            throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT 1 FROM crown_custody_audit WHERE operation_id=? AND transition=? "
                + "AND instance_id=? AND holder_id=? AND generation=?")) {
            query.setString(1, request.operationId().toString());
            query.setString(2, transition);
            query.setString(3, request.instanceId().toString());
            query.setString(4, request.holderId().toString());
            query.setLong(5, request.generation());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new SQLException("Custody audit is missing or inconsistent");
            }
        }
    }

    static void appendAudit(Connection connection, ExclusiveCustodyReservation request, String transition)
            throws SQLException {
        try (PreparedStatement audit = connection.prepareStatement(
                "INSERT INTO crown_custody_audit "
                + "(operation_id,transition,instance_id,holder_id,generation,recorded_at) VALUES (?,?,?,?,?,?)")) {
            audit.setString(1, request.operationId().toString());
            audit.setString(2, transition);
            audit.setString(3, request.instanceId().toString());
            audit.setString(4, request.holderId().toString());
            audit.setLong(5, request.generation());
            audit.setLong(6, System.currentTimeMillis());
            if (audit.executeUpdate() != 1) throw new SQLException("Custody audit was not recorded");
        }
    }
}
