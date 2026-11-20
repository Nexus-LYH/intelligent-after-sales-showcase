package com.ias.identity;

import com.ias.identity.auth.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties={"server.address=127.0.0.1","identity.auth.login-limit=200","identity.auth.csrf-limit=500"})
@ContextConfiguration(initializers=AuthFlowTest.MigrateBeforeContext.class)
@Import(AuthFlowTest.Support.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthFlowTest {
    static final AtomicReference<Runnable> AFTER_CONTEXT_LOOKUP=new AtomicReference<>();
    public static class MigrateBeforeContext implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override public void initialize(ConfigurableApplicationContext context) { TestDatabase.migrate(); }
    }
    static final class MutableClock extends Clock {
        final AtomicReference<Instant> time=new AtomicReference<>(Instant.parse("2026-10-14T12:00:00Z"));
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time.get(); }
        void advance(Duration duration) { time.updateAndGet(t->t.plus(duration)); }
    }
    static final class HookEncoder implements PasswordEncoder {
        final PasswordEncoder actual=new Argon2PasswordEncoder(16,32,1,19456,2);
        final AtomicReference<Runnable> afterMatches=new AtomicReference<>();
        @Override public String encode(CharSequence raw) { return actual.encode(raw); }
        @Override public boolean matches(CharSequence raw,String encoded) {
            boolean match=actual.matches(raw,encoded);
            Runnable hook=afterMatches.getAndSet(null);
            if (hook!=null) hook.run();
            return match;
        }
    }
    static final class HookTransactions extends TransactionTemplate {
        final AtomicReference<Runnable> afterCommit=new AtomicReference<>();
        HookTransactions(PlatformTransactionManager manager) {
            super(manager); setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED); setTimeout(5);
        }
        @Override public <T> T execute(TransactionCallback<T> callback) {
            T result=super.execute(callback);
            Runnable hook=afterCommit.getAndSet(null);
            if (hook!=null) hook.run();
            return result;
        }
    }
    @TestConfiguration static class Support {
        @Bean static BeanPostProcessor contextLookupCoordinator() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean,String name) {
                    if (!(bean instanceof com.ias.identity.persistence.AuthMapper)) return bean;
                    return java.lang.reflect.Proxy.newProxyInstance(bean.getClass().getClassLoader(),new Class<?>[]{com.ias.identity.persistence.AuthMapper.class},(proxy,method,args)-> {
                        Object result;
                        try { result=method.invoke(bean,args); }
                        catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                        if (method.getName().equals("contextByHash")) {
                            Runnable hook=AFTER_CONTEXT_LOOKUP.getAndSet(null);
                            if (hook!=null) hook.run();
                        }
                        return result;
                    });
                }
            };
        }
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
        @Bean @Primary HookEncoder testEncoder() { return new HookEncoder(); }
        @Bean @Primary HookTransactions testTransactions(PlatformTransactionManager manager) { return new HookTransactions(manager); }
    }
    @LocalServerPort int port;
    @Autowired MutableClock clock;
    @Autowired HookEncoder encoder;
    @Autowired AuthService service;
    @Autowired HookTransactions transactions;
    @Autowired ConfigurableApplicationContext applicationContext;
    private final JsonMapper json=JsonMapper.builder().build();
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private String suffix,codeA,codeB,account,password,platformAccount;
    private long tenantA,tenantB,userA,userB,customer,restricted,platform;
    private record Preauth(String cookie,String csrf) { @Override public String toString() { return "Preauth[redacted]"; } }
    private static final class Logged {
        String cookie;
        final String attempt;
        final Map<String,Object> identity;
        Logged(String cookie,String attempt,Map<String,Object> identity) { this.cookie=cookie; this.attempt=attempt; this.identity=identity; }
        @Override public String toString() { return "Logged[redacted]"; }
    }
    @BeforeAll void fixtures() throws Exception {
        suffix=UUID.randomUUID().toString().replace("-","").substring(0,10);
        codeA="IT_"+suffix.toUpperCase()+"A"; codeB="IT_"+suffix.toUpperCase()+"B";
        account="it."+suffix; platformAccount="platform."+suffix;
        password=new AuthCrypto().randomToken();
        String verifier=encoder.encode(password);
        for (String code:List.of(codeA,codeB)) TestDatabase.update("INSERT INTO tenant(code_normalized,name,status,created_at,updated_at) VALUES (?,'闭环测试租户',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",code);
        tenantA=TestDatabase.scalar("SELECT id FROM tenant WHERE code_normalized=?",codeA);
        tenantB=TestDatabase.scalar("SELECT id FROM tenant WHERE code_normalized=?",codeB);
        userA=user("TENANT",tenantA,account,"EMPLOYEE",0,0,verifier);
        userB=user("TENANT",tenantB,account,"EMPLOYEE",0,0,verifier);
        customer=user("TENANT",tenantA,"customer."+suffix,"CUSTOMER",1,0,verifier);
        restricted=user("TENANT",tenantA,"restricted."+suffix,"EMPLOYEE",0,1,verifier);
        platform=user("PLATFORM",null,platformAccount,"PLATFORM",0,0,verifier);
    }
    @BeforeEach void resetClock() { clock.time.set(Instant.parse("2026-10-14T12:00:00Z")); encoder.afterMatches.set(null); transactions.afterCommit.set(null); AFTER_CONTEXT_LOOKUP.set(null); }
    private long user(String realm,Long tenant,String name,String type,int binding,int change,String verifier) throws Exception {
        TestDatabase.update("INSERT INTO identity_user(realm,tenant_id,account_normalized,identity_type,display_name,password_verifier,status,must_change_password,binding_state,created_at,updated_at) VALUES (?,?,?,?,'闭环测试用户',?,1,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",realm,tenant,name,type,verifier,change,binding);
        return TestDatabase.scalar("SELECT id FROM identity_user WHERE login_namespace_id=? AND account_normalized=?",tenant==null ? "PLATFORM":"TENANT:"+tenant,name);
    }
    private HttpResponse<String> request(String method,String path,String cookie,String body,Map<String,String> headers) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(12));
        if (cookie!=null) builder.header("Cookie",cookie);
        for (var header:headers.entrySet()) builder.header(header.getKey(),header.getValue());
        if (method.equals("POST")) builder.header("Content-Type","application/json");
        builder.method(method,body==null ? HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }
    @SuppressWarnings("unchecked") private Map<String,Object> body(HttpResponse<String> response) { return json.readValue(response.body(),Map.class); }
    private String code(HttpResponse<String> response) { return (String)body(response).get("code"); }
    private void status(int expected,HttpResponse<String> response) { assertEquals(expected,response.statusCode(),"Unexpected HTTP status; response secrets intentionally not printed"); }
    private String cookie(HttpResponse<String> response,String name) {
        return response.headers().allValues("Set-Cookie").stream().filter(v->v.startsWith(name+"=")&&!v.contains("Max-Age=0")).map(v->v.split(";",2)[0]).findFirst().orElseThrow();
    }
    private Preauth preauth() throws Exception {
        var response=request("GET","/api/auth/csrf",null,null,Map.of()); status(200,response);
        return new Preauth(cookie(response,AuthService.PREAUTH_COOKIE),(String)body(response).get("token"));
    }
    private HttpResponse<String> login(Preauth pre,String attempt,String realm,String tenant,String name,String raw) throws Exception {
        var payload=new LinkedHashMap<String,Object>(); payload.put("realm",realm); payload.put("tenantCode",tenant); payload.put("account",name); payload.put("password",raw);
        return request("POST","/api/auth/login",pre.cookie,json.writeValueAsString(payload),Map.of("Origin","http://127.0.0.1:8081","X-CSRF-TOKEN",pre.csrf,"X-Login-Attempt-Id",attempt));
    }
    private Logged logged(String realm,String tenant,String name) throws Exception {
        Preauth pre=preauth(); String attempt=UUID.randomUUID().toString();
        var response=login(pre,attempt,realm,tenant,name,password); status(200,response);
        return new Logged(cookie(response,AuthService.AUTH_COOKIE),attempt,body(response));
    }
    private HttpResponse<String> me(Logged logged) throws Exception {
        var response=request("GET","/api/auth/me",logged.cookie,null,Map.of());
        if (response.statusCode()==200) {
            response.headers().allValues("Set-Cookie").stream()
                .filter(v->v.startsWith(AuthService.AUTH_COOKIE+"=")&&!v.contains("Max-Age=0"))
                .findFirst().ifPresent(v->logged.cookie=v.split(";",2)[0]);
        }
        return response;
    }
    @Test @Order(1) void normalTenantAndPlatformLoginMatchAndLogoutOnlyCurrentSession() throws Exception {
        Logged first=logged("TENANT",codeA.toLowerCase(),account.toUpperCase());
        var firstMe=me(first); status(200,firstMe);
        assertEquals(first.attempt,body(firstMe).get("loginAttemptId")); assertEquals(Long.toString(userA),body(firstMe).get("userId"));
        assertEquals(account,body(firstMe).get("account")); assertFalse(firstMe.body().contains("passwordVerifier")); assertFalse(firstMe.body().contains("customerCompany"));
        assertFalse(firstMe.body().contains(first.cookie.split("=",2)[1])); assertEquals("no-store",firstMe.headers().firstValue("Cache-Control").orElseThrow());
        Logged other=logged("TENANT",codeA,account); status(200,me(other));
        Logged secondTenant=logged("TENANT",codeB,account); status(200,me(secondTenant)); assertEquals(Long.toString(userB),secondTenant.identity.get("userId"));
        Logged platformLogin=logged("PLATFORM",null,platformAccount); status(200,me(platformLogin)); assertNull(platformLogin.identity.get("tenant"));
        var csrf=request("GET","/api/auth/csrf",first.cookie,null,Map.of()); status(200,csrf);
        assertEquals("AUTH",body(csrf).get("contextKind")); assertEquals(first.attempt,body(csrf).get("loginAttemptId"));
        var again=request("GET","/api/auth/csrf",first.cookie,null,Map.of()); assertTrue(Objects.equals(body(csrf).get("token"),body(again).get("token")),"CSRF should stay stable");
        var logout=request("POST","/api/auth/logout",first.cookie,null,Map.of("Origin","http://127.0.0.1:8081","X-CSRF-TOKEN",(String)body(csrf).get("token"))); status(204,logout);
        status(401,me(first)); status(200,me(other));
        status(204,request("POST","/api/auth/logout",first.cookie,null,Map.of("Origin","http://127.0.0.1:8081")));
        assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_security_audit WHERE action='LOGOUT' AND login_attempt_id=?",first.attempt));
        String authHeader=logout.headers().allValues("Set-Cookie").getFirst(); assertTrue(authHeader.contains("HttpOnly")&&authHeader.contains("SameSite=Lax")&&authHeader.contains("Path=/"));
    }
    @Test @Order(2) void failuresDoNotExposeAccountStateAndValidateBeforeClaim() throws Exception {
        for (String name:List.of("unknown."+suffix,"customer."+suffix)) {
            var response=login(preauth(),UUID.randomUUID().toString(),"TENANT",codeA,name,password); status(401,response); assertEquals("AUTHENTICATION_FAILED",code(response));
            assertEquals("NEW_PREAUTH",body(response).get("loginRecovery"));
        }
        TestDatabase.update("UPDATE identity_user SET status=0 WHERE id=?",userA);
        try { var response=login(preauth(),UUID.randomUUID().toString(),"TENANT",codeA,account,password); status(401,response); assertEquals("AUTHENTICATION_FAILED",code(response)); }
        finally { TestDatabase.update("UPDATE identity_user SET status=1 WHERE id=?",userA); }
        TestDatabase.update("UPDATE tenant SET status=0,security_epoch=security_epoch+1 WHERE id=?",tenantA);
        try { status(401,login(preauth(),UUID.randomUUID().toString(),"TENANT",codeA,account,password)); }
        finally { TestDatabase.update("UPDATE tenant SET status=1 WHERE id=?",tenantA); }
        Preauth pre=preauth(); String attempt=UUID.randomUUID().toString();
        var bad=login(pre,attempt,"TENANT",codeA," "+account,password); status(400,bad); assertEquals("REUSE_PREAUTH",body(bad).get("loginRecovery"));
        assertEquals(0,TestDatabase.scalar("SELECT COUNT(*) FROM identity_login_attempt WHERE attempt_id=?",attempt));
        status(200,login(pre,attempt,"TENANT",codeA,account,password));
        status(401,login(preauth(),UUID.randomUUID().toString(),"TENANT",codeA,account,"wrong-password"));
    }
    @Test @Order(3) void sourceCsrfCookiePriorityAndDeniedRoutes() throws Exception {
        Preauth pre=preauth();
        status(403,request("POST","/api/auth/login",pre.cookie,"{}",Map.of("Origin","https://evil.invalid")));
        status(403,request("POST","/api/auth/logout",null,null,Map.of()));
        status(403,request("GET","/api/auth/csrf",null,null,Map.of("Sec-Fetch-Site","cross-site")));
        var missing= request("POST","/api/auth/login",pre.cookie,json.writeValueAsString(Map.of("realm","TENANT","tenantCode",codeA,"account",account,"password",password)),Map.of("Origin","http://127.0.0.1:8081","X-Login-Attempt-Id",UUID.randomUUID().toString()));
        status(403,missing); assertEquals("CSRF_INVALID",code(missing));
        Preauth different=preauth(); status(403,login(new Preauth(pre.cookie,different.csrf),UUID.randomUUID().toString(),"TENANT",codeA,account,password));
        status(400,request("GET","/api/auth/csrf",pre.cookie+"; "+pre.cookie,null,Map.of()));
        Logged auth=logged("TENANT",codeA,account);
        var direct=request("POST","/api/auth/login",auth.cookie+"; "+pre.cookie,"{}",Map.of("Origin","http://127.0.0.1:8081")); status(409,direct); assertEquals("LOGIN_REQUIRES_LOGOUT",code(direct));
        status(401,request("GET","/api/auth/csrf",AuthService.AUTH_COOKIE+"=invalid; "+pre.cookie,null,Map.of()));
        status(403,request("GET","/internal/auth/introspect",auth.cookie,null,Map.of("X-User-Id","1","X-Tenant-Id","1")));
        var deniedRoute=request("POST","/api/identity/users/1/permissions",auth.cookie,"{}",Map.of("Origin","http://127.0.0.1:8081"));
        status(403,deniedRoute); assertTrue(deniedRoute.headers().allValues("Set-Cookie").isEmpty(),"Denied routes must not create a framework JSESSIONID");
        var invalidRevoke=request("POST","/api/identity/users/1/sessions/revoke",auth.cookie,"{}",Map.of("Origin","http://127.0.0.1:8081"));
        status(400,invalidRevoke); assertTrue(invalidRevoke.headers().allValues("Set-Cookie").isEmpty());
        status(403,request("POST","/api/auth/logout",auth.cookie,null,Map.of("Origin","http://127.0.0.1:8081","X-CSRF-TOKEN",pre.csrf)));
    }
    @Test @Order(4) void oneAttemptPerContextAndGlobalAttemptConflictDoesNotOccupyNewContext() throws Exception {
        Preauth pre=preauth(); String attempt=UUID.randomUUID().toString();
        try (ExecutorService pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch start=new CountDownLatch(1);
            var left=pool.submit(()->{ start.await(); return login(pre,attempt,"TENANT",codeA,account,password); });
            var right=pool.submit(()->{ start.await(); return login(pre,attempt,"TENANT",codeA,account,password); });
            start.countDown(); Set<Integer> statuses=Set.of(left.get().statusCode(),right.get().statusCode()); assertEquals(Set.of(200,409),statuses);
        }
        assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_session_context WHERE login_attempt_id=?",attempt));
        status(409,login(pre,attempt,"TENANT",codeA,account,"different-password"));
        Preauth fresh=preauth(); status(409,login(fresh,attempt,"TENANT",codeA,account,password));
        status(200,login(fresh,UUID.randomUUID().toString(),"TENANT",codeA,account,password));
    }
    @Test @Order(5) void unknownDeliveryRecoveryRequiresAttemptMatchAndHasFiniteWindow() throws Exception {
        Logged delivered=logged("TENANT",codeA,account);
        var actual=me(delivered); status(200,actual); assertEquals(delivered.attempt,body(actual).get("loginAttemptId"));
        // Client intentionally discards the second Set-Cookie, modelling no credential delivery at the HTTP-client layer.
        Preauth pre=preauth(); String abandoned=UUID.randomUUID().toString(); status(200,login(pre,abandoned,"TENANT",codeA,account,password));
        status(401,request("GET","/api/auth/me",pre.cookie,null,Map.of()));
        assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_session_context WHERE login_attempt_id=? AND delivery_state='UNCONFIRMED'",abandoned));
        assertNotEquals(abandoned,body(me(delivered)).get("loginAttemptId"));
        clock.advance(Duration.ofMinutes(10));
        assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_session_context WHERE login_attempt_id=? AND delivery_state='UNCONFIRMED'",abandoned));
        status(401,login(pre,abandoned,"TENANT",codeA,account,password));
    }
    @Test @Order(6) void millisecondBoundariesAndRestrictedAbsoluteLifetime() throws Exception {
        for (long offset:List.of(599999L,600000L,600001L)) {
            resetClock(); Preauth pre=preauth(); clock.advance(Duration.ofMillis(offset));
            var response=login(pre,UUID.randomUUID().toString(),"TENANT",codeA,account,password);
            status(offset<600000 ? 200:401,response);
            if (offset<600000) { String auth=cookie(response,AuthService.AUTH_COOKIE); clock.advance(Duration.ofMillis(1)); status(401,request("GET","/api/auth/me",auth,null,Map.of())); }
        }
        for (long offset:List.of(599999L,600000L,600001L)) {
            resetClock(); Logged auth=logged("TENANT",codeA,account); clock.advance(Duration.ofMillis(offset)); status(offset<600000 ? 200:401,me(auth));
        }
        resetClock(); Logged auth=logged("TENANT",codeA,account); status(200,me(auth));
        clock.advance(Duration.ofHours(8).minusMillis(1)); status(200,me(auth)); clock.advance(Duration.ofMillis(1)); status(401,me(auth));
        resetClock(); Logged limited=logged("TENANT",codeA,"restricted."+suffix); assertEquals("PASSWORD_CHANGE_REQUIRED",limited.identity.get("accessState")); status(200,me(limited));
        status(403,request("GET","/api/tickets",limited.cookie,null,Map.of()));
        clock.advance(Duration.ofMinutes(15).minusMillis(1)); status(200,me(limited)); clock.advance(Duration.ofMillis(1)); status(401,me(limited));
    }
    @Test @Order(7) void securityChangeBeforeIssuanceAndBeforeOrAfterConfirmationNeverRevivesSession() throws Exception {
        Preauth pre=preauth(); String attempt=UUID.randomUUID().toString();
        encoder.afterMatches.set(()-> {
            try { TestDatabase.update("UPDATE identity_user SET security_version=security_version+1,row_version=row_version+1 WHERE id=?",userA); }
            catch (SQLException e) { throw new IllegalStateException("Race fixture failed",e); }
        });
        status(401,login(pre,attempt,"TENANT",codeA,account,password));
        assertEquals(0,TestDatabase.scalar("SELECT COUNT(*) FROM identity_session_context WHERE login_attempt_id=?",attempt));
        Logged beforeConfirmation=logged("TENANT",codeA,account);
        TestDatabase.update("UPDATE identity_user SET status=0,security_version=security_version+1 WHERE id=?",userA);
        status(401,me(beforeConfirmation));
        TestDatabase.update("UPDATE identity_user SET status=1 WHERE id=?",userA); status(401,me(beforeConfirmation));
        Logged confirmed=logged("TENANT",codeA,account); status(200,me(confirmed));
        TestDatabase.update("UPDATE tenant SET security_epoch=security_epoch+1 WHERE id=?",tenantA); status(401,me(confirmed));
    }
    @Test @Order(8) void processingInterruptionAndAuditFailureDoNotIssueOrResetContext() throws Exception {
        Preauth pre=preauth(); String attempt=UUID.randomUUID().toString();
        String constraint="it_audit_fail_"+suffix;
        TestDatabase.update("ALTER TABLE identity_security_audit ADD CONSTRAINT "+constraint+" CHECK (NOT (action='LOGIN_ISSUED' AND login_attempt_id='"+attempt+"'))");
        try {
            var response=login(pre,attempt,"TENANT",codeA,account,password); status(503,response);
            assertTrue(response.headers().allValues("Set-Cookie").isEmpty());
            assertEquals(0,TestDatabase.scalar("SELECT COUNT(*) FROM identity_session_context WHERE login_attempt_id=?",attempt));
            assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_login_attempt WHERE attempt_id=? AND status='PROCESSING'",attempt));
            var csrf=request("GET","/api/auth/csrf",pre.cookie,null,Map.of()); status(409,csrf); assertEquals("LOGIN_IN_PROGRESS",code(csrf));
            status(409,login(pre,attempt,"TENANT",codeA,account,password));
        } finally { TestDatabase.update("ALTER TABLE identity_security_audit DROP CHECK "+constraint); }
    }
    @Test @Order(9) void securityChangeCommittedDuringLockWaitPreventsConfirmation() throws Exception {
        Logged auth=logged("TENANT",codeA,account);
        try (Connection blocker=TestDatabase.connection(); ExecutorService pool=Executors.newSingleThreadExecutor()) {
            blocker.setAutoCommit(false);
            try (PreparedStatement lock=blocker.prepareStatement("SELECT id FROM tenant WHERE id=? FOR UPDATE")) {
                lock.setLong(1,tenantA); lock.executeQuery().close();
            }
            var pending=pool.submit(()->me(auth));
            assertThrows(TimeoutException.class,()->pending.get(150,TimeUnit.MILLISECONDS));
            try (PreparedStatement change=blocker.prepareStatement("UPDATE tenant SET security_epoch=security_epoch+1,row_version=row_version+1 WHERE id=?")) {
                change.setLong(1,tenantA); change.executeUpdate();
            }
            blocker.commit(); status(401,pending.get(5,TimeUnit.SECONDS));
            assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_session_context WHERE login_attempt_id=? AND delivery_state='UNCONFIRMED'",auth.attempt));
        }
    }
    @Test @Order(10) void delayedConfirmationCommitCannotMakeExpiredDeliveryValidLater() throws Exception {
        Logged auth=logged("TENANT",codeA,account);
        byte[] originalHash=new AuthCrypto().hashToken(auth.cookie.split("=",2)[1]);
        transactions.afterCommit.set(()->clock.advance(Duration.ofMinutes(10)));
        status(401,me(auth)); status(401,me(auth));
        assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_session_context WHERE login_attempt_id=? AND delivery_state='CONFIRMED' AND token_hash<>?",auth.attempt,originalHash));
    }
    @Test @Order(11) void hashOnlyStorageAndUtcMillisecondsRoundTrip() throws Exception {
        Logged auth=logged("TENANT",codeA,account);
        byte[] hash=new AuthCrypto().hashToken(auth.cookie.split("=",2)[1]);
        assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_session_context WHERE login_attempt_id=? AND token_hash=? AND OCTET_LENGTH(token_hash)=32",auth.attempt,hash));
        assertEquals(1,TestDatabase.scalar("SELECT CAST('2026-10-14 12:34:56.123' AS DATETIME(3))='2026-10-14 12:34:56.123'"));
        AuthFailure failure=assertThrows(AuthFailure.class,()->AuthService.budget(System.nanoTime())); assertEquals(503,failure.status);
    }
    @Test @Order(12) void exhaustedRequestBudgetAfterConfirmationCommitMustNotRevive() throws Exception {
        Logged auth=logged("TENANT",codeA,account);
        transactions.afterCommit.set(()-> {
            clock.advance(Duration.ofMinutes(10));
            try { Thread.sleep(10_500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        });
        status(503,me(auth));
        status(401,me(auth));
    }
    @Test @Order(13) void failedLateCompensationCannotLeaveOriginalCredentialUsable() throws Exception {
        Logged auth=logged("TENANT",codeA,account);
        String constraint="it_confirmation_audit_"+suffix;
        TestDatabase.update("ALTER TABLE identity_security_audit ADD CONSTRAINT "+constraint+" CHECK (NOT (action='DELIVERY_TIMEOUT' AND login_attempt_id='"+auth.attempt+"'))");
        try {
            transactions.afterCommit.set(()->clock.advance(Duration.ofMinutes(10)));
            var first=me(auth); assertTrue(Set.of(401,503).contains(first.statusCode()));
            assertTrue(first.headers().allValues("Set-Cookie").stream().noneMatch(v->v.startsWith(AuthService.AUTH_COOKIE+"=")&&!v.contains("Max-Age=0")));
            status(401,me(auth));
        } finally { TestDatabase.update("ALTER TABLE identity_security_audit DROP CHECK "+constraint); }
    }
    @Test @Order(14) void postCommitDatabaseExceptionAndFreshServiceCannotReviveCredential() throws Exception {
        Logged auth=logged("TENANT",codeA,account);
        transactions.afterCommit.set(()-> {
            clock.advance(Duration.ofMinutes(10));
            throw new org.springframework.transaction.CannotCreateTransactionException("Injected post-commit database fault");
        });
        var first=me(auth); status(503,first); assertTrue(first.headers().allValues("Set-Cookie").isEmpty());
        status(401,me(auth));
        var fresh=new AuthService(applicationContext.getBean(com.ias.identity.persistence.AuthMapper.class),new AuthCrypto(),encoder,
            new TransactionTemplate(applicationContext.getBean(PlatformTransactionManager.class)),clock,
            applicationContext.getBean(com.ias.identity.persistence.RevocationMapper.class),applicationContext.getBean(com.ias.identity.persistence.AccountMapper.class));
        AuthFailure rejected=assertThrows(AuthFailure.class,()->fresh.me(auth.cookie.split("=",2)[1],System.nanoTime()+10_000_000_000L));
        assertEquals(401,rejected.status);
    }
    @Test @Order(15) void concurrentOriginalCredentialCannotPassBeforePostCommitProcessing() throws Exception {
        Logged auth=logged("TENANT",codeA,account);
        CountDownLatch committed=new CountDownLatch(1), release=new CountDownLatch(1);
        transactions.afterCommit.set(()-> {
            committed.countDown();
            try { if (!release.await(3,TimeUnit.SECONDS)) throw new IllegalStateException("Test coordinator did not release confirmation"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        });
        try (ExecutorService executor=Executors.newSingleThreadExecutor()) {
            String original=auth.cookie;
            var first=executor.submit(()->request("GET","/api/auth/me",original,null,Map.of()));
            try {
                assertTrue(committed.await(3,TimeUnit.SECONDS));
                status(401,request("GET","/api/auth/me",original,null,Map.of()));
                clock.advance(Duration.ofMinutes(10));
            } finally { release.countDown(); }
            status(401,first.get(3,TimeUnit.SECONDS)); status(401,me(auth));
        }
    }
    @Test @Order(16) void confirmationReplyLossRetiresOriginalAndSuccessfulConfirmationRotatesOnlyOnce() throws Exception {
        Logged lost=logged("TENANT",codeA,account); String original=lost.cookie;
        var confirmed=request("GET","/api/auth/me",original,null,Map.of()); status(200,confirmed);
        String current=cookie(confirmed,AuthService.AUTH_COOKIE);
        assertFalse(current.equals(original),"First confirmation must replace the credential");
        status(401,request("GET","/api/auth/me",original,null,Map.of()));
        var again=request("GET","/api/auth/me",current,null,Map.of()); status(200,again);
        assertTrue(again.headers().allValues("Set-Cookie").isEmpty(),"Confirmed sessions do not rotate or slide again");
        assertEquals(confirmed.statusCode(),again.statusCode());
        assertEquals(body(confirmed).get("expiresAt"),body(again).get("expiresAt"));
    }
    @Test @Order(17) void retiredCredentialIsRejectedByAnIndependentApplicationProcess() throws Exception {
        Logged auth=logged("TENANT",codeA,account);
        transactions.afterCommit.set(()-> { throw new org.springframework.transaction.CannotCreateTransactionException("Injected interruption after commit"); });
        status(503,me(auth)); status(401,me(auth));
        java.nio.file.Path jar=java.nio.file.Path.of("target/identity-0.0.1-SNAPSHOT.jar").toAbsolutePath();
        assertTrue(java.nio.file.Files.isRegularFile(jar),"Build the current runtime JAR before running the restart regression");
        Process process=new ProcessBuilder(java.nio.file.Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-jar",jar.toString(),"--server.port=0")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        process.getOutputStream().close();
        try {
            var ready=CompletableFuture.supplyAsync(()-> {
                try (var reader=process.inputReader()) {
                    String line;
                    while ((line=reader.readLine())!=null) {
                        var matcher=java.util.regex.Pattern.compile("Tomcat started on port (\\d+)").matcher(line);
                        if (matcher.find()) return Integer.parseInt(matcher.group(1));
                    }
                    throw new IllegalStateException("Packaged process did not start");
                } catch (java.io.IOException e) { throw new CompletionException(e); }
            });
            int restartedPort=ready.get(12,TimeUnit.SECONDS);
            var response=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+restartedPort+"/api/auth/me")).header("Cookie",auth.cookie).timeout(Duration.ofSeconds(5)).build(),HttpResponse.BodyHandlers.ofString());
            status(401,response);
        } finally { process.destroyForcibly(); assertTrue(process.waitFor(5,TimeUnit.SECONDS)); }
    }
    @Test @Order(18) void staleInFlightLookupMustRecheckCredentialUnderLock() throws Exception {
        Logged auth=logged("TENANT",codeA,account); String original=auth.cookie;
        CountDownLatch lookedUp=new CountDownLatch(1), resume=new CountDownLatch(1);
        AFTER_CONTEXT_LOOKUP.set(()-> {
            lookedUp.countDown();
            try { if (!resume.await(3,TimeUnit.SECONDS)) throw new IllegalStateException("Lookup coordinator timeout"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
        });
        try (ExecutorService executor=Executors.newSingleThreadExecutor()) {
            var stale=executor.submit(()->request("GET","/api/auth/me",original,null,Map.of()));
            try {
                assertTrue(lookedUp.await(3,TimeUnit.SECONDS));
                var winner=request("GET","/api/auth/me",original,null,Map.of()); status(200,winner);
                String newCookie=cookie(winner,AuthService.AUTH_COOKIE);
                resume.countDown(); status(401,stale.get(3,TimeUnit.SECONDS));
                status(200,request("GET","/api/auth/me",newCookie,null,Map.of()));
            } finally { resume.countDown(); AFTER_CONTEXT_LOOKUP.set(null); }
        }
    }
}
