package com.ias.identity;

import com.ias.identity.auth.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={"server.address=127.0.0.1","identity.auth.login-limit=500","identity.auth.csrf-limit=1000"})
@ContextConfiguration(initializers=AuthFlowTest.MigrateBeforeContext.class)
@Import(AuthFlowTest.Support.class)
class RevocationTest {
    @LocalServerPort int port;
    @Autowired AuthFlowTest.HookEncoder encoder;
    @Autowired AuthFlowTest.MutableClock clock;
    @Autowired AuthFlowTest.HookTransactions transactions;
    private final JsonMapper json=JsonMapper.builder().build();
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private long tenant,otherTenant,admin,target;
    private String code,suffix,password,verifier;
    private static final class Session {
        String cookie,csrf;
        final long user;
        Session(long user,String cookie) { this.user=user; this.cookie=cookie; }
        @Override public String toString() { return "TestSession[redacted]"; }
    }
    @BeforeEach void fixtures() throws Exception {
        clock.time.set(Instant.parse("2026-10-14T12:00:00Z")); encoder.afterMatches.set(null); transactions.afterCommit.set(null);
        suffix=UUID.randomUUID().toString().replace("-","").substring(0,12); code="RV_"+suffix.toUpperCase();
        password=new AuthCrypto().randomToken(); verifier=encoder.encode(password);
        for(String c:List.of(code,code+"_B")) TestDatabase.update("INSERT INTO tenant(code_normalized,name,status,created_at,updated_at) VALUES (?,'撤权测试',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",c);
        tenant=TestDatabase.scalar("SELECT id FROM tenant WHERE code_normalized=?",code);
        otherTenant=TestDatabase.scalar("SELECT id FROM tenant WHERE code_normalized=?",code+"_B");
        admin=user(tenant,"admin","EMPLOYEE",false); target=user(tenant,"target","EMPLOYEE",false);
        grant(admin,"identity.session.revoke");
    }
    @AfterEach void clearHooks() { transactions.afterCommit.set(null); encoder.afterMatches.set(null); AuthFlowTest.AFTER_CONTEXT_LOOKUP.set(null); }
    long user(Long tenant,String prefix,String type,boolean restricted) throws Exception {
        String account=prefix+"."+suffix;
        TestDatabase.update("INSERT INTO identity_user(realm,tenant_id,account_normalized,identity_type,display_name,password_verifier,status,must_change_password,binding_state,created_at,updated_at) VALUES (?,?,?,?,'撤权测试',?,1,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",tenant==null?"PLATFORM":"TENANT",tenant,account,type,verifier,restricted?1:0,"CUSTOMER".equals(type)?2:0);
        return TestDatabase.scalar("SELECT id FROM identity_user WHERE account_normalized=? AND login_namespace_id=?",account,tenant==null?"PLATFORM":"TENANT:"+tenant);
    }
    void grant(long user,String capability) throws Exception {
        TestDatabase.update("INSERT INTO identity_user_capability(user_id,capability_code,enabled,created_at,updated_at) VALUES (?,?,1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",user,capability);
    }
    HttpResponse<String> request(String method,String path,String cookie,String body,Map<String,String> headers) throws Exception {
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(cookie!=null) b.header("Cookie",cookie);
        headers.forEach(b::header);
        if(method.equals("POST")) b.header("Content-Type","application/json");
        return client.send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @SuppressWarnings("unchecked") Map<String,Object> body(HttpResponse<String> r) { return json.readValue(r.body(),Map.class); }
    void status(int expected,HttpResponse<String> r) { assertEquals(expected,r.statusCode(),"Unexpected status; sensitive response deliberately omitted"); }
    void error(int expected,String code,HttpResponse<String> r) { status(expected,r); assertEquals(code,body(r).get("code")); }
    String cookie(HttpResponse<String> r,String name) { return r.headers().allValues("Set-Cookie").stream().filter(v->v.startsWith(name+"=")&&!v.contains("Max-Age=0")).findFirst().orElseThrow().split(";",2)[0]; }
    Session login(long user) throws Exception { return login(user,true); }
    Session login(long user,boolean confirm) throws Exception {
        String account; String tenantCode;
        try(Connection c=TestDatabase.connection();var p=c.prepareStatement("SELECT u.account_normalized,t.code_normalized FROM identity_user u LEFT JOIN tenant t ON t.id=u.tenant_id WHERE u.id=?")) {
            p.setLong(1,user);try(var r=p.executeQuery()){r.next();account=r.getString(1);tenantCode=r.getString(2);}
        }
        var pre=request("GET","/api/auth/csrf",null,null,Map.of());status(200,pre);
        var payload=new LinkedHashMap<String,Object>(); payload.put("realm",tenantCode==null?"PLATFORM":"TENANT"); payload.put("tenantCode",tenantCode);payload.put("account",account);payload.put("password",password);
        var r=request("POST","/api/auth/login",cookie(pre,AuthService.PREAUTH_COOKIE),json.writeValueAsString(payload),Map.of("Origin","http://127.0.0.1:8081","X-CSRF-TOKEN",(String)body(pre).get("token"),"X-Login-Attempt-Id",UUID.randomUUID().toString()));status(200,r);
        Session session=new Session(user,cookie(r,AuthService.AUTH_COOKIE));
        if(confirm){var me=me(session);status(200,me);session.cookie=cookie(me,AuthService.AUTH_COOKIE);}
        if(confirm){var csrf=request("GET","/api/auth/csrf",session.cookie,null,Map.of());status(200,csrf);session.csrf=(String)body(csrf).get("token");}
        return session;
    }
    HttpResponse<String> me(Session s)throws Exception{return request("GET","/api/auth/me",s.cookie,null,Map.of());}
    HttpResponse<String> revoke(Session s,long target,long expected,String reason,String key)throws Exception{
        return request("POST","/api/identity/users/"+target+"/sessions/revoke",s==null?null:s.cookie,json.writeValueAsString(Map.of("reason",reason,"expectedSecurityVersion",Long.toString(expected))),s==null?Map.of("Origin","http://127.0.0.1:8081","Idempotency-Key",key):Map.of("Origin","http://127.0.0.1:8081","Idempotency-Key",key,"X-CSRF-TOKEN",s.csrf==null?"invalid":s.csrf));
    }
    long version(long user)throws Exception{return TestDatabase.scalar("SELECT security_version FROM identity_user WHERE id=?",user);}
    long operations()throws Exception{return TestDatabase.scalar("SELECT COUNT(*) FROM identity_revocation_operation WHERE actor_user_id=?",admin);}
    @Test void allOldSessionsInvalidImmediatelyReplayStableAndNewLoginAllowed()throws Exception{
        Session a=login(admin),t1=login(target),t2=login(target);
        long other=user(otherTenant,"other","EMPLOYEE",false);Session unaffected=login(other);
        assertEquals(List.of("identity.session.revoke"),body(me(a)).get("permissions"));
        var first=revoke(a,target,1,"人工安全撤权","stable-key");status(200,first);
        assertEquals("2",body(first).get("newSecurityVersion"));
        status(401,me(t1));status(401,me(t2));status(200,me(unaffected));status(200,me(a));
        var replay=revoke(a,target,1,"人工安全撤权","stable-key");status(200,replay);assertEquals(body(first),body(replay));
        assertEquals(2,version(target));assertEquals(1,operations());
        assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_security_audit WHERE revocation_operation_id=? AND user_security_version_before=1 AND user_security_version_after=2 AND reason=?",body(first).get("operationId"),"人工安全撤权"));
        status(200,me(login(target)));
    }
    @Test void identityTypeNeverImpliesPermissionAndTenantPlatformScopesStaySeparate()throws Exception{
        Session a=login(admin);long outsider=user(otherTenant,"outside","EMPLOYEE",false),platform=user(null,"platform","PLATFORM",false);
        error(404,"TARGET_NOT_ACCESSIBLE",revoke(a,outsider,1,"拒绝跨租户管理","cross"));
        error(404,"TARGET_NOT_ACCESSIBLE",revoke(a,platform,1,"拒绝平台管理","platform"));
        error(403,"ACCESS_DENIED",revoke(login(target),target,1,"无权限不能撤权","ordinary"));
        long customer=user(tenant,"customer","CUSTOMER",false);grant(customer,"identity.session.revoke");
        error(403,"ACCESS_DENIED",revoke(login(customer),target,1,"客户不能冒用管理","customer"));
        grant(platform,"identity.session.revoke");Session p=login(platform);
        error(403,"ACCESS_DENIED",revoke(p,platform,1,"平台需独立能力","platformwrong"));
        grant(platform,"identity.platform.session.revoke");
        error(404,"TARGET_NOT_ACCESSIBLE",revoke(p,target,1,"平台不跨租户管理","platformcross"));
        status(200,revoke(p,platform,1,"平台自身会话撤权","platformself"));status(401,me(p));
        assertEquals(0,operations());
    }
    @Test void csrfSourceRestrictionAndUnknownInputCannotWrite()throws Exception{
        Session a=login(admin);String path="/api/identity/users/"+target+"/sessions/revoke";
        String payload=json.writeValueAsString(Map.of("reason","人工安全撤权","expectedSecurityVersion",1));
        error(403,"CSRF_INVALID",request("POST",path,a.cookie,payload,Map.of("Origin","http://127.0.0.1:8081","Idempotency-Key","csrf")));
        error(403,"ORIGIN_REJECTED",request("POST",path,a.cookie,payload,Map.of("Origin","https://evil.invalid","Idempotency-Key","source","X-CSRF-TOKEN",a.csrf)));
        error(401,"SESSION_INVALID",revoke(null,target,1,"匿名不能管理会话","anonymous"));
        for(String key:List.of("","has space","x".repeat(129)))error(400,"VALIDATION_ERROR",revoke(a,target,1,"人工安全撤权",key));
        for(String reason:List.of("短","x".repeat(201),"reason\nsecret"))error(400,"VALIDATION_ERROR",revoke(a,target,1,reason,"reason"));
        String extra=json.writeValueAsString(Map.of("reason","人工安全撤权","expectedSecurityVersion",1,"tenantId",otherTenant));
        error(400,"VALIDATION_ERROR",request("POST",path,a.cookie,extra,Map.of("Origin","http://127.0.0.1:8081","Idempotency-Key","extra","X-CSRF-TOKEN",a.csrf)));
        status(403,request("GET",path,a.cookie,null,Map.of()));
        long restricted=user(tenant,"restricted","EMPLOYEE",true);grant(restricted,"identity.session.revoke");
        error(403,"ACCESS_DENIED",revoke(login(restricted),target,1,"强制改密不能管理","restricted"));
        assertEquals(1,version(target));assertEquals(0,operations());
    }
    @Test void idempotencyChecksContentBeforeExpectedVersionAndKeysAreCaseSensitive()throws Exception{
        Session a=login(admin);status(200,revoke(a,target,1,"原始撤权的原因","Key"));
        error(409,"IDEMPOTENCY_CONFLICT",revoke(a,target,1,"修改撤权的原因","Key"));
        error(409,"IDEMPOTENCY_CONFLICT",revoke(a,admin,1,"原始撤权的原因","Key"));
        error(409,"IDEMPOTENCY_CONFLICT",revoke(a,target,2,"原始撤权的原因","Key"));
        error(409,"SECURITY_VERSION_CONFLICT",revoke(a,target,1,"原始撤权的原因","new-key"));
        status(200,revoke(a,target,2,"原始撤权的原因","key"));assertEquals(3,version(target));assertEquals(2,operations());
    }
    @Test void selfRevocationAllowsOnlyCommittedReplyAndFreshIdentityMayReplay()throws Exception{
        Session a=login(admin);var first=revoke(a,admin,1,"撤销本人所有会话","self");status(200,first);
        status(401,me(a));error(401,"SESSION_INVALID",revoke(a,admin,1,"撤销本人所有会话","self"));
        Session fresh=login(admin);var replay=revoke(fresh,admin,1,"撤销本人所有会话","self");status(200,replay);assertEquals(body(first),body(replay));assertEquals(2,version(admin));
    }
    @Test void concurrentSameKeySameContentCommitsExactlyOnce()throws Exception{
        Session a=login(admin);var results=concurrent(()->revoke(a,target,1,"并发重复相同内容","same"),()->revoke(a,target,1,"并发重复相同内容","same"));
        status(200,results.get(0));status(200,results.get(1));assertEquals(body(results.get(0)),body(results.get(1)));assertEquals(2,version(target));assertEquals(1,operations());
    }
    @Test void concurrentDifferentKeysWithSameExpectedHaveOneWinner()throws Exception{
        Session a=login(admin);var results=concurrent(()->revoke(a,target,1,"并发竞争不同键值","one"),()->revoke(a,target,1,"并发竞争不同键值","two"));
        assertEquals(List.of(200,409),results.stream().map(HttpResponse::statusCode).sorted().toList());assertEquals(2,version(target));assertEquals(1,operations());
    }
    @Test void concurrentSameKeyDifferentTargetsConflictsAndActorScopeIsIndependent()throws Exception{
        Session a=login(admin);long another=user(tenant,"another","EMPLOYEE",false);
        var results=concurrent(()->revoke(a,target,1,"同键不同目标竞争","global-key"),()->revoke(a,another,1,"同键不同目标竞争","global-key"));
        assertEquals(List.of(200,409),results.stream().map(HttpResponse::statusCode).sorted().toList());assertEquals(1,operations());
        long winner=version(target)==2?target:another;
        long secondAdmin=user(tenant,"admin2","EMPLOYEE",false);grant(secondAdmin,"identity.session.revoke");
        status(200,revoke(login(secondAdmin),winner,2,"同键不同目标竞争","global-key"));assertEquals(3,version(winner));
    }
    @Test void currentPermissionIsRequiredBeforeHistoricalResultReplay()throws Exception{
        Session a=login(admin);status(200,revoke(a,target,1,"首次撤权成功记录","permission"));
        // Adversarial fixture deliberately omits a version bump to prove online facts are read on every authorization.
        TestDatabase.update("UPDATE identity_user_capability SET enabled=0 WHERE user_id=?",admin);
        error(403,"ACCESS_DENIED",revoke(a,target,1,"首次撤权成功记录","permission"));
        assertEquals(List.of(),body(me(a)).get("permissions"));assertEquals(2,version(target));assertEquals(1,operations());
    }
    @Test void auditFailureRollsBackVersionOperationAndRetryCanSucceed()throws Exception{
        Session a=login(admin),t=login(target);String constraint="it_revoke_audit_"+suffix;
        TestDatabase.update("ALTER TABLE identity_security_audit ADD CONSTRAINT "+constraint+" CHECK (NOT (action='USER_SESSIONS_REVOKE' AND actor_user_id="+admin+"))");
        try {error(503,"OPERATION_RESULT_UNKNOWN",revoke(a,target,1,"故障审计原子回滚","audit"));assertEquals(1,version(target));assertEquals(0,operations());status(200,me(t));}
        finally {TestDatabase.update("ALTER TABLE identity_security_audit DROP CHECK "+constraint);}
        status(200,revoke(a,target,1,"故障审计原子回滚","audit"));assertEquals(2,version(target));
    }
    @Test void lostCommitAcknowledgementRecoversUsingSameKeyWithoutExtraVersion()throws Exception{
        Session a=login(admin);transactions.afterCommit.set(()->{throw new org.springframework.transaction.CannotCreateTransactionException("Injected unknown acknowledgement");});
        error(503,"OPERATION_RESULT_UNKNOWN",revoke(a,target,1,"提交后响应未知恢复","unknown"));
        assertEquals(2,version(target));assertEquals(1,operations());
        status(200,revoke(a,target,1,"提交后响应未知恢复","unknown"));assertEquals(2,version(target));assertEquals(1,operations());
    }
    @Test void revocationCommittedDuringPasswordVerificationPreventsStaleIssuance()throws Exception{
        Session a=login(admin);
        encoder.afterMatches.set(()->{try{status(200,revoke(a,target,1,"密码核验期间撤权","race"));}catch(Exception e){throw new RuntimeException(e);}});
        // login helper expects success, so perform the signing request explicitly to observe401.
        var pre=request("GET","/api/auth/csrf",null,null,Map.of());status(200,pre);
        var r=request("POST","/api/auth/login",cookie(pre,AuthService.PREAUTH_COOKIE),json.writeValueAsString(Map.of("realm","TENANT","tenantCode",code,"account","target."+suffix,"password",password)),Map.of("Origin","http://127.0.0.1:8081","X-CSRF-TOKEN",(String)body(pre).get("token"),"X-Login-Attempt-Id",UUID.randomUUID().toString()));
        error(401,"AUTHENTICATION_FAILED",r);assertEquals(2,version(target));
    }
    @Test void issuedBeforeRevocationCannotConfirmAndUnconfirmedActorCannotManage()throws Exception{
        Session a=login(admin),unconfirmed=login(target,false);
        status(200,revoke(a,target,1,"签发后确认前撤权","beforeconfirm"));status(401,me(unconfirmed));
        Session pending=login(admin,false);
        try(Connection c=TestDatabase.connection();var p=c.prepareStatement("SELECT csrf_token FROM identity_session_context WHERE token_hash=?")){
            p.setBytes(1,new AuthCrypto().hashToken(pending.cookie.split("=",2)[1]));try(var r=p.executeQuery()){r.next();pending.csrf=r.getString(1);}
        }
        error(403,"SESSION_CONFIRMATION_REQUIRED",revoke(pending,target,2,"管理前必须确认会话","pending"));assertEquals(2,version(target));
    }
    @Test void permissionChangeBeforeAuthorityLockRejectsStaleActorAndHistoricalReplay()throws Exception{
        Session a=login(admin);status(200,revoke(a,target,1,"权限变化前成功记录","old-result"));
        CountDownLatch located=new CountDownLatch(1),release=new CountDownLatch(1);
        AuthFlowTest.AFTER_CONTEXT_LOOKUP.set(()->{located.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test coordination timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new RuntimeException(e);}});
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            Future<HttpResponse<String>> request=executor.submit(()->revoke(a,target,1,"权限变化前成功记录","old-result"));
            try {
                assertTrue(located.await(3,TimeUnit.SECONDS));
                try(Connection c=TestDatabase.connection()){
                    c.setAutoCommit(false);
                    try(var s=c.createStatement()){
                        s.executeQuery("SELECT id FROM tenant WHERE id="+tenant+" FOR UPDATE").close();
                        s.executeQuery("SELECT id FROM identity_user WHERE id="+admin+" FOR UPDATE").close();
                        s.executeUpdate("UPDATE identity_user_capability SET enabled=0,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3) WHERE user_id="+admin);
                        s.executeUpdate("UPDATE identity_user SET security_version=security_version+1,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3) WHERE id="+admin);
                        s.executeUpdate("INSERT INTO identity_security_audit(occurred_at,action,actor_type,target_type,target_id,tenant_id,result,reason_code,user_security_version_before,user_security_version_after,trace_id) VALUES(UTC_TIMESTAMP(3),'TEST_PERMISSION_CHANGE','SYSTEM','USER',"+admin+","+tenant+",'SUCCESS','TEST_FIXTURE',1,2,'test-permission-change')");
                    }
                    c.commit();
                }
            }finally{release.countDown();}
            error(401,"SESSION_INVALID",request.get(10,TimeUnit.SECONDS));
        }
        error(403,"ACCESS_DENIED",revoke(login(admin),target,1,"权限变化前成功记录","old-result"));assertEquals(2,version(target));assertEquals(1,operations());
    }
    @Test void mutualPlatformRevocationUsesOrderedLocksWithoutDeadlock()throws Exception{
        long p=user(null,"p","PLATFORM",false),q=user(null,"q","PLATFORM",false);grant(p,"identity.platform.session.revoke");grant(q,"identity.platform.session.revoke");
        Session ps=login(p),qs=login(q);
        var r=concurrent(()->revoke(ps,q,1,"平台互相撤销会话","pq"),()->revoke(qs,p,1,"平台互相撤销会话","qp"));
        assertEquals(List.of(200,401),r.stream().map(HttpResponse::statusCode).sorted().toList());assertEquals(3,version(p)+version(q));
    }
    @Test void maxVersionCannotWrapAndUnicodeReasonIsStoredWithoutTrimming()throws Exception{
        Session a=login(admin);TestDatabase.update("UPDATE identity_user SET security_version=? WHERE id=?",Long.MAX_VALUE,target);
        error(409,"SECURITY_VERSION_EXHAUSTED",revoke(a,target,Long.MAX_VALUE,"版本溢出必须拒绝","max"));assertEquals(Long.MAX_VALUE,version(target));
        String reason=" "+"😀".repeat(198)+" ";var r=revoke(a,admin,1,reason,"unicode");status(200,r);
        assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_revocation_operation WHERE operation_id=? AND BINARY reason=BINARY ?",body(r).get("operationId"),reason));
    }
    @Test void capabilityConstraintsAndRuntimeLeastPrivilege()throws Exception{
        assertThrows(SQLException.class,()->grant(admin,"identity.unknown"));
        assertThrows(SQLException.class,()->grant(admin,"identity.session.revoke"));
        try(Connection c=DriverManager.getConnection(TestDatabase.URL,System.getenv("SPRING_DATASOURCE_USERNAME"),System.getenv("SPRING_DATASOURCE_PASSWORD"));var s=c.createStatement()){
            for(String sql:List.of(
                "UPDATE identity_user SET status=status WHERE 1=0",
                "UPDATE identity_user_capability SET enabled=enabled WHERE 1=0"
            ))assertEquals(0,s.executeUpdate(sql),sql);
            for(String sql:List.of(
                "UPDATE identity_user SET display_name=display_name WHERE 1=0",
                "UPDATE identity_user SET realm=realm WHERE 1=0",
                "UPDATE identity_user SET tenant_id=tenant_id WHERE 1=0",
                "UPDATE identity_user_capability SET capability_code=capability_code WHERE 1=0",
                "INSERT INTO identity_user_capability(user_id,capability_code,created_at,updated_at) VALUES (-1,'identity.session.revoke',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                "UPDATE identity_revocation_operation SET reason=reason WHERE 1=0",
                "DELETE FROM identity_revocation_operation WHERE 1=0"
            ))assertThrows(SQLException.class,()->s.executeUpdate(sql),sql);
        }
    }
    @Test void oldCredentialRejectedImmediatelyAndAtOrAfterRealSixtySeconds()throws Exception{
        Session a=login(admin),old=login(target);
        var committed=new java.util.concurrent.atomic.AtomicLong();
        transactions.afterCommit.set(()->committed.set(System.nanoTime()));
        status(200,revoke(a,target,1,"真实六十秒撤权核验","real-sixty"));assertTrue(committed.get()>0);
        var immediate=me(old);status(401,immediate);long immediateMs=TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-committed.get());
        long boundary=committed.get()+TimeUnit.SECONDS.toNanos(60),remaining=boundary-System.nanoTime();
        if(remaining>0)Thread.sleep(Duration.ofNanos(remaining));
        long observed=System.nanoTime();assertTrue(observed>=boundary);
        var later=me(old);status(401,later);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target","revocation-timing.json"),json.writeValueAsString(Map.of(
            "boundary","first sample at or after 60s from observed committed transaction callback; not exact equality",
            "immediateMs",immediateMs,"immediateStatus",immediate.statusCode(),"laterRequestStartedMs",TimeUnit.NANOSECONDS.toMillis(observed-committed.get()),"laterStatus",later.statusCode(),"scope","identity HTTP only; cross-service/Range not tested")));
    }
    List<HttpResponse<String>> concurrent(Callable<HttpResponse<String>> first,Callable<HttpResponse<String>> second)throws Exception{
        CountDownLatch start=new CountDownLatch(1);try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            Future<HttpResponse<String>> a=executor.submit(()->{start.await();return first.call();});Future<HttpResponse<String>> b=executor.submit(()->{start.await();return second.call();});start.countDown();
            return List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
        }
    }
}
