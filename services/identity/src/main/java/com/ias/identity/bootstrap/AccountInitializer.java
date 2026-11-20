package com.ias.identity.bootstrap;

import com.ias.identity.auth.AuthCrypto;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.*;
import java.util.*;

/**
 * Explicit offline operation only. Not a Spring component/Runner or HTTP controller.
 */
public final class AccountInitializer {
    public record TenantSpec(String code, String name) {
    }

    public record UserSpec(String realm, String tenantCode, String account, String displayName, boolean administrator,
                           String passwordRef) {
    }

    public record Plan(List<TenantSpec> tenants, List<UserSpec> users) {
    }

    public record Result(String type, String id, String account, String state) {
    }

    private static final String LOCK = "ias_identity_test.account_bootstrap";

    private AccountInitializer() {
    }

    public static List<Result> initialize(Connection c, Plan plan, Map<String, String> secrets, PasswordEncoder encoder) throws SQLException {
        return initialize(c, plan, secrets, encoder, false);
    }

    /**
     * Additional TENANT-only employees: preserves all existing permissions/status/secrets without restoration.
     */
    public static List<Result> initializeEmployees(Connection c, Plan plan, Map<String, String> secrets, PasswordEncoder encoder) throws SQLException {
        return initialize(c, plan, secrets, encoder, true);
    }

