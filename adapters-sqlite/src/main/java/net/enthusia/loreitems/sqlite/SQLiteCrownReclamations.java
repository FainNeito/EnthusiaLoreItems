package net.enthusia.loreitems.sqlite;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import net.enthusia.loreitems.application.CrownEquipmentRecovery.Delivery;
import net.enthusia.loreitems.application.CrownEquipmentRecovery.Receipt;
import net.enthusia.loreitems.application.CrownEquipmentRecovery.Reclamation;
import net.enthusia.loreitems.application.CrownEquipmentRecovery.ReclamationReservation;
import net.enthusia.loreitems.domain.ExclusiveCustodyReservation;

/**
 * Inactive-holder reclamation and recovery-winner rows. Every method runs inside the caller's open
 * transaction; only a successful reservation or delivery commits, and the caller always rolls back afterwards.
 */
final class SQLiteCrownReclamations {
    private SQLiteCrownReclamations() {}

    /** Acquire the writer lock before reading custody or competing PvP requests. */
    static void lockCustody(Connection connection) throws SQLException {
        try (var lock = connection.createStatement()) {
            lock.executeUpdate("UPDATE crown_custody SET generation=generation WHERE singleton=1");
        }
    }

    static ReclamationReservation reserveWinner(Connection connection, UUID recoveryOperation, UUID instance,
            UUID formerHolder, long generation, UUID match, UUID winner) throws SQLException {
        lockCustody(connection);
        var prior = priorWinner(connection, recoveryOperation, instance, formerHolder, generation, match, winner);
        if (prior.isPresent()) return prior.get();
        var source = heldSource(connection, recoveryOperation, instance, formerHolder, generation);
        if (source.isEmpty() || !insertWinner(connection, recoveryOperation, source.get(), match, winner))
            return ReclamationReservation.CONFLICT;
        connection.commit(); return ReclamationReservation.RESERVED;
    }

    private static Optional<ReclamationReservation> priorWinner(Connection connection, UUID recoveryOperation, UUID instance,
            UUID formerHolder, long generation, UUID match, UUID winner) throws SQLException {
        try(var existing=connection.prepareStatement("SELECT instance_id,holder_id,generation,match_id,winner_id FROM crown_recovery_delivery WHERE recovery_operation=?")) {
            existing.setString(1,recoveryOperation.toString());
            try(var row=existing.executeQuery()) {
                if(!row.next()) return Optional.empty();
                return Optional.of(instance.toString().equals(row.getString(1)) && formerHolder.toString().equals(row.getString(2))
                        && generation==row.getLong(3) && match.toString().equals(row.getString(4)) && winner.toString().equals(row.getString(5))
                        ? ReclamationReservation.ALREADY_RESERVED : ReclamationReservation.CONFLICT);
            }
        }
    }

    /** The former holder's reclamation is held under this recovery operation for exactly this instance and generation. */
    private static Optional<ExclusiveCustodyReservation> heldSource(Connection connection, UUID recoveryOperation, UUID instance,
            UUID formerHolder, long generation) throws SQLException {
        var held=read(connection,formerHolder);
        if(held.isEmpty() || !held.get().held() || !held.get().operation().equals(recoveryOperation)) return Optional.empty();
        var source=held.get().source().reservation();
        if(!source.instanceId().equals(instance) || source.generation()!=generation) return Optional.empty();
        return Optional.of(source);
    }

    private static boolean insertWinner(Connection connection, UUID recoveryOperation, ExclusiveCustodyReservation source,
            UUID match, UUID winner) throws SQLException {
        try(var insert=connection.prepareStatement("""
                INSERT OR IGNORE INTO crown_recovery_delivery
                SELECT ?,?,?,?,?,?,?,NULL WHERE NOT EXISTS
                (SELECT 1 FROM crown_succession_requests WHERE operation_id=?)""")) {
            insert.setString(1,recoveryOperation.toString());insert.setString(2,source.operationId().toString());
            insert.setString(3,source.instanceId().toString());insert.setString(4,source.holderId().toString());insert.setLong(5,source.generation());
            insert.setString(6,match.toString());insert.setString(7,winner.toString());insert.setString(8,source.operationId().toString());
            return insert.executeUpdate()==1;
        }
    }

    /** Claims the winner's pending recovery grant for {@code next}; commits only once the held payload is read. */
    static Optional<Delivery> takeRecoveryDelivery(Connection connection, UUID winner, UUID next) throws SQLException {
        var operation = pendingRecoveryOperation(connection, winner);
        if (operation.isEmpty()) return Optional.empty();
        var held = heldReclamationFor(connection, operation.get());
        if (held.isEmpty() || !claimRecipient(connection, operation.get(), next)) return Optional.empty();
        var payload = heldPayload(connection, operation.get());
        if (payload.isEmpty()) return Optional.empty();
        var source=held.get().source();
        var destination=new ExclusiveCustodyReservation(next,source.reservation().instanceId(),winner,
                Math.incrementExact(source.reservation().generation()),ExclusiveCustodyReservation.State.RESERVED);
        var delivery=new Delivery(destination,source.definition(),payload.get());
        connection.commit(); return Optional.of(delivery);
    }

    private static Optional<String> pendingRecoveryOperation(Connection connection, UUID winner) throws SQLException {
        try(var query=connection.prepareStatement("""
                SELECT d.recovery_operation FROM crown_recovery_delivery d JOIN crown_custody c
                ON c.operation_id=d.source_operation AND c.instance_id=d.instance_id AND c.holder_id=d.holder_id AND c.generation=d.generation
                WHERE d.winner_id=? AND d.recipient_operation IS NULL AND c.state='EQUIPPED'""")) {
            query.setString(1,winner.toString());
            try(var row=query.executeQuery()){return row.next() ? Optional.of(row.getString(1)) : Optional.empty();}
        }
    }

