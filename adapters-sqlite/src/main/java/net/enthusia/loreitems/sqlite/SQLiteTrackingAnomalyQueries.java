package net.enthusia.loreitems.sqlite;

import static net.enthusia.loreitems.sqlite.SQLiteTrackingConflictSupport.setNullableString;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import net.enthusia.loreitems.application.TrackingObservationUseCase;
import net.enthusia.loreitems.domain.LocationDescriptor;

final class SQLiteTrackingAnomalyQueries {
    private SQLiteTrackingAnomalyQueries() {}

    static boolean hasNonDuplicateBlockingAnomaly(
            Connection connection,
            TrackingObservationUseCase.Request request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM instance_anomalies WHERE instance_id = ? "
                        + "AND status IN ('OPEN', 'ACKNOWLEDGED') "
                        + "AND anomaly_type <> 'DUPLICATE_INSTANCE' LIMIT 1")) {
            statement.setString(1, request.identity().instanceId().value().toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    static boolean hasActiveConflictEvidence(
            Connection connection,
            TrackingObservationUseCase.Request request) throws SQLException {
        LocationDescriptor location = request.location();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM instance_observations observation "
                        + "JOIN instance_anomalies anomaly "
                        + "ON anomaly.instance_id = observation.instance_id "
                        + "WHERE observation.instance_id = ? "
                        + "AND observation.location_type = ? "
                        + "AND observation.location_key = ? "
                        + "AND ((observation.container_path IS NULL AND ? IS NULL) "
                        + "OR observation.container_path = ?) "
                        + "AND observation.confidence = 'CONFLICTING' "
                        + "AND anomaly.anomaly_type = 'DUPLICATE_INSTANCE' "
                        + "AND anomaly.status IN ('OPEN', 'ACKNOWLEDGED') "
                        + "AND observation.observed_at >= anomaly.first_seen_at LIMIT 1")) {
            statement.setString(1, request.identity().instanceId().value().toString());
            statement.setString(2, location.type().name());
            statement.setString(3, location.locationKey());
            setNullableString(statement, 4, location.containerPath());
            setNullableString(statement, 5, location.containerPath());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

}
