package com.ias.identity;

import org.flywaydb.core.Flyway;
import java.sql.*;

final class TestDatabase {
    static final String URL=System.getenv().getOrDefault("IAS_TEST_DATABASE_URL","jdbc:mysql://127.0.0.1:3307/ias_identity_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true");
    static final String NAME=System.getenv().getOrDefault("IAS_TEST_DATABASE_NAME","ias_identity_test");
    private TestDatabase() { }
    static Connection connection() throws SQLException {
        String user=System.getenv("IAS_MIGRATION_USER"), password=System.getenv("IAS_MIGRATION_PASSWORD");
        if (user==null || password==null) throw new IllegalStateException("Dedicated test migration credentials must be injected; no default credentials.");
        Connection connection=DriverManager.getConnection(URL,user,password);
        try (Statement statement=connection.createStatement(); ResultSet result=statement.executeQuery("SELECT @@port,@@datadir,DATABASE()")) {
            result.next();
            if (result.getInt(1)!=3307 || !result.getString(2).replace('\\','/').equalsIgnoreCase("C:/ias-local/Develop/IAS-MySQL84/data/") || !NAME.equals(result.getString(3))) {
                connection.close(); throw new IllegalStateException("Refusing writes outside the approved dedicated instance.");
            }
        }
        return connection;
    }
    static void migrate() {
        try (Connection ignored=connection()) { }
        catch (SQLException e) { throw new IllegalStateException("Dedicated test database verification failed",e); }
        Flyway.configure().dataSource(URL,System.getenv("IAS_MIGRATION_USER"),System.getenv("IAS_MIGRATION_PASSWORD"))
            .locations("classpath:db/migration").cleanDisabled(true).load().migrate();
    }
    static int update(String sql,Object... parameters) throws SQLException {
        try (Connection connection=connection(); PreparedStatement statement=connection.prepareStatement(sql)) {
            for (int i=0;i<parameters.length;i++) statement.setObject(i+1,parameters[i]);
            return statement.executeUpdate();
        }
    }
    static long scalar(String sql,Object... parameters) throws SQLException {
        try (Connection connection=connection(); PreparedStatement statement=connection.prepareStatement(sql)) {
            for (int i=0;i<parameters.length;i++) statement.setObject(i+1,parameters[i]);
            try (ResultSet result=statement.executeQuery()) { result.next(); return result.getLong(1); }
        }
    }
}
