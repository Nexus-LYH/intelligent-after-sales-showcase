package com.ias.identity;

import org.junit.jupiter.api.*;
import java.sql.*;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class MigrationTest {
    @BeforeAll static void migrate() { TestDatabase.migrate(); }
    @Test void versionedMigrationReplaysWithoutRecreatingTables() throws Exception {
        TestDatabase.migrate();
        assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success=1"));
        assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE version='2' AND success=1"));
        assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM flyway_schema_history WHERE version='3' AND success=1"));
        assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='identity_user_capability'"));
        assertEquals(0,TestDatabase.scalar("SELECT COUNT(*) FROM identity_session_context c JOIN identity_security_audit a ON a.target_type='SESSION' AND a.target_id=c.id WHERE a.action='CONFIRMATION_PROTOCOL_UPGRADE' AND c.revoked_at IS NULL"));
        assertEquals(6,TestDatabase.scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('tenant','identity_user','identity_session_context','identity_login_attempt','identity_revocation_operation','identity_security_audit')"));
    }
    @Test void realMysqlChecksGeneratedNamespaceUniqueConstraintsAndForeignKeys() throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);
        String code="IT_"+suffix.toUpperCase();
        TestDatabase.update("INSERT INTO tenant(code_normalized,name,status,created_at,updated_at) VALUES (?,'测试服务商',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",code);
        long tenant=TestDatabase.scalar("SELECT id FROM tenant WHERE code_normalized=?",code);
        TestDatabase.update("INSERT INTO tenant(code_normalized,name,status,created_at,updated_at) VALUES (?,'第二服务商',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",code+"_B");
        long second=TestDatabase.scalar("SELECT id FROM tenant WHERE code_normalized=?",code+"_B");
        // A deliberately non-loginable verifier is adequate for DDL tests; authentication fixtures use the real encoder.
        String insert="INSERT INTO identity_user(realm,tenant_id,account_normalized,identity_type,display_name,password_verifier,binding_state,created_at,updated_at) VALUES (?,?,?,?,?,'disabled-ddl-fixture',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))";
        TestDatabase.update(insert,"TENANT",tenant,"ddl."+suffix,"EMPLOYEE","测试员工",0);
        assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_user WHERE tenant_id=? AND login_namespace_id=?",tenant,"TENANT:"+tenant));
        assertThrows(SQLException.class,()->TestDatabase.update(insert,"TENANT",tenant,"ddl."+suffix,"CUSTOMER","测试客户",2));
        TestDatabase.update(insert,"TENANT",second,"ddl."+suffix,"CUSTOMER","测试客户",2);
        TestDatabase.update(insert,"PLATFORM",null,"platform."+suffix,"PLATFORM","平台测试",0);
        assertThrows(SQLException.class,()->TestDatabase.update(insert,"PLATFORM",null,"platform."+suffix,"PLATFORM","重复平台",0));
        assertThrows(SQLException.class,()->TestDatabase.update(insert,"PLATFORM",tenant,"invalid."+suffix,"PLATFORM","非法归属",0));
        assertThrows(SQLException.class,()->TestDatabase.update(insert,"TENANT",tenant,"Upper."+suffix,"EMPLOYEE","非法账号",0));
        assertThrows(SQLException.class,()->TestDatabase.update(insert,"TENANT",tenant,"pending."+suffix,"CUSTOMER","非法绑定",0));
        assertThrows(SQLException.class,()->TestDatabase.update("UPDATE tenant SET status=2 WHERE id=?",tenant));
        assertThrows(SQLException.class,()->TestDatabase.update("UPDATE identity_user SET must_change_password=2 WHERE tenant_id=?",tenant));
        assertThrows(SQLException.class,()->TestDatabase.update("DELETE FROM tenant WHERE id=?",tenant));
        assertThrows(SQLException.class,()->TestDatabase.update("UPDATE identity_user SET login_namespace_id='PLATFORM' WHERE tenant_id=?",tenant));
        assertEquals(0,TestDatabase.scalar("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND delete_rule='CASCADE'"));
        assertEquals(1,TestDatabase.scalar("SELECT @@port=3307 AND @@session.time_zone='+00:00' AND @@session.transaction_isolation='READ-COMMITTED'"));
    }
    @Test void malformedContextAndAttemptAreRejectedByDatabase() throws Exception {
        assertThrows(SQLException.class,()->TestDatabase.update("INSERT INTO identity_session_context(token_hash,kind,issued_at,expires_at,preauth_state,csrf_token,updated_at) VALUES (RANDOM_BYTES(32),'AUTH',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)+INTERVAL 10 MINUTE,'READY',REPEAT('a',43),UTC_TIMESTAMP(3))"));
        assertThrows(SQLException.class,()->TestDatabase.update("INSERT INTO identity_session_context(token_hash,kind,issued_at,expires_at,preauth_state,csrf_token,updated_at) VALUES (RANDOM_BYTES(32),'PREAUTH',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)-INTERVAL 1 SECOND,'READY',REPEAT('a',43),UTC_TIMESTAMP(3))"));
        assertThrows(SQLException.class,()->TestDatabase.update("INSERT INTO identity_login_attempt(attempt_id,preauth_context_id,expected_realm,expected_account_normalized,status,created_at,retain_until) VALUES ('not-a-uuid',-1,'PLATFORM','fixture','PROCESSING',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))"));
    }
    @Test void applicationRoleCannotCreateTablesOrRewriteSecurityHistory() throws Exception {
        try (Connection connection=DriverManager.getConnection(TestDatabase.URL,System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD")); Statement statement=connection.createStatement()) {
            assertThrows(SQLException.class,()->statement.executeUpdate("CREATE TABLE it_denied_runtime (id INT)"));
            assertThrows(SQLException.class,()->statement.executeUpdate("UPDATE identity_security_audit SET reason_code='forbidden' WHERE 1=0"));
            assertThrows(SQLException.class,()->statement.executeUpdate("DELETE FROM identity_security_audit WHERE 1=0"));
            assertThrows(SQLException.class,()->statement.executeUpdate("UPDATE tenant SET name=name WHERE 1=0"));
        }
    }
}