    private static List<Result> initialize(Connection c, Plan plan, Map<String, String> secrets, PasswordEncoder encoder, boolean employeeOnly) throws SQLException {
        validate(plan, secrets, employeeOnly);
        // Computing verifiers does not hold row locks and never changes existing credentials.
        Map<String, String> verifiers = new HashMap<>();
        for (UserSpec u : plan.users()) verifiers.put(u.passwordRef(), encoder.encode(secrets.get(u.passwordRef())));
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT @@port,DATABASE(),@@datadir")) {
            r.next();
            String expectedDatabase = System.getenv().getOrDefault("IAS_INITIALIZE_DATABASE", "ias_identity_test");
            if (r.getInt(1) != 3307 || !expectedDatabase.equals(r.getString(2)) || !"C:/ias-local/Develop/IAS-MySQL84/data/".equalsIgnoreCase(r.getString(3).replace('\\', '/')))
                throw new IllegalStateException("Dedicated initialization environment required");
        }
        if (!c.getAutoCommit()) throw new IllegalStateException("Initializer owns its transaction");
        try (var s = c.createStatement(); var r = s.executeQuery("SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('1','2','3') AND success=1")) {
            r.next();
            if (r.getInt(1) != 3) throw new IllegalStateException("Apply versioned migrations explicitly first");
        }
        c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        try (var s = c.prepareStatement("SELECT GET_LOCK(?,10)")) {
            s.setString(1, LOCK);
            try (var r = s.executeQuery()) {
                r.next();
                if (r.getInt(1) != 1) throw new IllegalStateException("Initialization busy");
            }
        }
        List<Result> result = new ArrayList<>();
        String trace = UUID.randomUUID().toString();
        boolean transactionClosed = false;
        try {
            c.setAutoCommit(false);
            Map<String, Long> tenants = new HashMap<>();
            for (TenantSpec t : plan.tenants()) {
                String code = AuthCrypto.tenantCode(t.code());
                Long id = null;
                try (var s = c.prepareStatement("SELECT id FROM tenant WHERE code_normalized=? FOR UPDATE")) {
                    s.setString(1, code);
                    try (var r = s.executeQuery()) {
                        if (r.next()) id = r.getLong(1);
                    }
                }
                boolean created = id == null;
                if (created) {
                    id = insert(c, "INSERT INTO tenant(code_normalized,name,status,created_at,updated_at) VALUES (?,?,1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", code, t.name());
                    audit(c, "BOOTSTRAP_TENANT", "TENANT", id, id, trace);
                }
                tenants.put(code, id);
                result.add(new Result("TENANT", id.toString(), code, created ? "CREATED" : "ALREADY_EXISTS"));
            }
            for (UserSpec u : plan.users()) {
                Long tenant = "PLATFORM".equals(u.realm()) ? null : tenants.get(AuthCrypto.tenantCode(u.tenantCode()));
                String namespace = tenant == null ? "PLATFORM" : "TENANT:" + tenant, account = AuthCrypto.account(u.account());
                String capability = tenant == null ? "identity.platform.session.revoke" : "identity.session.revoke";
                Long id = null;
                try (var s = c.prepareStatement("SELECT id,realm,tenant_id,identity_type,binding_state FROM identity_user WHERE login_namespace_id=? AND account_normalized=? FOR UPDATE")) {
                    s.setString(1, namespace);
                    s.setString(2, account);
                    try (var r = s.executeQuery()) {
                        if (r.next()) {
                            id = r.getLong(1);
                            Long actualTenant = r.getObject(3) == null ? null : r.getLong(3);
                            if (!u.realm().equals(r.getString(2)) || !Objects.equals(tenant, actualTenant) || !(tenant == null ? "PLATFORM" : "EMPLOYEE").equals(r.getString(4)) || r.getInt(5) != 0)
                                throw new IllegalStateException("Existing account ownership/identity differs; no overwrite");
                        }
                    }
                }
                boolean created = id == null;
                if (created) {
                    id = insert(c, "INSERT INTO identity_user(realm,tenant_id,account_normalized,identity_type,display_name,password_verifier,status,must_change_password,binding_state,created_at,updated_at) VALUES (?,?,?,?,?,?,1,1,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))", u.realm(), tenant, account, tenant == null ? "PLATFORM" : "EMPLOYEE", u.displayName(), verifiers.get(u.passwordRef()));
                    audit(c, "BOOTSTRAP_USER", "USER", id, tenant, trace);
                    if (u.administrator()) {
                        try (var s = c.prepareStatement("INSERT INTO identity_user_capability(user_id,capability_code,enabled,created_at,updated_at) VALUES (?,?,1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))")) {
                            s.setLong(1, id);
                            s.setString(2, capability);
                            s.executeUpdate();
                            if (tenant != null) { s.setString(2, "identity.employee.manage"); s.executeUpdate(); }
                        }
                        audit(c, "BOOTSTRAP_CAPABILITY", "USER", id, tenant, trace);
                    }
                } else {
                    Set<String> actual = new HashSet<>();
                    boolean disabled = false;
                    try (var s = c.prepareStatement("SELECT capability_code,enabled FROM identity_user_capability WHERE user_id=?")) {
                        s.setLong(1, id);
                        try (var r = s.executeQuery()) {
                            while (r.next()) {
                                actual.add(r.getString(1));
                                if (r.getInt(2) != 1) disabled = true;
                            }
                        }
                    }
                    Set<String> allowed = tenant == null ? Set.of(capability) : Set.of(capability, "identity.employee.manage", "asset.read", "asset.manage", "identity.customer.provision", "ticket.read");
                    if (!employeeOnly && (disabled || (u.administrator() ? (!actual.contains(capability) || !allowed.containsAll(actual)) : !actual.isEmpty())))
                        throw new IllegalStateException("Existing capability differs; do not restore or overwrite");
                }
                result.add(new Result("USER", id.toString(), account, created ? "CREATED" : "ALREADY_EXISTS"));
            }
            c.commit();
            transactionClosed = true;
            return List.copyOf(result);
        } catch (SQLException | RuntimeException e) {
            try {
                c.rollback();
                transactionClosed = true;
            } catch (SQLException ignored) {
            }
            throw e;
        } finally {
            // Never implicitly commit an uncertain transaction while cleaning up a failed rollback.
            try {
                if (transactionClosed) c.setAutoCommit(true);
            } finally {
                try (var s = c.prepareStatement("SELECT RELEASE_LOCK(?)")) {
                    s.setString(1, LOCK);
                    s.executeQuery().close();
                }
            }
        }
    }

    private static long insert(Connection c, String sql, Object... values) throws SQLException {
        try (var s = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            for (int i = 0; i < values.length; i++) s.setObject(i + 1, values[i]);
            s.executeUpdate();
            try (var r = s.getGeneratedKeys()) {
                r.next();
                return r.getLong(1);
            }
        }
    }

    private static void audit(Connection c, String action, String type, long id, Long tenant, String trace) throws SQLException {
        try (var s = c.prepareStatement("INSERT INTO identity_security_audit(occurred_at,action,actor_type,target_type,target_id,tenant_id,result,reason_code,trace_id) VALUES(UTC_TIMESTAMP(3),?,'SYSTEM',?,?,?,'SUCCESS','OFFLINE_INITIALIZATION',?)")) {
            s.setString(1, action);
            s.setString(2, type);
            s.setLong(3, id);
            s.setObject(4, tenant);
            s.setString(5, trace);
            s.executeUpdate();
        }
    }

    private static void validate(Plan plan, Map<String, String> secrets, boolean employeeOnly) {
        if (plan == null || plan.tenants() == null || plan.users() == null || plan.tenants().isEmpty() || plan.tenants().size() > 10 || plan.users().isEmpty() || plan.users().size() > 50)
            throw new IllegalArgumentException("Invalid bounded initialization plan");
        Set<String> codes = new HashSet<>(), accounts = new HashSet<>(), refs = new HashSet<>(), adminTenants = new HashSet<>();
        boolean platformAdmin = false;
        for (TenantSpec t : plan.tenants()) {
            text(t.name(), 100);
            if (!codes.add(AuthCrypto.tenantCode(t.code()))) throw new IllegalArgumentException("Duplicate tenant");
        }
        for (UserSpec u : plan.users()) {
            if (!Set.of("TENANT", "PLATFORM").contains(u.realm()) || employeeOnly && !"TENANT".equals(u.realm()))
                throw new IllegalArgumentException("Invalid realm");
            boolean platform = "PLATFORM".equals(u.realm());
            String code = platform ? null : AuthCrypto.tenantCode(u.tenantCode());
            if (platform ? u.tenantCode() != null : !codes.contains(code))
                throw new IllegalArgumentException("Invalid tenant location");
            text(u.displayName(), 50);
            if (!accounts.add((platform ? "PLATFORM" : code) + ":" + AuthCrypto.account(u.account())))
                throw new IllegalArgumentException("Duplicate account");
            if (u.passwordRef() == null || !u.passwordRef().matches("[A-Za-z0-9_-]{1,64}") || !refs.add(u.passwordRef()))
                throw new IllegalArgumentException("Invalid secret reference");
            AuthCrypto.newPassword(secrets.get(u.passwordRef()));
            if (u.administrator()) {
                if (platform) platformAdmin = true;
                else adminTenants.add(code);
            }
        }
        if ((!employeeOnly && !platformAdmin) || !adminTenants.containsAll(codes) || !secrets.keySet().equals(refs))
            throw new IllegalArgumentException("Plan requires explicit platform/tenant administrators and matching secret references");
    }

    private static void text(String value, int limit) {
        if (value == null || value.isBlank() || AuthCrypto.codePoints(value) > limit || value.codePoints().anyMatch(c -> Character.isISOControl(c) || (c >= 0xD800 && c <= 0xDFFF)))
            throw new IllegalArgumentException("Invalid display text");
    }
}

