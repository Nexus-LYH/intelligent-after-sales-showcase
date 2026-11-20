package com.ias.identity.bootstrap;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.Map;

/** Offline initialization for an exclusive local release window. No secrets are printed. */
public final class LocalReleaseBootstrapCli {
    private LocalReleaseBootstrapCli() { }

    public static void main(String[] args) {
        try {
            if (args.length != 0) throw new IllegalArgumentException();
            String database = required("IAS_INITIALIZE_DATABASE");
            if (!database.equals("ias_identity_test") && !database.matches("ias_identity_it_[0-9]{8}"))
                throw new IllegalArgumentException();
            Path plan = Path.of(required("IAS_BOOTSTRAP_PLAN"));
            Path secret = Path.of(required("IAS_BOOTSTRAP_SECRET_FILE"));
            if (Files.size(plan) > 65536 || Files.size(secret) > 65536) throw new IllegalArgumentException();
            var json = JsonMapper.builder().build();
            var spec = json.readValue(Files.readString(plan), AccountInitializer.Plan.class);
            @SuppressWarnings("unchecked") Map<String,String> passwords = json.readValue(Files.readString(secret), Map.class);
            String url = "jdbc:mysql://127.0.0.1:3307/" + database + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
            try (var connection = DriverManager.getConnection(url, required("IAS_INITIALIZER_USER"), required("IAS_INITIALIZER_PASSWORD"))) {
                Map<Long,String> existing = passwordSnapshot(connection);
                var encoder = new Argon2PasswordEncoder(16,32,1,19456,2);
                var first = AccountInitializer.initialize(connection, spec, passwords, encoder);
                var second = AccountInitializer.initialize(connection, spec, passwords, encoder);
                if (first.size() != second.size()) throw new IllegalStateException();
                for (int i = 0; i < first.size(); i++) {
                    if (!first.get(i).id().equals(second.get(i).id()) || !second.get(i).state().equals("ALREADY_EXISTS"))
                        throw new IllegalStateException();
                }
                Map<Long,String> after = passwordSnapshot(connection);
                for (var entry : existing.entrySet()) {
                    if (!after.containsKey(entry.getKey()) || !java.util.Objects.equals(entry.getValue(), after.get(entry.getKey())))
                        throw new IllegalStateException();
                }
            }
            System.out.println("IAS_BOOTSTRAP_REENTRANT_PASSWORDS_UNCHANGED");
        } catch (Exception e) {
            // The first transaction can have committed: an operator must retain and reuse the same inputs.
            System.err.println("IAS_BOOTSTRAP_NOT_CONFIRMED");
            System.exit(1);
        }
    }

    private static Map<Long,String> passwordSnapshot(Connection connection) throws Exception {
        Map<Long,String> result = new HashMap<>();
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT id,password_verifier FROM identity_user")) {
            while (rows.next()) result.put(rows.getLong(1), rows.getString(2));
        }
        return result;
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException();
        return value;
    }
}
