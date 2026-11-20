package ir.vigo.notification.inapp.rest;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import org.postgresql.ds.PGSimpleDataSource;

/** Test-only capability: marker is provisioned externally in a newly created container, never here. */
public final class DisposablePostgresFixture implements AutoCloseable {
    final PGSimpleDataSource source = new PGSimpleDataSource();
    final String database;
    final String schema = "notification_test_" + UUID.randomUUID().toString().replace("-", "");
    private final String identity;
    private boolean created;

    public DisposablePostgresFixture() {
        database = System.getProperty("notification.test.postgres.database", "");
        identity = System.getProperty("notification.test.postgres.identity", "");
        if (!database.matches("notification_disposable_[a-f0-9]{32}")
                || !identity.matches("[a-f0-9]{32}")) throw new IllegalStateException("Disposable PostgreSQL fixture identity is required");
        source.setServerNames(new String[] {"127.0.0.1"});
        source.setPortNumbers(new int[] {Integer.parseInt(System.getProperty("notification.test.postgres.port"))});
        source.setDatabaseName(database);
        source.setUser("notification_test");
        source.setPassword("notification_test_only");
        source.setConnectTimeout(5);
        source.setSocketTimeout(10);
    }

    static void requireIdentity(Connection connection, String database, String identity) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT current_database(), current_user, shobj_description(oid, 'pg_database')
                FROM pg_database WHERE datname = current_database()
                """); var result = statement.executeQuery()) {
            if (!database.matches("notification_disposable_[a-f0-9]{32}") || !identity.matches("[a-f0-9]{32}")
                    || !result.next() || !database.equals(result.getString(1))
                    || !"notification_test".equals(result.getString(2))
                    || !("vigo-notification-disposable:" + identity).equals(result.getString(3)) || result.next()) {
                throw new SQLException("Disposable PostgreSQL fixture identity mismatch; refusing database mutation");
            }
        }
    }

    public PGSimpleDataSource dataSource() { return source; }

    public String schemaName() { return schema; }

    public Connection connection() throws SQLException {
        Connection connection = source.getConnection();
        try { requireIdentity(connection, database, identity); return connection; }
        catch (SQLException | RuntimeException failure) { connection.close(); throw failure; }
    }

    public void create() throws SQLException {
        if (created) throw new IllegalStateException("Fixture schema already created");
        try (var connection = connection(); var statement = connection.createStatement()) {
            // No IF NOT EXISTS: never adopt a schema that this instance did not create.
            statement.execute("CREATE SCHEMA " + schema);
            created = true;
        }
        source.setCurrentSchema(schema);
    }

    @Override public void close() throws SQLException {
        if (!created) return;
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
            created = false;
        }
    }
}
