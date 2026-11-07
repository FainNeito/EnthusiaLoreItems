package net.enthusia.loreitems.sqlite;

import static net.enthusia.loreitems.sqlite.SQLiteCustodyEvidence.*;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;
import java.util.Arrays;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.domain.ExclusiveCustodyReservation;

/**
 * Experimental journal over a separately provisioned schema. Not registered with the plugin.
 * A reservation does not assert possession or authorize a physical item operation.
 */
final class SQLiteExclusiveCustodyJournal {
    enum Result { RESERVED, REPLAY, REVIEW_REQUIRED, CONFLICT }
    enum RecoveryResult { NO_RESERVATION, AWAITING_FIRST_JOIN, REVIEW_REQUIRED, ALREADY_IN_REVIEW, EQUIPPED }
    enum HelmetEvidenceResult { RECORDED, REPLAY, HOLD }

    /**
     * Stores opaque helmet evidence before any inventory mutation. The blob is never returned
     * by this API and is not authority to reconstruct an item or perform an exchange.
     */
    CompletionStage<HelmetEvidenceResult> recordHelmetEvidence(
            SQLiteStorageRuntime storage, ExclusiveCustodyReservation request, byte[] serializedHelmet) {
        return recordHelmetEvidence(storage, request, serializedHelmet, false);
    }

    /** Records a genuinely empty head slot without inventing an item or serialized payload. */
    CompletionStage<HelmetEvidenceResult> recordEmptyHelmetEvidence(
            SQLiteStorageRuntime storage, ExclusiveCustodyReservation request) {
        return recordHelmetEvidence(storage, request, new byte[0], true);
    }

