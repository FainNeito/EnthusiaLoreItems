package net.enthusia.loreitems.sqlite;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.enthusia.loreitems.application.CrownEquipmentRecovery;
import java.util.concurrent.CompletionStage;
import net.enthusia.loreitems.application.CrownHelmetEvidenceStore;
import net.enthusia.loreitems.domain.ExclusiveCustodyReservation;

/** SQLite custody adapter. Join recovery exposes committed receipts only. */
public final class SQLiteCrownHelmetEvidenceStore implements CrownHelmetEvidenceStore, net.enthusia.loreitems.application.CrownExchangeAdmission, CrownEquipmentRecovery {
    private final SQLiteStorageRuntime storage;
    private final SQLiteExclusiveCustodyJournal journal = new SQLiteExclusiveCustodyJournal();

    public SQLiteCrownHelmetEvidenceStore(SQLiteStorageRuntime storage) {
        this.storage = Objects.requireNonNull(storage, "storage");
    }

    @Override
    public CompletionStage<ReclamationReservation> reserveRecoveryWinner(UUID recoveryOperation, UUID instance,
            UUID formerHolder, long generation, UUID match, UUID winner) {
        Objects.requireNonNull(recoveryOperation); Objects.requireNonNull(instance); Objects.requireNonNull(formerHolder);
        Objects.requireNonNull(match); Objects.requireNonNull(winner);
        if(generation<0 || generation==Long.MAX_VALUE) throw new IllegalArgumentException("Invalid generation");
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            try {
                try(var lock=connection.createStatement()){lock.executeUpdate("UPDATE crown_custody SET generation=generation WHERE singleton=1");}
                try(var existing=connection.prepareStatement("SELECT instance_id,holder_id,generation,match_id,winner_id FROM crown_recovery_delivery WHERE recovery_operation=?")) {
                    existing.setString(1,recoveryOperation.toString());
                    try(var row=existing.executeQuery()) {
                        if(row.next()) return instance.toString().equals(row.getString(1)) && formerHolder.toString().equals(row.getString(2))
                                && generation==row.getLong(3) && match.toString().equals(row.getString(4)) && winner.toString().equals(row.getString(5))
                                ? ReclamationReservation.ALREADY_RESERVED : ReclamationReservation.CONFLICT;
                    }
                }
                var held=readReclamation(connection,formerHolder);
                if(held.isEmpty() || !held.get().held() || !held.get().operation().equals(recoveryOperation)) return ReclamationReservation.CONFLICT;
                var source=held.get().source().reservation();
                if(!source.instanceId().equals(instance) || source.generation()!=generation) return ReclamationReservation.CONFLICT;
                try(var insert=connection.prepareStatement("""
                        INSERT OR IGNORE INTO crown_recovery_delivery
                        SELECT ?,?,?,?,?,?,?,NULL WHERE NOT EXISTS
                        (SELECT 1 FROM crown_succession_requests WHERE operation_id=?)""")) {
                    insert.setString(1,recoveryOperation.toString());insert.setString(2,source.operationId().toString());
                    insert.setString(3,instance.toString());insert.setString(4,formerHolder.toString());insert.setLong(5,generation);
                    insert.setString(6,match.toString());insert.setString(7,winner.toString());insert.setString(8,source.operationId().toString());
                    if(insert.executeUpdate()!=1) return ReclamationReservation.CONFLICT;
                }
                connection.commit(); return ReclamationReservation.RESERVED;
            } finally {connection.rollback();}
        });
    }

    private static Optional<Delivery> takeRecoveryDelivery(java.sql.Connection connection, UUID winner, UUID next) throws java.sql.SQLException {
        try(var query=connection.prepareStatement("""
                SELECT d.recovery_operation FROM crown_recovery_delivery d JOIN crown_custody c
                ON c.operation_id=d.source_operation AND c.instance_id=d.instance_id AND c.holder_id=d.holder_id AND c.generation=d.generation
                WHERE d.winner_id=? AND d.recipient_operation IS NULL AND c.state='EQUIPPED'""")) {
            query.setString(1,winner.toString());
            String operation;
            try(var row=query.executeQuery()){if(!row.next())return Optional.empty();operation=row.getString(1);}
            try(var payload=connection.prepareStatement("""
                    SELECT d.holder_id FROM crown_recovery_delivery d WHERE d.recovery_operation=?""")) {
                payload.setString(1,operation);
                try(var row=payload.executeQuery()) {
                    if(!row.next())return Optional.empty();
                    var held=readReclamation(connection,UUID.fromString(row.getString(1)));
                    if(held.isEmpty() || !held.get().held() || !held.get().operation().toString().equals(operation))return Optional.empty();
                    try(var update=connection.prepareStatement("UPDATE crown_recovery_delivery SET recipient_operation=? WHERE recovery_operation=? AND recipient_operation IS NULL")) {
                        update.setString(1,next.toString());update.setString(2,operation);if(update.executeUpdate()!=1)return Optional.empty();
                    }
                    try(var bytes=connection.prepareStatement("SELECT payload FROM crown_reclamation_escrow WHERE recovery_operation=? AND state='HELD'")) {
                        bytes.setString(1,operation);
                        try(var value=bytes.executeQuery()) {
                            if(!value.next())return Optional.empty();
                            var source=held.get().source();
                            var destination=new ExclusiveCustodyReservation(next,source.reservation().instanceId(),winner,
                                    Math.incrementExact(source.reservation().generation()),ExclusiveCustodyReservation.State.RESERVED);
                            var delivery=new Delivery(destination,source.definition(),value.getBytes(1));
                            connection.commit(); return Optional.of(delivery);
                        }
                    }
                }
            }
        }
    }

    @Override
    public CompletionStage<ReclamationReservation> reserveReclamation(
            UUID recoveryOperation, UUID instance, UUID holder, long generation) {
        Objects.requireNonNull(recoveryOperation);
        Objects.requireNonNull(instance);
        Objects.requireNonNull(holder);
        if (generation < 0) throw new IllegalArgumentException("Negative custody generation");
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            try {
                // Acquire the writer lock before reading custody or competing PvP requests.
                try (var lock = connection.createStatement()) {
                    lock.executeUpdate("UPDATE crown_custody SET generation=generation WHERE singleton=1");
                }
                try (var existing = connection.prepareStatement("""
                        SELECT r.instance_id,r.holder_id,r.generation,
                            EXISTS (SELECT 1 FROM crown_custody c JOIN lore_instances i USING(instance_id)
                                WHERE c.operation_id=r.source_operation AND c.instance_id=r.instance_id
                                AND c.holder_id=r.holder_id AND c.generation=r.generation
                                AND c.state='EQUIPPED' AND i.lifecycle_state='ACTIVE')
                        FROM crown_reclamation r WHERE recovery_operation=?""")) {
                    existing.setString(1, recoveryOperation.toString());
                    try (var row = existing.executeQuery()) {
                        if (row.next()) return instance.toString().equals(row.getString(1))
                                && holder.toString().equals(row.getString(2)) && generation == row.getLong(3) && row.getInt(4) == 1
                                ? ReclamationReservation.ALREADY_RESERVED
                                : ReclamationReservation.CONFLICT;
                    }
                }
                try (var query = connection.prepareStatement("""
                        SELECT c.operation_id FROM crown_custody c JOIN lore_instances i USING(instance_id)
                        WHERE c.singleton=1 AND c.instance_id=? AND c.holder_id=? AND c.generation=?
                        AND c.state='EQUIPPED' AND i.lifecycle_state='ACTIVE'
                        AND NOT EXISTS (SELECT 1 FROM crown_succession_requests s WHERE s.operation_id=c.operation_id)
                        AND NOT EXISTS (SELECT 1 FROM crown_reclamation r WHERE r.source_operation=c.operation_id)""")) {
                    query.setString(1, instance.toString()); query.setString(2, holder.toString()); query.setLong(3, generation);
                    try (var row = query.executeQuery()) {
                        if (!row.next()) return ReclamationReservation.CONFLICT;
                        UUID sourceOperation = UUID.fromString(row.getString(1));
                        var source = new ExclusiveCustodyReservation(sourceOperation,instance,holder,generation,
                                ExclusiveCustodyReservation.State.RESERVED);
                        if (SQLiteExclusiveCustodyJournal.equippedDigest(connection, source).isEmpty())
                            return ReclamationReservation.CONFLICT;
                        try (var insert = connection.prepareStatement("INSERT INTO crown_reclamation VALUES(?,?,?,?,?,?)")) {
                            insert.setString(1,recoveryOperation.toString()); insert.setString(2,sourceOperation.toString());
                            insert.setString(3,instance.toString()); insert.setString(4,holder.toString());
                            insert.setLong(5,generation); insert.setLong(6,System.currentTimeMillis()); insert.executeUpdate();
                        }
                    }
                }
                connection.commit();
                return ReclamationReservation.RESERVED;
            } finally { connection.rollback(); }
        });
    }

    @Override
    public CompletionStage<Optional<Reclamation>> findReclamation(UUID holder) {
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            try { return readReclamation(connection, holder); }
            finally { connection.rollback(); }
        });
    }

    @Override
    public CompletionStage<Optional<ReleasedReclamation>> releasedReclamation(UUID holder, UUID operation) {
        return storage.execute(connection -> {
            try(var query=connection.prepareStatement("""
                    SELECT r.source_operation,r.instance_id,r.generation,e.definition_id FROM crown_reclamation r
                    JOIN crown_reclamation_escrow e USING(recovery_operation)
                    JOIN crown_recovery_delivery d USING(recovery_operation)
                    WHERE r.holder_id=? AND r.recovery_operation=? AND e.state='HELD'
                    AND d.source_operation=r.source_operation AND d.instance_id=r.instance_id
                    AND d.holder_id=r.holder_id AND d.generation=r.generation""")) {
                query.setString(1,holder.toString());query.setString(2,operation.toString());
                try(var row=query.executeQuery()) {
                    return row.next() ? Optional.of(new ReleasedReclamation(new ExclusiveCustodyReservation(
                            UUID.fromString(row.getString(1)),UUID.fromString(row.getString(2)),holder,row.getLong(3),
                            ExclusiveCustodyReservation.State.RESERVED),UUID.fromString(row.getString(4)))) : Optional.empty();
                }
            }
        });
    }

    private static Optional<Reclamation> readReclamation(java.sql.Connection connection, UUID holder) throws java.sql.SQLException {
        try (var query = connection.prepareStatement("""
                SELECT r.recovery_operation,c.operation_id,c.instance_id,c.generation,i.definition_id,
                       e.state,e.payload,e.payload_sha256,e.definition_id
                FROM crown_reclamation r JOIN crown_custody c ON c.operation_id=r.source_operation
                    AND c.instance_id=r.instance_id AND c.holder_id=r.holder_id AND c.generation=r.generation
                JOIN lore_instances i ON i.instance_id=c.instance_id
                LEFT JOIN crown_reclamation_escrow e USING(recovery_operation)
                WHERE c.holder_id=? AND c.state='EQUIPPED' AND i.lifecycle_state='ACTIVE'""")) {
            query.setString(1, holder.toString());
            try (var row = query.executeQuery()) {
                if (!row.next()) return Optional.empty();
                UUID definition = UUID.fromString(row.getString(5));
                var source = new ExclusiveCustodyReservation(UUID.fromString(row.getString(2)),
                        UUID.fromString(row.getString(3)), holder, row.getLong(4), ExclusiveCustodyReservation.State.RESERVED);
                var digest = SQLiteExclusiveCustodyJournal.equippedDigest(connection,source);
                if (digest.isEmpty()) return Optional.empty();
                String state = row.getString(6);
                if (state != null && (!definition.toString().equals(row.getString(9))
                        || !SQLiteExclusiveCustodyJournal.fingerprint(row.getBytes(7)).equals(row.getString(8))))
                    throw new java.sql.SQLException("Reclamation escrow integrity mismatch");
                return Optional.of(new Reclamation(UUID.fromString(row.getString(1)),
                        new Receipt(source,definition,digest.get()), "HELD".equals(state)));
            }
        }
    }

    @Override
    public CompletionStage<Boolean> stageReclamation(Reclamation request, byte[] crown) {
        byte[] payload = Objects.requireNonNull(crown).clone();
        if (payload.length == 0 || payload.length > 1048576) throw new IllegalArgumentException("Invalid Crown payload size");
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            try {
                try (var lock = connection.createStatement()) {
                    lock.executeUpdate("UPDATE crown_custody SET generation=generation WHERE singleton=1");
                }
                var current = readReclamation(connection,request.source().reservation().holderId());
                if (current.isEmpty() || current.get().held() || !current.get().equals(request)) return false;
                String digest = SQLiteExclusiveCustodyJournal.fingerprint(payload);
                try (var insert = connection.prepareStatement("INSERT OR IGNORE INTO crown_reclamation_escrow VALUES(?,?,?,?,'STAGED')")) {
                    insert.setString(1,request.operation().toString()); insert.setString(2,request.source().definition().toString());
                    insert.setBytes(3,payload); insert.setString(4,digest); insert.executeUpdate();
                }
                try (var verify = connection.prepareStatement("SELECT payload,payload_sha256 FROM crown_reclamation_escrow WHERE recovery_operation=? AND state='STAGED'")) {
                    verify.setString(1,request.operation().toString());
                    try (var row = verify.executeQuery()) {
                        if (!row.next() || !java.util.Arrays.equals(payload,row.getBytes(1)) || !digest.equals(row.getString(2))) return false;
                    }
                }
                connection.commit();
                return true;
            } finally { connection.rollback(); }
        });
    }

    @Override
    public CompletionStage<Boolean> confirmReclamation(Reclamation request) {
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            try {
                try (var lock = connection.createStatement()) {
                    lock.executeUpdate("UPDATE crown_custody SET generation=generation WHERE singleton=1");
                }
                var current = readReclamation(connection,request.source().reservation().holderId());
                if (current.isEmpty() || !current.get().operation().equals(request.operation())
                        || !current.get().source().equals(request.source())) return false;
                if (current.get().held()) return true;
                try (var update = connection.prepareStatement("UPDATE crown_reclamation_escrow SET state='HELD' WHERE recovery_operation=? AND state='STAGED'")) {
                    update.setString(1, request.operation().toString());
                    if (update.executeUpdate() != 1) return false;
                }
                connection.commit();
                return true;
            } finally { connection.rollback(); }
        });
    }

    /** A true result authorizes only the first materialization for this committed operation. */
    public CompletionStage<Boolean> reserveFirstIssue(ExclusiveCustodyReservation request, UUID definition,
                                                      long revision, net.enthusia.loreitems.application.EncodedItemTemplate template, byte[] original) {
        return journal.reserveFirstIssue(storage, request, definition, revision, template, original);
    }
    /** Fence interrupted reservations before any Crown worker or join listener is admitted. */
    public CompletionStage<Boolean> holdInterruptedOnStartup() {
        return journal.recoverInterruptedOnStartup(storage).thenApply(result ->
                result == SQLiteExclusiveCustodyJournal.RecoveryResult.REVIEW_REQUIRED
                        || result == SQLiteExclusiveCustodyJournal.RecoveryResult.ALREADY_IN_REVIEW);
    }
    /** Compares a saved original to committed evidence without changing custody. */
    public CompletionStage<Boolean> matchesSavedHelmet(ExclusiveCustodyReservation reservation, byte[] original) {
        return journal.matchesSavedHelmet(storage, reservation, original);
    }
    public CompletionStage<java.util.Optional<String>> commitEquipment(ExclusiveCustodyReservation request, byte[] savedOriginal) {
        return journal.commitEquipment(storage, request, savedOriginal);
    }
    public CompletionStage<java.util.Optional<String>> readEquipment(ExclusiveCustodyReservation request) {
        return journal.readEquipment(storage, request);
    }
    @Override
    public CompletionStage<Boolean> recordPlayerKill(ExclusiveCustodyReservation request, UUID killer) {
        if (request.holderId().equals(killer)) return java.util.concurrent.CompletableFuture.completedFuture(false);
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            try {
                try (var insert = connection.prepareStatement("""
                        INSERT INTO crown_succession_requests (operation_id, instance_id, holder_id, generation, killer_id, recorded_at)
                        SELECT operation_id, instance_id, holder_id, generation, ?, ? FROM crown_custody
                        WHERE singleton=1 AND state IN ('RESERVED','EQUIPPED') AND operation_id=? AND instance_id=? AND holder_id=? AND generation=?
                        AND NOT EXISTS (SELECT 1 FROM crown_reclamation r WHERE r.source_operation=crown_custody.operation_id)
                        ON CONFLICT DO NOTHING""")) {
                    insert.setString(1, killer.toString());
                    insert.setLong(2, System.currentTimeMillis());
                    insert.setString(3, request.operationId().toString());
                    insert.setString(4, request.instanceId().toString());
                    insert.setString(5, request.holderId().toString());
                    insert.setLong(6, request.generation());
                    insert.executeUpdate();
                }
                if (!SQLiteExclusiveCustodyJournal.canRecordDeath(connection, request)) return false;
                try (var query = connection.prepareStatement("""
                        SELECT 1 FROM crown_succession_requests WHERE operation_id=? AND instance_id=? AND holder_id=?
                        AND generation=? AND killer_id=?""")) {
                    query.setString(1, request.operationId().toString());
                    query.setString(2, request.instanceId().toString());
                    query.setString(3, request.holderId().toString());
                    query.setLong(4, request.generation());
                    query.setString(5, killer.toString());
                    try (var rows = query.executeQuery()) { if (!rows.next()) return false; }
                }
                connection.commit();
                return true;
            } finally {
                connection.rollback();
            }
        });
    }

    @Override
    public CompletionStage<Boolean> escrowForHandoff(Receipt source, byte[] crown) {
        if (crown.length == 0 || crown.length > 1048576) return java.util.concurrent.CompletableFuture.completedFuture(false);
        byte[] snapshot = crown.clone();
        var request = source.reservation();
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            try (var insert = connection.prepareStatement("""
                    INSERT INTO crown_handoff_escrow (operation_id,payload)
                    SELECT s.operation_id,? FROM crown_succession_requests s
                    JOIN crown_custody c USING(operation_id,instance_id,holder_id,generation)
                    JOIN lore_instances i ON i.instance_id=c.instance_id
                    WHERE c.state='EQUIPPED' AND c.operation_id=? AND c.instance_id=? AND c.holder_id=?
                    AND c.generation=? AND i.definition_id=? AND i.lifecycle_state='ACTIVE'
                    ON CONFLICT(operation_id) DO NOTHING""")) {
                insert.setBytes(1, snapshot);
                insert.setString(2, request.operationId().toString());
                insert.setString(3, request.instanceId().toString());
                insert.setString(4, request.holderId().toString());
                insert.setLong(5, request.generation());
                insert.setString(6, source.definition().toString());
                insert.executeUpdate();
                if (!SQLiteExclusiveCustodyJournal.equippedDigest(connection, request).filter(source.originalDigest()::equals).isPresent())
                    return false;
                try (var check = connection.prepareStatement("""
                        SELECT 1 FROM crown_handoff_escrow e JOIN crown_succession_requests s USING(operation_id)
                        JOIN lore_instances i ON i.instance_id=s.instance_id
                        WHERE operation_id=? AND payload=? AND released=0 AND i.definition_id=? AND i.lifecycle_state='ACTIVE'""")) {
                    check.setString(1, request.operationId().toString());
                    check.setBytes(2, snapshot);
                    check.setString(3, source.definition().toString());
                    try (var row = check.executeQuery()) { if (!row.next()) return false; }
                }
                connection.commit();
                return true;
            } finally { connection.rollback(); }
        });
    }

    @Override
    public CompletionStage<Optional<UUID>> confirmSourceReleased(UUID holder, UUID operation, UUID instance, UUID definition) {
        return storage.execute(connection -> {
            try (var update = connection.prepareStatement("""
                    UPDATE crown_handoff_escrow SET released=1 WHERE operation_id=? AND EXISTS (
                    SELECT 1 FROM crown_succession_requests JOIN crown_custody USING(operation_id,holder_id,instance_id,generation)
                    JOIN lore_instances USING(instance_id) WHERE operation_id=? AND holder_id=? AND instance_id=?
                    AND definition_id=? AND lifecycle_state='ACTIVE' AND state='EQUIPPED')
                    RETURNING (SELECT killer_id FROM crown_succession_requests s WHERE s.operation_id=crown_handoff_escrow.operation_id)""")) {
                update.setString(1, operation.toString());
                update.setString(2, operation.toString());
                update.setString(3, holder.toString());
                update.setString(4, instance.toString());
                update.setString(5, definition.toString());
                try (var row = update.executeQuery()) {
                    return row.next() ? Optional.of(UUID.fromString(row.getString(1))) : Optional.empty();
                }
            }
        });
    }

    @Override
    public CompletionStage<Optional<Delivery>> takeReleased(UUID killer) {
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            UUID next = UUID.randomUUID();
            try (var update = connection.prepareStatement("""
                    UPDATE crown_handoff_escrow SET recipient_operation=? WHERE released=1 AND recipient_operation IS NULL
                    AND operation_id IN (SELECT s.operation_id FROM crown_succession_requests s JOIN crown_custody c
                    USING(operation_id,instance_id,holder_id,generation) WHERE s.killer_id=? AND c.state='EQUIPPED')""")) {
                update.setString(1, next.toString());
                update.setString(2, killer.toString());
                if (update.executeUpdate() != 1) return takeRecoveryDelivery(connection,killer,next);
                try (var query = connection.prepareStatement("""
                        SELECT e.payload,s.instance_id,s.generation,i.definition_id FROM crown_handoff_escrow e
                        JOIN crown_succession_requests s USING(operation_id) JOIN lore_instances i USING(instance_id)
                        WHERE recipient_operation=? AND i.lifecycle_state='ACTIVE'""")) {
                    query.setString(1, next.toString());
                    try (var row = query.executeQuery()) {
                        if (!row.next()) return Optional.empty();
                        var request = new ExclusiveCustodyReservation(next, UUID.fromString(row.getString(2)), killer,
                                Math.incrementExact(row.getLong(3)), ExclusiveCustodyReservation.State.RESERVED);
                        var delivery = new Delivery(request, UUID.fromString(row.getString(4)), row.getBytes(1));
                        connection.commit();
                        return Optional.of(delivery);
                    }
                }
            } finally { connection.rollback(); }
        });
    }

    @Override
    public CompletionStage<Optional<Inspection>> inspectCustody() {
        return storage.execute(connection -> {
            try (var query = connection.prepareStatement("""
                    SELECT c.operation_id,c.instance_id,c.holder_id,c.generation,c.state,
                           COALESCE(s.killer_id,d.winner_id),COALESCE(e.recipient_operation,d.recipient_operation),
                           CASE WHEN d.recovery_operation IS NOT NULL THEN 1 ELSE e.released END,r.recovery_operation,COALESCE(re.state,'RESERVED')
                    FROM crown_custody c LEFT JOIN crown_succession_requests s
                    USING(operation_id,instance_id,holder_id,generation)
                    LEFT JOIN crown_handoff_escrow e ON e.operation_id=s.operation_id
                    LEFT JOIN crown_reclamation r ON r.source_operation=c.operation_id
                    LEFT JOIN crown_reclamation_escrow re ON re.recovery_operation=r.recovery_operation
                    LEFT JOIN crown_recovery_delivery d ON d.source_operation=c.operation_id
                    WHERE c.singleton=1""") ; var rows = query.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                String recipient = rows.getString(6), delivery = rows.getString(7);
                Optional<PendingTransfer> transfer = recipient == null ? Optional.empty()
                        : Optional.of(new PendingTransfer(UUID.fromString(recipient),
                                Optional.ofNullable(delivery).map(UUID::fromString), rows.getInt(8) == 1));
                return Optional.of(new Inspection(UUID.fromString(rows.getString(1)),
                        UUID.fromString(rows.getString(2)), UUID.fromString(rows.getString(3)),
                        rows.getLong(4), rows.getString(5), transfer,
                        Optional.ofNullable(rows.getString(9)).map(UUID::fromString), rows.getString(10)));
            }
        });
    }

    @Override
    public CompletionStage<Boolean> hasUnconfirmedDelivery(UUID holder) {
        return storage.execute(connection -> {
            try (var query = connection.prepareStatement("""
                    SELECT 1 FROM crown_handoff_escrow e
                    JOIN crown_succession_requests s USING(operation_id)
                    JOIN crown_custody c USING(operation_id,instance_id,holder_id,generation)
                    WHERE s.killer_id=? AND e.released=1 AND e.recipient_operation IS NOT NULL
                    UNION ALL SELECT 1 FROM crown_recovery_delivery d JOIN crown_custody c ON c.operation_id=d.source_operation
                    AND c.instance_id=d.instance_id AND c.holder_id=d.holder_id AND c.generation=d.generation
                    WHERE d.winner_id=? AND d.recipient_operation IS NOT NULL""")) {
                query.setString(1, holder.toString());
                query.setString(2, holder.toString());
                try (var rows = query.executeQuery()) { return rows.next(); }
            }
        });
    }

    @Override
    public CompletionStage<Boolean> commitSuccessor(ExclusiveCustodyReservation destination, UUID definition) {
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            try (var move = connection.prepareStatement("""
                    UPDATE crown_custody AS c SET operation_id=?,holder_id=?,generation=?
                    WHERE state='EQUIPPED' AND instance_id=? AND (EXISTS (
                    SELECT 1 FROM crown_succession_requests s JOIN crown_handoff_escrow e USING(operation_id)
                    JOIN lore_instances i USING(instance_id) WHERE s.operation_id=c.operation_id AND s.instance_id=c.instance_id
                    AND s.holder_id=c.holder_id AND s.generation=c.generation AND s.generation+1=? AND e.released=1
                    AND e.recipient_operation=? AND s.killer_id=? AND i.definition_id=? AND i.lifecycle_state='ACTIVE')
                    OR EXISTS (SELECT 1 FROM crown_recovery_delivery d JOIN crown_reclamation_escrow re USING(recovery_operation)
                    JOIN lore_instances i ON i.instance_id=d.instance_id WHERE d.source_operation=c.operation_id
                    AND d.instance_id=c.instance_id AND d.holder_id=c.holder_id AND d.generation=c.generation
                    AND d.generation+1=? AND d.recipient_operation=? AND d.winner_id=? AND re.state='HELD'
                    AND i.definition_id=? AND i.lifecycle_state='ACTIVE'))""")) {
                move.setString(1,destination.operationId().toString());
                move.setString(2,destination.holderId().toString());
                move.setLong(3,destination.generation());
                move.setString(4,destination.instanceId().toString());
                move.setLong(5,destination.generation());
                move.setString(6,destination.operationId().toString());
                move.setString(7,destination.holderId().toString());
                move.setString(8,definition.toString());
                move.setLong(9,destination.generation());move.setString(10,destination.operationId().toString());
                move.setString(11,destination.holderId().toString());move.setString(12,definition.toString());
                if (move.executeUpdate()!=1) return false;
                // The live journal holds one generation. Preserve its full history before rotating it.
                try (var archive=connection.createStatement()) {
                    archive.executeUpdate("INSERT INTO crown_prior_audit SELECT * FROM crown_custody_audit");
                    archive.executeUpdate("INSERT INTO crown_prior_helmets SELECT * FROM crown_helmet_evidence");
                    archive.executeUpdate("DELETE FROM crown_custody_audit");
                    archive.executeUpdate("DELETE FROM crown_helmet_evidence");
                }
                try (var evidence=connection.prepareStatement("""
                        INSERT INTO crown_helmet_evidence(operation_id,instance_id,holder_id,generation,payload,payload_sha256,is_empty)
                        VALUES(?,?,?,?,X'',?,1)""")) {
                    SQLiteExclusiveCustodyJournal.bindIdentity(evidence,destination);
                    evidence.setString(5,SQLiteExclusiveCustodyJournal.fingerprint(new byte[0]));
                    evidence.executeUpdate();
                }
                for (String state : java.util.List.of("RESERVED","HELMET_EVIDENCE","EQUIPPED"))
                    SQLiteExclusiveCustodyJournal.appendAudit(connection,destination,state);
                if (SQLiteExclusiveCustodyJournal.equippedDigest(connection,destination).isEmpty()) return false;
                connection.commit();
                return true;
            } finally { connection.rollback(); }
        });
    }

    @Override
    public CompletionStage<Optional<Receipt>> confirmLoaded(ExclusiveCustodyReservation request, UUID definition, byte[] original) {
        return journal.commitEquipment(storage, request, original, Objects.requireNonNull(definition))
                .thenCompose(ignored -> findEquipped(request.holderId()));
    }
    @Override
    public CompletionStage<Optional<Receipt>> findEquipped(UUID holder) {
        return storage.execute(connection -> {
            connection.setAutoCommit(false);
            try (var query = connection.prepareStatement(
                    "SELECT c.operation_id,c.instance_id,c.generation,i.definition_id FROM crown_custody c "
                    + "JOIN lore_instances i ON i.instance_id=c.instance_id AND i.lifecycle_state='ACTIVE' "
                    + "WHERE c.singleton=1 AND c.state='EQUIPPED' AND c.holder_id=?")) {
                query.setString(1, holder.toString());
                try (var rows = query.executeQuery()) {
                    if (!rows.next()) return Optional.empty();
                    var request = new ExclusiveCustodyReservation(UUID.fromString(rows.getString(1)),
                            UUID.fromString(rows.getString(2)), holder, rows.getLong(3), ExclusiveCustodyReservation.State.RESERVED);
                    UUID definition = UUID.fromString(rows.getString(4));
                    return SQLiteExclusiveCustodyJournal.equippedDigest(connection, request)
                            .map(digest -> new Receipt(request, definition, digest));
                }
            } finally {
                connection.rollback();
            }
        });
    }
    @Override
    public CompletionStage<Result> record(ExclusiveCustodyReservation reservation, boolean empty, byte[] evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (empty && evidence.length != 0) throw new IllegalArgumentException("Empty helmet evidence must have no payload");
        var stage = empty ? journal.recordEmptyHelmetEvidence(storage, reservation)
                : journal.recordHelmetEvidence(storage, reservation, evidence);
        return stage.thenApply(result -> switch (result) {
            case RECORDED -> Result.RECORDED;
            case REPLAY -> Result.REPLAY;
            case HOLD -> Result.HOLD;
        });
    }

    @Override
    public CompletionStage<Boolean> admit(ExclusiveCustodyReservation request, UUID definition, boolean empty, byte[] original) {
        return journal.admitExisting(storage, request, definition, empty, original);
    }

    @Override
    public CompletionStage<Boolean> hold(ExclusiveCustodyReservation reservation) {
        return journal.holdCommitted(storage, reservation);
    }
}