    /** The former holder's reclamation is held under exactly this recovery operation. */
    private static Optional<Reclamation> heldReclamationFor(Connection connection, String operation) throws SQLException {
        try(var payload=connection.prepareStatement("""
                SELECT d.holder_id FROM crown_recovery_delivery d WHERE d.recovery_operation=?""")) {
            payload.setString(1,operation);
            try(var row=payload.executeQuery()) {
                if(!row.next())return Optional.empty();
                var held=read(connection,UUID.fromString(row.getString(1)));
                if(held.isEmpty() || !held.get().held() || !held.get().operation().toString().equals(operation))return Optional.empty();
                return held;
            }
        }
    }

    private static boolean claimRecipient(Connection connection, String operation, UUID next) throws SQLException {
        try(var update=connection.prepareStatement("UPDATE crown_recovery_delivery SET recipient_operation=? WHERE recovery_operation=? AND recipient_operation IS NULL")) {
            update.setString(1,next.toString());update.setString(2,operation);return update.executeUpdate()==1;
        }
    }

    private static Optional<byte[]> heldPayload(Connection connection, String operation) throws SQLException {
        try(var bytes=connection.prepareStatement("SELECT payload FROM crown_reclamation_escrow WHERE recovery_operation=? AND state='HELD'")) {
            bytes.setString(1,operation);
            try(var value=bytes.executeQuery()) {return value.next() ? Optional.of(value.getBytes(1)) : Optional.empty();}
        }
    }

    static ReclamationReservation reserveReclamation(Connection connection, UUID recoveryOperation, UUID instance, UUID holder,
            long generation) throws SQLException {
        lockCustody(connection);
        var prior = priorReclamation(connection, recoveryOperation, instance, holder, generation);
        if (prior.isPresent()) return prior.get();
        var source = reclaimableSource(connection, instance, holder, generation);
        if (source.isEmpty()) return ReclamationReservation.CONFLICT;
        try (var insert = connection.prepareStatement("INSERT INTO crown_reclamation VALUES(?,?,?,?,?,?)")) {
            insert.setString(1,recoveryOperation.toString()); insert.setString(2,source.get().operationId().toString());
            insert.setString(3,instance.toString()); insert.setString(4,holder.toString());
            insert.setLong(5,generation); insert.setLong(6,System.currentTimeMillis()); insert.executeUpdate();
        }
        connection.commit();
        return ReclamationReservation.RESERVED;
    }

    private static Optional<ReclamationReservation> priorReclamation(Connection connection, UUID recoveryOperation, UUID instance,
            UUID holder, long generation) throws SQLException {
        try (var existing = connection.prepareStatement("""
                SELECT r.instance_id,r.holder_id,r.generation,
                    EXISTS (SELECT 1 FROM crown_custody c JOIN lore_instances i USING(instance_id)
                        WHERE c.operation_id=r.source_operation AND c.instance_id=r.instance_id
                        AND c.holder_id=r.holder_id AND c.generation=r.generation
                        AND c.state='EQUIPPED' AND i.lifecycle_state='ACTIVE')
                FROM crown_reclamation r WHERE recovery_operation=?""")) {
            existing.setString(1, recoveryOperation.toString());
            try (var row = existing.executeQuery()) {
                if (!row.next()) return Optional.empty();
                return Optional.of(instance.toString().equals(row.getString(1))
                        && holder.toString().equals(row.getString(2)) && generation == row.getLong(3) && row.getInt(4) == 1
                        ? ReclamationReservation.ALREADY_RESERVED
                        : ReclamationReservation.CONFLICT);
            }
        }
    }

    /** The equipped, active, uncontested custody row for this holder and generation, with a valid equipped receipt. */
    private static Optional<ExclusiveCustodyReservation> reclaimableSource(Connection connection, UUID instance, UUID holder,
            long generation) throws SQLException {
        try (var query = connection.prepareStatement("""
                SELECT c.operation_id FROM crown_custody c JOIN lore_instances i USING(instance_id)
                WHERE c.singleton=1 AND c.instance_id=? AND c.holder_id=? AND c.generation=?
                AND c.state='EQUIPPED' AND i.lifecycle_state='ACTIVE'
                AND NOT EXISTS (SELECT 1 FROM crown_succession_requests s WHERE s.operation_id=c.operation_id)
                AND NOT EXISTS (SELECT 1 FROM crown_reclamation r WHERE r.source_operation=c.operation_id)""")) {
            query.setString(1, instance.toString()); query.setString(2, holder.toString()); query.setLong(3, generation);
            try (var row = query.executeQuery()) {
                if (!row.next()) return Optional.empty();
                var source = new ExclusiveCustodyReservation(UUID.fromString(row.getString(1)),instance,holder,generation,
                        ExclusiveCustodyReservation.State.RESERVED);
                return SQLiteCustodyEvidence.equippedDigest(connection, source).isEmpty() ? Optional.empty() : Optional.of(source);
            }
        }
    }

    static Optional<Reclamation> read(Connection connection, UUID holder) throws SQLException {
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
                var digest = SQLiteCustodyEvidence.equippedDigest(connection,source);
                if (digest.isEmpty()) return Optional.empty();
                String state = row.getString(6);
                if (state != null && (!definition.toString().equals(row.getString(9))
                        || !SQLiteCustodyEvidence.fingerprint(row.getBytes(7)).equals(row.getString(8))))
                    throw new SQLException("Reclamation escrow integrity mismatch");
                return Optional.of(new Reclamation(UUID.fromString(row.getString(1)),
                        new Receipt(source,definition,digest.get()), "HELD".equals(state)));
            }
        }
    }
}
