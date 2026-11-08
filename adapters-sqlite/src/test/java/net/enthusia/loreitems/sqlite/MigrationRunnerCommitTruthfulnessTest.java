package net.enthusia.loreitems.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationRunnerCommitTruthfulnessTest {
    private static final int EXPECTED_SCHEMA_VERSION_COUNT = 18;
    private static final String SET_AUTO_COMMIT = "setAutoCommit";
    private static final String COMMIT = "commit";

    @TempDir
    Path temporaryDirectory;

    @Test
    void committedMigrationsAreNotReportedAsFailedWhenCleanupFails() throws Exception {
        SQLiteConnectionFactory factory = new SQLiteConnectionFactory(
                temporaryDirectory.resolve("committed-cleanup.db"), 5_000);
        try (Connection delegate = factory.open();
                Connection connection = rejectAutoCommitRestoreAfterCommit(delegate)) {
            new MigrationRunner().migrate(connection);

            assertEquals(EXPECTED_SCHEMA_VERSION_COUNT, countSchemaHistory(delegate));
        }
    }

    private static Connection rejectAutoCommitRestoreAfterCommit(Connection delegate) {
        AtomicBoolean committed = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {Connection.class},
                (proxy, method, arguments) -> invoke(delegate, committed, method, arguments));
    }

    private static Object invoke(
            Connection delegate,
            AtomicBoolean committed,
            Method method,
            Object[] arguments) throws Throwable {
        if (SET_AUTO_COMMIT.equals(method.getName())
                && Boolean.TRUE.equals(arguments[0])
                && committed.get()) {
            throw new SQLException("simulated post-commit cleanup failure");
        }
        try {
            Object result = method.invoke(delegate, arguments);
            if (COMMIT.equals(method.getName())) {
                committed.set(true);
            }
            return result;
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
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
}