    private CompletionStage<HelmetEvidenceResult> recordHelmetEvidence(
            SQLiteStorageRuntime storage, ExclusiveCustodyReservation request, byte[] serializedHelmet, boolean empty) {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(serializedHelmet, "serializedHelmet");
        if (request.state() != ExclusiveCustodyReservation.State.RESERVED) {
            throw new IllegalArgumentException("Helmet evidence requires a reserved operation");
        }
        if ((serializedHelmet.length == 0) != empty || serializedHelmet.length > MAX_HELMET_EVIDENCE_BYTES) {
            throw new IllegalArgumentException("Invalid helmet evidence size");
        }
        byte[] evidence = serializedHelmet.clone();
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            try {
                HelmetEvidenceResult result = storeHelmetEvidence(connection, request, evidence, empty);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollbackFailure) { failure.addSuppressed(rollbackFailure); }
                throw failure;
            }
        });
    }

    private HelmetEvidenceResult storeHelmetEvidence(Connection connection, ExclusiveCustodyReservation request, byte[] evidence, boolean empty)
            throws SQLException {
        if (!currentlyReserved(connection, request)) return HelmetEvidenceResult.HOLD;
        requireAudit(connection, request, "RESERVED");
        requireNoForeignHelmetEvidence(connection, request);
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO crown_helmet_evidence (operation_id,instance_id,holder_id,generation,payload,payload_sha256,is_empty) VALUES (?,?,?,?,?,?,?)")) {
            insert.setString(1, request.operationId().toString());
            insert.setString(2, request.instanceId().toString());
            insert.setString(3, request.holderId().toString());
            insert.setLong(4, request.generation());
            insert.setBytes(5, evidence);
            insert.setString(6, fingerprint(evidence));
            insert.setInt(7, empty ? 1 : 0);
            if (insert.executeUpdate() == 1) {
                appendAudit(connection, request, "HELMET_EVIDENCE");
                return HelmetEvidenceResult.RECORDED;
            }
        }
        if (!sameStoredEvidence(connection, request, evidence, empty)) return HelmetEvidenceResult.HOLD;
        requireAudit(connection, request, "HELMET_EVIDENCE");
        return HelmetEvidenceResult.REPLAY;
    }

    private static boolean currentlyReserved(Connection connection, ExclusiveCustodyReservation request) throws SQLException {
        try (PreparedStatement current = connection.prepareStatement(
                "SELECT state FROM crown_custody WHERE singleton=1 AND operation_id=? "
                + "AND instance_id=? AND holder_id=? AND generation=?")) {
            current.setString(1, request.operationId().toString());
            current.setString(2, request.instanceId().toString());
            current.setString(3, request.holderId().toString());
            current.setLong(4, request.generation());
            try (ResultSet row = current.executeQuery()) {
                return row.next() && "RESERVED".equals(row.getString(1));
            }
        }
    }

    /** A replay must carry byte-identical evidence, digest and empty marker. */
    private static boolean sameStoredEvidence(Connection connection, ExclusiveCustodyReservation request, byte[] evidence,
            boolean empty) throws SQLException {
        try (PreparedStatement existing = connection.prepareStatement(
                "SELECT payload,payload_sha256,is_empty FROM crown_helmet_evidence WHERE operation_id=? AND instance_id=? AND holder_id=? AND generation=?")) {
            existing.setString(1, request.operationId().toString());
            existing.setString(2, request.instanceId().toString());
            existing.setString(3, request.holderId().toString());
            existing.setLong(4, request.generation());
            try (ResultSet row = existing.executeQuery()) {
                return row.next() && Arrays.equals(evidence, row.getBytes(1)) && fingerprint(evidence).equals(row.getString(2))
                        && row.getInt(3) == (empty ? 1 : 0) && !row.wasNull();
            }
        }
    }

    /**
     * Startup-only recovery, before any worker is admitted. An interrupted reservation
     * goes to review, never to item delivery. Missing evidence and SQL races fail closed.
     */
    CompletionStage<RecoveryResult> recoverInterruptedOnStartup(SQLiteStorageRuntime storage) {
        Objects.requireNonNull(storage, "storage");
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            try {
                RecoveryResult result = recoverInterrupted(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        });
    }

    private RecoveryResult recoverInterrupted(Connection connection) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT operation_id,instance_id,holder_id,generation,state FROM crown_custody WHERE singleton=1");
             ResultSet rows = query.executeQuery()) {
            if (!rows.next()) {
                requireNoOrphanEvidence(connection);
                return RecoveryResult.NO_RESERVATION;
            }
            ExclusiveCustodyReservation request;
            long generation = rows.getLong(4);
            if (rows.wasNull()) throw new SQLException("Missing custody generation");
            try {
                request = new ExclusiveCustodyReservation(UUID.fromString(rows.getString(1)), UUID.fromString(rows.getString(2)),
                        UUID.fromString(rows.getString(3)), generation, ExclusiveCustodyReservation.State.RESERVED);
            } catch (IllegalArgumentException | NullPointerException malformed) {
                throw new SQLException("Invalid custody identity", malformed);
            }
            String state = rows.getString(5);
            requireAudit(connection, request, "RESERVED");
            if ("EQUIPPED".equals(state)) {
                equippedDigest(connection, request).orElseThrow(() -> new SQLException("Missing equipped receipt"));
                return RecoveryResult.EQUIPPED;
            }
            verifyHelmetEvidenceIfPresent(connection, request);
            if ("REVIEW_REQUIRED".equals(state)) {
                requireAudit(connection, request, "REVIEW_REQUIRED");
                return RecoveryResult.ALREADY_IN_REVIEW;
            }
            if ("RESERVED".equals(state) && firstIssue(connection, request)) {
                requireAudit(connection, request, "HELMET_EVIDENCE");
                return RecoveryResult.AWAITING_FIRST_JOIN;
            }
            if (!"RESERVED".equals(state) || !holdForReview(connection, request)) {
                throw new SQLException("Custody state changed or is invalid");
            }
            appendAudit(connection, request, "REVIEW_REQUIRED");
            return RecoveryResult.REVIEW_REQUIRED;
        }
    }

    /** Called only for the first award. A retry never grants permission to materialize another item. */
    CompletionStage<Boolean> reserveFirstIssue(SQLiteStorageRuntime storage, ExclusiveCustodyReservation request,
            UUID definition, long revision, net.enthusia.loreitems.application.EncodedItemTemplate template, byte[] original) {
        byte[] snapshot = original.clone();
        if (revision < 1 || snapshot.length > MAX_HELMET_EVIDENCE_BYTES) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            try {
                if (reserve(connection, request) != Result.RESERVED) return false;
                requireNoOrphanEvidence(connection);
                var findings = SQLiteCrownIdentityAudit.inspect(connection, definition.toString(), request.instanceId().toString());
                if (!findings.equals(java.util.Set.of(SQLiteCrownIdentityAudit.Finding.EXPECTED_INSTANCE_UNAVAILABLE,
                        SQLiteCrownIdentityAudit.Finding.LOCATION_UNCERTAIN,
                        SQLiteCrownIdentityAudit.Finding.LIVE_PHYSICAL_PROOF_REQUIRED))) return false;

                // A partial unique index also fences ordinary mint/distribution writers after commit.
                // The predicate is a canonical java.util.UUID, never player or config text.
                try (var ddl = connection.createStatement()) {
                    ddl.executeUpdate("CREATE UNIQUE INDEX crown_first_issue_definition ON lore_instances(definition_id) "
                            + "WHERE definition_id='" + definition + "'");
                }
                try (var insert = connection.prepareStatement(
                        "INSERT INTO lore_instances(instance_id,definition_id,applied_revision,desired_revision,lifecycle_state,created_at) "
                        + "SELECT ?,d.definition_id,d.current_revision,d.current_revision,'ACTIVE',? FROM lore_definitions d "
                        + "JOIN lore_definition_revisions r ON r.definition_id=d.definition_id AND r.revision=d.current_revision "
                        + "WHERE d.definition_id=? AND d.current_revision=? AND r.codec_version=? AND r.template_blob=? AND d.deleted_at IS NULL "
                        + "AND NOT EXISTS (SELECT 1 FROM lore_instances i JOIN lore_definitions prior ON prior.definition_id=i.definition_id "
                        + "WHERE prior.lookup_key=d.lookup_key)")) {
                    insert.setString(1, request.instanceId().toString());
                    insert.setLong(2, System.currentTimeMillis());
                    insert.setString(3, definition.toString());
                    insert.setLong(4, revision);
                    insert.setInt(5, template.codecVersion());
                    insert.setBytes(6, template.payload());
                    if (insert.executeUpdate() != 1) return false;
                }
                appendAudit(connection, request, "RESERVED");
                if (storeHelmetEvidence(connection, request, snapshot, snapshot.length == 0) != HelmetEvidenceResult.RECORDED) return false;

                appendAudit(connection, request, "FIRST_ISSUE");
                connection.commit();
                return true;
            } finally {
                if (!connection.getAutoCommit()) connection.rollback();
            }
        });
    }
    CompletionStage<Boolean> admitExisting(SQLiteStorageRuntime storage, ExclusiveCustodyReservation request,
            UUID definition, boolean empty, byte[] original) {
        byte[] snapshot = original.clone();
        if (empty != (snapshot.length == 0) || snapshot.length > MAX_HELMET_EVIDENCE_BYTES) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            try {
                if (hasReservation(connection)) return false;
                requireNoOrphanEvidence(connection);
                if (reserve(connection, request) != Result.RESERVED) return false;
                var findings = SQLiteCrownIdentityAudit.inspect(connection, definition.toString(), request.instanceId().toString());
                if (!findings.equals(java.util.Set.of(SQLiteCrownIdentityAudit.Finding.LIVE_PHYSICAL_PROOF_REQUIRED))) return false;
                try (var location = connection.prepareStatement("SELECT 1 FROM instance_current_state "
                        + "WHERE instance_id=? AND location_type='PLAYER_INVENTORY' AND location_key=? AND state='CONFIRMED_NOW'")) {
                    location.setString(1, request.instanceId().toString());
                    location.setString(2, "player:" + request.holderId());
                    try (var rows = location.executeQuery()) { if (!rows.next()) return false; }
                }
                appendAudit(connection, request, "RESERVED");
                if (storeHelmetEvidence(connection, request, snapshot, empty) != HelmetEvidenceResult.RECORDED) return false;
                connection.commit();
                return true;
            } finally {
                if (!connection.getAutoCommit()) connection.rollback();
            }
        });
    }
    /** Returns only after commit; errors remain uncertain and must not be used to mutate an item. */
    CompletionStage<Result> reserveCommitted(SQLiteStorageRuntime storage, ExclusiveCustodyReservation request) {
        Objects.requireNonNull(storage, "storage");
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            try {
                if (!hasReservation(connection)) requireNoOrphanEvidence(connection);
                Result result = reserve(connection, request);
                if (result == Result.RESERVED) appendAudit(connection, request, "RESERVED");
                if (result == Result.REPLAY || result == Result.REVIEW_REQUIRED) {
                    requireAudit(connection, request, "RESERVED");
                    if (result == Result.REVIEW_REQUIRED) requireAudit(connection, request, "REVIEW_REQUIRED");
                    verifyHelmetEvidenceIfPresent(connection, request);
                }
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        });
    }

    /** Read-only agreement with evidence from a reloaded player. Never creates missing evidence. */
    CompletionStage<Boolean> matchesSavedHelmet(SQLiteStorageRuntime storage,
            ExclusiveCustodyReservation request, byte[] original) {
        byte[] snapshot = original.clone();
        if (snapshot.length > MAX_HELMET_EVIDENCE_BYTES
                || request.state() != ExclusiveCustodyReservation.State.RESERVED) {
            return java.util.concurrent.CompletableFuture.completedFuture(false);
        }
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            try (PreparedStatement query = connection.prepareStatement(
                    "SELECT e.payload FROM crown_custody s "
                    + "JOIN crown_helmet_evidence e USING(operation_id,instance_id,holder_id,generation) "
                    + "WHERE s.singleton=1 AND s.operation_id=? AND s.instance_id=? AND s.holder_id=? "
                    + "AND s.generation=? AND s.state='RESERVED' AND NOT EXISTS "
                    + "(SELECT 1 FROM crown_custody_audit WHERE transition='REVIEW_REQUIRED')")) {
                query.setString(1, request.operationId().toString());
                query.setString(2, request.instanceId().toString());
                query.setString(3, request.holderId().toString());
                query.setLong(4, request.generation());
                try (ResultSet row = query.executeQuery()) {
                    if (!row.next() || !Arrays.equals(snapshot, row.getBytes(1))) return false;
                }
                requireAudit(connection, request, "RESERVED");
                verifyHelmetEvidenceIfPresent(connection, request);
                return true;
            } finally {
                connection.rollback();
            }
        });
    }
    /** Only called with a freshly reloaded, exclusively controlled player and their exact staged escrow. */
    CompletionStage<java.util.Optional<String>> commitEquipment(SQLiteStorageRuntime storage,
            ExclusiveCustodyReservation request, byte[] original) {
        return commitEquipment(storage, request, original, null);
    }

    CompletionStage<java.util.Optional<String>> commitEquipment(SQLiteStorageRuntime storage,
            ExclusiveCustodyReservation request, byte[] original, UUID joinedDefinition) {
        byte[] snapshot = original.clone();
        if (request.state() != ExclusiveCustodyReservation.State.RESERVED || snapshot.length > MAX_HELMET_EVIDENCE_BYTES) {
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
        }
        String digest = fingerprint(snapshot);
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            try {
                // Acquire the write lock and fence the decision before examining its evidence.
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE crown_custody SET state='EQUIPPED' WHERE singleton=1 AND "
                        + "operation_id=? AND instance_id=? AND holder_id=? AND generation=? AND state='RESERVED'")) {
                    bindIdentity(update, request);
                    if (update.executeUpdate() == 0) {
                        var replay = equippedDigest(connection, request).filter(digest::equals);
                        connection.rollback();
                        return replay;
                    }
                }
                if (joinedDefinition != null && !firstIssueStillSole(connection, request, joinedDefinition)) {
                    connection.rollback();
                    return java.util.Optional.empty();
                }
                requireAudit(connection, request, "RESERVED");
                requireAudit(connection, request, "HELMET_EVIDENCE");
                verifyHelmetEvidenceIfPresent(connection, request);
                if (!retireEvidence(connection, request, snapshot, digest)) {
                    connection.rollback();
                    return java.util.Optional.empty();
                }
                appendAudit(connection, request, "EQUIPPED");
                var receipt = equippedDigest(connection, request).orElseThrow(() -> new SQLException("Invalid equipped receipt"));
                connection.commit();
                return java.util.Optional.of(receipt);
            } catch (SQLException | RuntimeException failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        });
    }

    /** A joined first issue still has no conflicting provider record and no location outside this holder. */
    private static boolean firstIssueStillSole(Connection connection, ExclusiveCustodyReservation request, UUID joinedDefinition)
            throws SQLException {
        var conflicts = new java.util.HashSet<>(SQLiteCrownIdentityAudit.inspect(connection,
                joinedDefinition.toString(), request.instanceId().toString()));
        conflicts.remove(SQLiteCrownIdentityAudit.Finding.LIVE_PHYSICAL_PROOF_REQUIRED);
        conflicts.remove(SQLiteCrownIdentityAudit.Finding.LOCATION_UNCERTAIN);
        if (!firstIssue(connection, request) || !conflicts.isEmpty()) return false;
        try (var location = connection.prepareStatement("SELECT 1 FROM instance_current_state WHERE instance_id=? "
                + "AND (location_type IS NOT 'PLAYER_INVENTORY' OR location_key IS NOT ?)")) {
            location.setString(1, request.instanceId().toString());
            location.setString(2, "player:" + request.holderId());
            try (var rows = location.executeQuery()) {
                return !rows.next();
            }
        }
    }

    /** Clears the sealed original exactly once; any other row count leaves the commit to roll back. */
    private static boolean retireEvidence(Connection connection, ExclusiveCustodyReservation request, byte[] snapshot, String digest)
            throws SQLException {
        try (PreparedStatement retire = connection.prepareStatement(
                "UPDATE crown_helmet_evidence SET payload=X'' WHERE operation_id=? AND "
                + "instance_id=? AND holder_id=? AND generation=? AND payload=? AND payload_sha256=?")) {
            bindIdentity(retire, request);
            retire.setBytes(5, snapshot);
            retire.setString(6, digest);
            return retire.executeUpdate() == 1;
        }
    }

    CompletionStage<java.util.Optional<String>> readEquipment(SQLiteStorageRuntime storage, ExclusiveCustodyReservation request) {
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            try {
                return equippedDigest(connection, request);
            } finally {
                connection.rollback();
            }
        });
    }
    static boolean canRecordDeath(Connection connection, ExclusiveCustodyReservation request) throws SQLException {
        if (equippedDigest(connection, request).isPresent()) return true;
        // A holder can die immediately after staging, before the first fresh player save is loaded.
        // Recording the attacker does not retire escrow or authorize an equipment move.
        try (var query = connection.prepareStatement("SELECT 1 FROM crown_custody WHERE singleton=1 AND "
                + "operation_id=? AND instance_id=? AND holder_id=? AND generation=? AND state='RESERVED'")) {
            bindIdentity(query, request);
            try (var rows = query.executeQuery()) { if (!rows.next()) return false; }
        }
        if (!firstIssue(connection, request)) return false;
        requireAudit(connection, request, "RESERVED");
        requireAudit(connection, request, "HELMET_EVIDENCE");
        verifyHelmetEvidenceIfPresent(connection, request);
        return true;
    }

    /** The review transition and its audit entry either both commit or both roll back. */
    CompletionStage<Boolean> holdCommitted(SQLiteStorageRuntime storage, ExclusiveCustodyReservation request) {
        Objects.requireNonNull(storage, "storage");
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            try {
                boolean held = holdForReview(connection, request);
                if (held) {
                    requireAudit(connection, request, "RESERVED");
                    verifyHelmetEvidenceIfPresent(connection, request);
                    appendAudit(connection, request, "REVIEW_REQUIRED");
                } else if (isExactReviewHold(connection, request)) {
                    requireAudit(connection, request, "RESERVED");
                    requireAudit(connection, request, "REVIEW_REQUIRED");
                    verifyHelmetEvidenceIfPresent(connection, request);
                    held = true;
                }
                connection.commit();
                return held;
            } catch (SQLException | RuntimeException failure) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
        });
    }

    Result reserve(Connection connection, ExclusiveCustodyReservation request) throws SQLException {
        Objects.requireNonNull(connection, "connection");
        Objects.requireNonNull(request, "request");
        if (request.state() != ExclusiveCustodyReservation.State.RESERVED) {
            throw new IllegalArgumentException("Only a fresh reservation may be requested");
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT OR IGNORE INTO crown_custody "
                + "(singleton,operation_id,instance_id,holder_id,generation,state) VALUES (1,?,?,?,?,'RESERVED')")) {
            insert.setString(1, request.operationId().toString());
            insert.setString(2, request.instanceId().toString());
            insert.setString(3, request.holderId().toString());
            insert.setLong(4, request.generation());
            if (insert.executeUpdate() == 1) return Result.RESERVED;
        }
        return existingReservation(connection, request);
    }

    /** The singleton was already taken: only the identical operation can replay or report review. */
    private static Result existingReservation(Connection connection, ExclusiveCustodyReservation request) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT operation_id,instance_id,holder_id,generation,state FROM crown_custody WHERE singleton=1");
             ResultSet row = query.executeQuery()) {
            if (!row.next()) throw new SQLException("Custody conflict without singleton row");
            boolean same = request.operationId().toString().equals(row.getString(1))
                    && request.instanceId().toString().equals(row.getString(2))
                    && request.holderId().toString().equals(row.getString(3))
                    && request.generation() == row.getLong(4);
            if (!same) return Result.CONFLICT;
            return switch (row.getString(5)) {
                case "EQUIPPED" -> Result.CONFLICT;
                case "RESERVED" -> Result.REPLAY;
                case "REVIEW_REQUIRED" -> Result.REVIEW_REQUIRED;
                default -> throw new SQLException("Unknown custody state");
            };
        }
    }

    /** Exact operation and generation only; no path out of review is provided. */
    boolean holdForReview(Connection connection, ExclusiveCustodyReservation request) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE crown_custody SET state='REVIEW_REQUIRED' WHERE singleton=1 "
                + "AND operation_id=? AND instance_id=? AND holder_id=? AND generation=? AND state='RESERVED'")) {
            update.setString(1, request.operationId().toString());
            update.setString(2, request.instanceId().toString());
            update.setString(3, request.holderId().toString());
            update.setLong(4, request.generation());
            return update.executeUpdate() == 1;
        }
    }
}
