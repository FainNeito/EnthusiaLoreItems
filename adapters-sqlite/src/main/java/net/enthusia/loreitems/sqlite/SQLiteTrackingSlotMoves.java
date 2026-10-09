package net.enthusia.loreitems.sqlite;

import static net.enthusia.loreitems.sqlite.SQLiteTrackingConflictSupport.setNullableString;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;

/** Keep current slot exact without adding a transfer-history event. */
final class SQLiteTrackingSlotMoves {
    private SQLiteTrackingSlotMoves() {}

    static boolean sameHolder(LocationDescriptor first, LocationDescriptor second) {
        if (first == null || second == null || first.type() != second.type()
                || !first.locationKey().equals(second.locationKey())) {
            return false;
        }
        return switch (first.type()) {
            case PLAYER_INVENTORY, PLAYER_ENDER_CHEST, BLOCK_CONTAINER -> true;
            default -> false;
        };
    }

    static boolean updateSlot(
            Connection connection, TrackingObservationUseCase.Request request,
            long currentRevision, long observedAt) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE instance_current_state SET container_path = ?, updated_at = ?, "
                        + "state_revision = state_revision + 1 WHERE instance_id = ? "
                        + "AND state_revision = ? AND state = 'CONFIRMED_NOW' "
                        + "AND updated_at <= ?")) {
            setNullableString(statement, 1, request.location().containerPath());
            statement.setLong(2, observedAt);
            statement.setString(3, request.identity().instanceId().value().toString());
            statement.setLong(4, currentRevision);
            statement.setLong(5, observedAt);
            return statement.executeUpdate() == 1;
        }
    }
}
