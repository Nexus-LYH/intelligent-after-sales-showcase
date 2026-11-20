package com.ias.identity;

import com.ias.identity.auth.AuthCrypto;
import com.ias.identity.bootstrap.AccountInitializer;
import org.junit.jupiter.api.*;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class BootstrapTest {
    private final Argon2PasswordEncoder encoder=new Argon2PasswordEncoder(16,32,1,19456,2);
    private AccountInitializer.Plan plan;private Map<String,String> secrets;private String code,account;
    @BeforeAll static void migrate(){TestDatabase.migrate();}
    @BeforeEach void inputs(){
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,12);code="BI_"+suffix.toUpperCase();account="init."+suffix;
        plan=new AccountInitializer.Plan(List.of(new AccountInitializer.TenantSpec(code,"初始化事务测试")),List.of(new AccountInitializer.UserSpec("PLATFORM",null,"p."+suffix,"初始化平台",true,"p"),new AccountInitializer.UserSpec("TENANT",code,account,"初始化管理员",true,"a")));
        secrets=Map.of("p",new AuthCrypto().randomToken(),"a",new AuthCrypto().randomToken());
    }
    private List<AccountInitializer.Result> run()throws Exception{try(var c=TestDatabase.connection()){return AccountInitializer.initialize(c,plan,secrets,encoder);}}
    private long userId()throws Exception{return TestDatabase.scalar("SELECT id FROM identity_user WHERE account_normalized=?",account);}
    @Test void firstRepeatedAndConcurrentInitializationNeverAddsDuplicateFacts()throws Exception{
        CountDownLatch start=new CountDownLatch(1);List<AccountInitializer.Result> first,second;
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var a=executor.submit(()->{start.await();return run();});var b=executor.submit(()->{start.await();return run();});start.countDown();first=a.get(15,TimeUnit.SECONDS);second=b.get(15,TimeUnit.SECONDS);
        }
        assertEquals(Set.of("CREATED","ALREADY_EXISTS"),Set.of(first.getFirst().state(),second.getFirst().state()));
        assertEquals(first.stream().map(AccountInitializer.Result::id).toList(),second.stream().map(AccountInitializer.Result::id).toList());
        assertTrue(run().stream().allMatch(r->r.state().equals("ALREADY_EXISTS")));
        assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM tenant WHERE code_normalized=?",code));assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_user WHERE account_normalized=?",account));
        assertEquals(2,TestDatabase.scalar("SELECT COUNT(*) FROM identity_user_capability WHERE user_id=?",userId()));assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_user_capability WHERE user_id=? AND capability_code='identity.employee.manage' AND enabled=1",userId()));assertEquals(2,TestDatabase.scalar("SELECT COUNT(*) FROM identity_security_audit WHERE target_type='USER' AND target_id=? AND action LIKE 'BOOTSTRAP%'",userId()));
    }
    @Test void existingPasswordFlagsVersionsAndDisabledGrantAreNeverResetOrRestored()throws Exception{
        run();long id=userId();String changed=encoder.encode(new AuthCrypto().randomToken());
        TestDatabase.update("UPDATE identity_user SET password_verifier=?,must_change_password=0,status=0,security_version=9,row_version=8 WHERE id=?",changed,id);
        run();assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_user WHERE id=? AND BINARY password_verifier=BINARY ? AND must_change_password=0 AND status=0 AND security_version=9 AND row_version=8",id,changed));
        TestDatabase.update("UPDATE identity_user_capability SET enabled=0 WHERE user_id=?",id);
        assertThrows(IllegalStateException.class,this::run);assertEquals(0,TestDatabase.scalar("SELECT enabled FROM identity_user_capability WHERE user_id=?",id));
    }
    @Test void roleOrIdentityMismatchFailsWithoutTakingOverExistingAccount()throws Exception{
        run();long id=userId();TestDatabase.update("UPDATE identity_user SET identity_type='CUSTOMER',binding_state=2 WHERE id=?",id);
        assertThrows(IllegalStateException.class,this::run);assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_user WHERE id=? AND identity_type='CUSTOMER' AND binding_state=2",id));
    }
    @Test void bootstrapAuditFailureRollsBackEntireNewPlan()throws Exception{
        long high=TestDatabase.scalar("SELECT COALESCE(MAX(id),0) FROM tenant");String constraint="it_bootstrap_audit_"+code.substring(3);
        TestDatabase.update("ALTER TABLE identity_security_audit ADD CONSTRAINT "+constraint+" CHECK (action<>'BOOTSTRAP_TENANT' OR target_id<="+high+")");
        try{assertThrows(SQLException.class,this::run);assertEquals(0,TestDatabase.scalar("SELECT COUNT(*) FROM tenant WHERE code_normalized=?",code));assertEquals(0,TestDatabase.scalar("SELECT COUNT(*) FROM identity_user WHERE account_normalized=?",account));}
        finally{TestDatabase.update("ALTER TABLE identity_security_audit DROP CHECK "+constraint);}
        assertTrue(run().stream().allMatch(r->r.state().equals("CREATED")));
    }
    @Test void invalidPlanAndWeakInitialPasswordCannotWrite()throws Exception{
        secrets=Map.of("p","short","a",new AuthCrypto().randomToken());assertThrows(RuntimeException.class,this::run);assertEquals(0,TestDatabase.scalar("SELECT COUNT(*) FROM tenant WHERE code_normalized=?",code));
    }
    @Test void tenantOnlyEmployeeInitializationNeedsNoPlatformAndPreservesLaterSecurityConfiguration()throws Exception{
        var employeePlan=new AccountInitializer.Plan(plan.tenants(),List.of(plan.users().get(1)));var employeeSecrets=Map.of("a",secrets.get("a"));
        try(var c=TestDatabase.connection()){assertTrue(AccountInitializer.initializeEmployees(c,employeePlan,employeeSecrets,encoder).stream().allMatch(r->r.state().equals("CREATED")));}
        long id=userId();TestDatabase.update("UPDATE identity_user SET status=0,must_change_password=0,security_version=8,row_version=7 WHERE id=?",id);TestDatabase.update("UPDATE identity_user_capability SET enabled=0 WHERE user_id=?",id);
        TestDatabase.update("INSERT INTO identity_user_capability(user_id,capability_code,enabled,created_at,updated_at) VALUES(?,'ticket.read',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id);
        try(var c=TestDatabase.connection()){assertTrue(AccountInitializer.initializeEmployees(c,employeePlan,employeeSecrets,encoder).stream().allMatch(r->r.state().equals("ALREADY_EXISTS")));}
        assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_user WHERE id=? AND status=0 AND must_change_password=0 AND security_version=8 AND row_version=7",id));assertEquals(0,TestDatabase.scalar("SELECT SUM(enabled) FROM identity_user_capability WHERE user_id=?",id));
        assertEquals(0,TestDatabase.scalar("SELECT COUNT(*) FROM identity_user WHERE account_normalized=?",plan.users().getFirst().account()));
    }
}
