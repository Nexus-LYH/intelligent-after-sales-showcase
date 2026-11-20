package com.ias.identity;

import com.ias.identity.auth.*;
import com.ias.identity.bootstrap.AccountInitializer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
class AccountTest {
    @LocalServerPort int port;
    @Autowired AuthFlowTest.MutableClock clock;
    @Autowired AuthFlowTest.HookEncoder encoder;
    @Autowired AuthFlowTest.HookTransactions transactions;
    @MockitoBean EmployeeScopeReferences scopeReferences;
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final JsonMapper json=JsonMapper.builder().build();
    private Map<String,String> secrets;
    private AccountInitializer.Plan plan;
    private String suffix,codeA;
    private long tenantA;
    private User admin,admin2,member,other,platform;
    private static final class User {
        final long id;final String account,realm,tenant;
        String password;
        User(long id,String account,String realm,String tenant,String password){this.id=id;this.account=account;this.realm=realm;this.tenant=tenant;this.password=password;}
        @Override public String toString(){return "TestUser[redacted]";}
    }
    private static final class Session {
        final User user;String cookie,csrf;
        Session(User user,String cookie){this.user=user;this.cookie=cookie;}
        @Override public String toString(){return "TestSession[redacted]";}
    }
    @BeforeEach void initialize()throws Exception{
        clock.time.set(Instant.parse("2026-10-15T00:00:00Z"));encoder.afterMatches.set(null);transactions.afterCommit.set(null);AuthFlowTest.AFTER_CONTEXT_LOOKUP.set(null);
        suffix=UUID.randomUUID().toString().replace("-","").substring(0,10);codeA="AC_"+suffix.toUpperCase();
        List<AccountInitializer.UserSpec> users=List.of(spec("p",null,true),spec("a",codeA,true),spec("a2",codeA,true),spec("e",codeA,false),spec("b",codeA+"B",true));
        plan=new AccountInitializer.Plan(List.of(new AccountInitializer.TenantSpec(codeA,"账号服务测试A"),new AccountInitializer.TenantSpec(codeA+"B","账号服务测试B")),users);
        secrets=new HashMap<>();for(var u:users)secrets.put(u.passwordRef(),new AuthCrypto().randomToken());
        List<AccountInitializer.Result> results;try(var c=TestDatabase.connection()){results=AccountInitializer.initialize(c,plan,secrets,encoder);}
        Map<String,Long> ids=new HashMap<>();for(var r:results)if(r.type().equals("USER"))ids.put(r.account(),Long.parseLong(r.id()));
        platform=user(users.get(0),ids);admin=user(users.get(1),ids);admin2=user(users.get(2),ids);member=user(users.get(3),ids);other=user(users.get(4),ids);
        tenantA=TestDatabase.scalar("SELECT id FROM tenant WHERE code_normalized=?",codeA);
    }
    private AccountInitializer.UserSpec spec(String ref,String tenant,boolean manager){return new AccountInitializer.UserSpec(tenant==null?"PLATFORM":"TENANT",tenant,ref+"."+suffix,"账号测试"+ref,manager,ref);}
    private User user(AccountInitializer.UserSpec s,Map<String,Long> ids){return new User(ids.get(s.account()),s.account(),s.realm(),s.tenantCode(),secrets.get(s.passwordRef()));}
    @AfterEach void clearHooks(){encoder.afterMatches.set(null);transactions.afterCommit.set(null);AuthFlowTest.AFTER_CONTEXT_LOOKUP.set(null);}
    HttpResponse<String> request(String method,String path,String cookie,String payload,Map<String,String> headers)throws Exception{
        var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(15));
        if(cookie!=null)b.header("Cookie",cookie);headers.forEach(b::header);if(method.equals("POST"))b.header("Content-Type","application/json");
        return http.send(b.method(method,payload==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(payload)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @SuppressWarnings("unchecked") Map<String,Object> body(HttpResponse<String> r){return json.readValue(r.body(),Map.class);}
    void status(int expected,HttpResponse<String> r){assertEquals(expected,r.statusCode(),"Unexpected HTTP status; sensitive response omitted");}
    void error(int expected,String code,HttpResponse<String> r){status(expected,r);assertEquals(code,body(r).get("code"));}
    String cookie(HttpResponse<String> r,String name){return r.headers().allValues("Set-Cookie").stream().filter(v->v.startsWith(name+"=")&&!v.contains("Max-Age=0")).findFirst().orElseThrow().split(";",2)[0];}
    HttpResponse<String> loginResponse(User u,String password)throws Exception{
        var pre=request("GET","/api/auth/csrf",null,null,Map.of());status(200,pre);
        var payload=new LinkedHashMap<String,Object>();payload.put("realm",u.realm);payload.put("tenantCode",u.tenant);payload.put("account",u.account);payload.put("password",password);
        return request("POST","/api/auth/login",cookie(pre,AuthService.PREAUTH_COOKIE),json.writeValueAsString(payload),Map.of("Origin","http://127.0.0.1:5173","X-CSRF-TOKEN",(String)body(pre).get("token"),"X-Login-Attempt-Id",UUID.randomUUID().toString()));
    }
    Session login(User u)throws Exception{return login(u,true);}
    Session login(User u,boolean confirm)throws Exception{
        var r=loginResponse(u,u.password);status(200,r);var s=new Session(u,cookie(r,AuthService.AUTH_COOKIE));
        if(confirm){var me=me(s);status(200,me);s.cookie=cookie(me,AuthService.AUTH_COOKIE);var csrf=request("GET","/api/auth/csrf",s.cookie,null,Map.of());status(200,csrf);s.csrf=(String)body(csrf).get("token");}
        return s;
    }
    Session normal(User u)throws Exception{Session s=login(u);String replacement=new AuthCrypto().randomToken();status(200,password(s,u.password,replacement,replacement));u.password=replacement;return login(u);}
    HttpResponse<String> me(Session s)throws Exception{return request("GET","/api/auth/me",s.cookie,null,Map.of());}
    HttpResponse<String> password(Session s,String old,String next,String confirm)throws Exception{
        return request("POST","/api/auth/password",s==null?null:s.cookie,json.writeValueAsString(Map.of("currentPassword",old,"newPassword",next,"confirmPassword",confirm)),s==null?Map.of("Origin","http://127.0.0.1:5173"):Map.of("Origin","http://127.0.0.1:5173","X-CSRF-TOKEN",s.csrf==null?"invalid":s.csrf));
    }
    HttpResponse<String> get(Session s,String suffix)throws Exception{return request("GET","/api/identity/users"+suffix,s==null?null:s.cookie,null,Map.of());}
    long version(User u)throws Exception{return TestDatabase.scalar("SELECT security_version FROM identity_user WHERE id=?",u.id);}
    @Test void initialRestrictedChangeLogsOutEveryOldSessionAndAllowsExplicitNormalLogin()throws Exception{
        Session first=login(admin),second=login(admin),unaffected=login(member);
        assertEquals("PASSWORD_CHANGE_REQUIRED",body(me(first)).get("accessState"));status(403,get(first,""));
        String old=admin.password,next=new AuthCrypto().randomToken();var r=password(first,old,next,next);status(200,r);assertEquals(Map.of("nextAction","LOGIN"),body(r));
        assertEquals(2,r.headers().allValues("Set-Cookie").stream().filter(v->v.contains("Max-Age=0")).count());
        status(401,me(first));status(401,me(second));status(200,me(unaffected));assertEquals(2,version(admin));
        status(401,loginResponse(admin,old));admin.password=next;Session fresh=login(admin);assertEquals("NORMAL",body(me(fresh)).get("accessState"));status(200,get(fresh,""));
        String voluntary=new AuthCrypto().randomToken();status(200,password(fresh,next,voluntary,voluntary));assertEquals(3,version(admin));status(401,me(fresh));
    }
    @Test void passwordPolicyOldValueAndConfirmationNeverMutateOnError()throws Exception{
        Session s=login(member);String next=new AuthCrypto().randomToken();
        error(403,"CURRENT_PASSWORD_INVALID",password(s," "+member.password,next,next));
        error(400,"PASSWORD_UNCHANGED",password(s,member.password,member.password,member.password));
        for(String invalid:List.of("x".repeat(11),"😀".repeat(129)))error(400,"VALIDATION_ERROR",password(s,member.password,invalid,invalid));
        error(400,"VALIDATION_ERROR",password(s,member.password,next,next+"x"));assertEquals(1,version(member));
        String twelve="新密码".repeat(4);status(200,password(s,member.password,twelve,twelve));member.password=twelve;Session again=login(member);
        String maximum=" "+"😀".repeat(126)+" ";status(200,password(again,member.password,maximum,maximum));
        status(401,loginResponse(member,maximum.strip()));member.password=maximum;status(200,me(login(member)));
    }
    @Test void passwordSourceCsrfInputAndExpiryAreFailClosed()throws Exception{
        Session s=login(member);String n=new AuthCrypto().randomToken(),payload=json.writeValueAsString(Map.of("currentPassword",member.password,"newPassword",n,"confirmPassword",n));
        error(401,"SESSION_INVALID",password(null,member.password,n,n));
        error(403,"CSRF_INVALID",request("POST","/api/auth/password",s.cookie,payload,Map.of("Origin","http://127.0.0.1:5173")));
        error(403,"ORIGIN_REJECTED",request("POST","/api/auth/password",s.cookie,payload,Map.of("Origin","https://evil.invalid","X-CSRF-TOKEN",s.csrf)));
        error(400,"VALIDATION_ERROR",request("POST","/api/auth/password",s.cookie,json.writeValueAsString(Map.of("currentPassword",member.password,"newPassword",n,"confirmPassword",n,"userId",admin.id)),Map.of("Origin","http://127.0.0.1:5173","X-CSRF-TOKEN",s.csrf)));
        Session pending=login(admin,false);
        try(var c=TestDatabase.connection();var p=c.prepareStatement("SELECT csrf_token FROM identity_session_context WHERE token_hash=?")){p.setBytes(1,new AuthCrypto().hashToken(pending.cookie.split("=",2)[1]));try(var r=p.executeQuery()){r.next();pending.csrf=r.getString(1);}}
        error(403,"SESSION_CONFIRMATION_REQUIRED",password(pending,admin.password,n,n));
        clock.advance(Duration.ofMinutes(15));error(401,"SESSION_INVALID",password(s,member.password,n,n));assertEquals(1,version(member));
    }
    @Test void concurrentPasswordChangesHaveOneWinnerAndOneAudit()throws Exception{
        Session a=login(member),b=login(member);String first=new AuthCrypto().randomToken(),second=new AuthCrypto().randomToken();
        CountDownLatch start=new CountDownLatch(1);List<HttpResponse<String>> results;
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var x=executor.submit(()->{start.await();return password(a,member.password,first,first);});var y=executor.submit(()->{start.await();return password(b,member.password,second,second);});start.countDown();results=List.of(x.get(15,TimeUnit.SECONDS),y.get(15,TimeUnit.SECONDS));
        }
        assertEquals(1,results.stream().filter(r->r.statusCode()==200).count());assertTrue(results.stream().filter(r->r.statusCode()!=200).allMatch(r->r.statusCode()==401||r.statusCode()==409));
        assertEquals(2,version(member));assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_security_audit WHERE action='PASSWORD_CHANGE' AND actor_user_id=?",member.id));
        member.password=results.getFirst().statusCode()==200?first:second;status(200,me(login(member)));status(401,me(a));status(401,me(b));
    }
    @Test void revocationDuringOldPasswordVerificationPreventsPasswordCommit()throws Exception{
        Session a=normal(admin),target=login(member);String replacement=new AuthCrypto().randomToken();
        encoder.afterMatches.set(()->{try{status(200,request("POST","/api/identity/users/"+member.id+"/sessions/revoke",a.cookie,json.writeValueAsString(Map.of("reason","改密核验期间撤权","expectedSecurityVersion","1")),Map.of("Origin","http://127.0.0.1:5173","X-CSRF-TOKEN",a.csrf,"Idempotency-Key","password-race")));}catch(Exception e){throw new RuntimeException(e);}});
        error(401,"SESSION_INVALID",password(target,member.password,replacement,replacement));assertEquals(2,version(member));
        assertEquals(1,TestDatabase.scalar("SELECT must_change_password FROM identity_user WHERE id=?",member.id));status(200,me(login(member)));
    }
    @Test void passwordAuditFailureRollsBackAllFactsAndRetrySucceeds()throws Exception{
        Session s=login(member);String next=new AuthCrypto().randomToken(),constraint="it_password_audit_"+suffix;
        TestDatabase.update("ALTER TABLE identity_security_audit ADD CONSTRAINT "+constraint+" CHECK (NOT (action='PASSWORD_CHANGE' AND actor_user_id="+member.id+"))");
        try{error(503,"OPERATION_RESULT_UNKNOWN",password(s,member.password,next,next));assertEquals(1,version(member));assertEquals(1,TestDatabase.scalar("SELECT must_change_password FROM identity_user WHERE id=?",member.id));status(200,me(s));}
        finally{TestDatabase.update("ALTER TABLE identity_security_audit DROP CHECK "+constraint);}
        status(200,password(s,member.password,next,next));assertEquals(2,version(member));
    }
    @Test void passwordUnknownCommitKeepsCookieAndRequiresExplicitLoginCheck()throws Exception{
        Session s=login(member);String old=member.password,next=new AuthCrypto().randomToken();
        encoder.afterMatches.set(()->transactions.afterCommit.set(()->{throw new org.springframework.transaction.CannotCreateTransactionException("Injected lost password commit acknowledgement");}));
        var r=password(s,old,next,next);error(503,"OPERATION_RESULT_UNKNOWN",r);assertTrue(r.headers().allValues("Set-Cookie").isEmpty());assertEquals(2,version(member));
        status(401,me(s));status(401,loginResponse(member,old));member.password=next;assertEquals("NORMAL",body(me(login(member))).get("accessState"));
    }
    @Test void queryProvidesActualVersionAndOnlyWhitelistedSameNamespaceFields()throws Exception{
        Session a=normal(admin);var list=get(a,"");status(200,list);assertEquals(3,((Number)body(list).get("total")).intValue());
        var detail=get(a,"/"+member.id);status(200,detail);Map<String,Object> d=body(detail);
        assertEquals(Set.of("id","account","displayName","identityType","status","mustChangePassword","bindingState","securityVersion","updatedAt","department","tenant"),d.keySet());
        assertEquals("1",d.get("securityVersion"));assertEquals(true,d.get("mustChangePassword"));assertEquals("NOT_APPLICABLE",d.get("bindingState"));
        error(404,"TARGET_NOT_ACCESSIBLE",get(a,"/"+other.id));error(404,"TARGET_NOT_ACCESSIBLE",get(a,"/"+platform.id));
        var revoke=request("POST","/api/identity/users/"+d.get("id")+"/sessions/revoke",a.cookie,json.writeValueAsString(Map.of("reason","从真实详情取得版本","expectedSecurityVersion",d.get("securityVersion"))),Map.of("Origin","http://127.0.0.1:5173","X-CSRF-TOKEN",a.csrf,"Idempotency-Key","from-detail"));status(200,revoke);assertEquals("2",body(get(a,"/"+member.id)).get("securityVersion"));
    }
    @Test void queryRejectsRestrictedOrdinaryAndRevokedPermissionAndPlatformStaysSeparate()throws Exception{
        error(401,"SESSION_INVALID",get(null,""));status(403,get(login(admin),""));Session employee=normal(member);status(403,get(employee,""));status(403,get(employee,"/"+member.id));
        Session p=normal(platform);status(200,get(p,"?keyword="+platform.account));status(403,get(employee,"?keyword="+platform.account));error(404,"TARGET_NOT_ACCESSIBLE",get(p,"/"+admin.id));
        Session a=normal(admin);TestDatabase.update("UPDATE identity_user_capability SET enabled=0 WHERE user_id=?",admin.id);status(403,get(a,""));status(403,get(a,"/"+member.id));
    }
    @Test void employeeAccessIsLimitedToCurrentTenantAdministratorAndCurrentFacts()throws Exception{
        TestDatabase.update("INSERT INTO identity_employee_configuration(user_id,tenant_id,created_at,updated_at) VALUES(?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",member.id,tenantA);
        TestDatabase.update("INSERT INTO identity_employee_company_scope(tenant_id,user_id,company_id,enabled) VALUES(?,?,?,1)",tenantA,member.id,123L);
        TestDatabase.update("INSERT INTO identity_warehouse_scope(tenant_id,user_id,warehouse_id,enabled) VALUES(?,?,?,1)",tenantA,member.id,456L);
        TestDatabase.update("INSERT INTO identity_user_capability(user_id,capability_code,enabled,created_at,updated_at) VALUES(?,'ticket.read',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",member.id);
        Session manager=normal(admin),ordinary=normal(member),platformSession=normal(platform);var response=get(manager,"/"+member.id+"/access");status(200,response);var value=body(response);
        assertEquals(Long.toString(member.id),value.get("userId"));assertEquals(List.of("123"),value.get("companyIds"));assertEquals(List.of("456"),value.get("warehouseIds"));assertEquals(List.of("ticket.read"),value.get("capabilities"));
        error(404,"TARGET_NOT_ACCESSIBLE",get(manager,"/"+other.id+"/access"));status(403,get(ordinary,"/"+member.id+"/access"));error(404,"TARGET_NOT_ACCESSIBLE",get(platformSession,"/"+member.id+"/access"));
        TestDatabase.update("UPDATE identity_employee_company_scope SET enabled=0 WHERE tenant_id=? AND user_id=?",tenantA,member.id);assertEquals(List.of(),body(get(manager,"/"+member.id+"/access")).get("companyIds"));
    }
    @Test void employeeStatusRequiresDedicatedPermissionAndRevokesOldSessions()throws Exception{
        Session manager=normal(admin),employee=normal(member),outsider=normal(other);String op=UUID.randomUUID().toString(),version=Long.toString(version(member));
        String payload=json.writeValueAsString(Map.of("operationId",op,"expectedSecurityVersion",version,"status",0,"reason","核验员工停用原因"));
        var headers=Map.of("Origin","http://127.0.0.1:5173","X-CSRF-TOKEN",manager.csrf,"Idempotency-Key",op);String path="/api/identity/users/"+member.id+"/status";
        var changed=request("POST",path,manager.cookie,payload,headers);status(200,changed);assertEquals(0,((Number)body(changed).get("status")).intValue());status(401,me(employee));assertEquals(Long.parseLong(version)+1,version(member));
        status(200,request("POST",path,manager.cookie,payload,headers));assertEquals(Long.parseLong(version)+1,version(member));
        var receipt=get(manager,"/"+member.id+"/status-operations/"+op);status(200,receipt);assertEquals(op,body(receipt).get("operationId"));error(404,"OPERATION_NOT_OBSERVED",get(manager,"/"+member.id+"/status-operations/"+UUID.randomUUID()));
        var different=json.writeValueAsString(Map.of("operationId",op,"expectedSecurityVersion",version,"status",1,"reason","核验员工停用原因"));error(409,"IDEMPOTENCY_CONFLICT",request("POST",path,manager.cookie,different,headers));
        String otherOp=UUID.randomUUID().toString(),otherPayload=json.writeValueAsString(Map.of("operationId",otherOp,"expectedSecurityVersion",Long.toString(version(other)),"status",0,"reason","跨租户测试原因"));var otherHeaders=Map.of("Origin","http://127.0.0.1:5173","X-CSRF-TOKEN",manager.csrf,"Idempotency-Key",otherOp);error(404,"TARGET_NOT_ACCESSIBLE",request("POST","/api/identity/users/"+other.id+"/status",manager.cookie,otherPayload,otherHeaders));status(200,me(outsider));
        String protectOp=UUID.randomUUID().toString(),protectedPayload=json.writeValueAsString(Map.of("operationId",protectOp,"expectedSecurityVersion",Long.toString(version(admin2)),"status",0,"reason","管理员保护测试"));var protectHeaders=Map.of("Origin","http://127.0.0.1:5173","X-CSRF-TOKEN",manager.csrf,"Idempotency-Key",protectOp);error(403,"ADMIN_TARGET_PROTECTED",request("POST","/api/identity/users/"+admin2.id+"/status",manager.cookie,protectedPayload,protectHeaders));
        TestDatabase.update("UPDATE identity_user_capability SET enabled=0 WHERE user_id=? AND capability_code='identity.employee.manage'",admin2.id);Session revokeOnly=normal(admin2);String deniedOp=UUID.randomUUID().toString(),denied=json.writeValueAsString(Map.of("operationId",deniedOp,"expectedSecurityVersion",Long.toString(version(member)),"status",1,"reason","无管理权限测试"));status(403,request("POST",path,revokeOnly.cookie,denied,Map.of("Origin","http://127.0.0.1:5173","X-CSRF-TOKEN",revokeOnly.csrf,"Idempotency-Key",deniedOp)));
        String enableOp=UUID.randomUUID().toString(),enable=json.writeValueAsString(Map.of("operationId",enableOp,"expectedSecurityVersion",Long.toString(version(member)),"status",1,"reason","恢复员工工作资格"));status(200,request("POST",path,manager.cookie,enable,Map.of("Origin","http://127.0.0.1:5173","X-CSRF-TOKEN",manager.csrf,"Idempotency-Key",enableOp)));status(401,me(employee));status(200,me(login(member)));
    }
    @Test void employeeCreationDoesNotPersistPasswordInOperationAndIsTenantScoped()throws Exception{
        Session manager=normal(admin),ordinary=normal(member),otherManager=normal(other);String op=UUID.randomUUID().toString(),account="new."+suffix,password=new AuthCrypto().randomToken();
        String payload=json.writeValueAsString(Map.of("operationId",op,"account",account,"displayName","新员工测试","initialPassword",password));var headers=Map.of("Origin","http://127.0.0.1:5173","X-CSRF-TOKEN",manager.csrf,"Idempotency-Key",op);
        var created=request("POST","/api/identity/employees",manager.cookie,payload,headers);status(200,created);var receipt=body(created);assertEquals(account,receipt.get("account"));assertEquals(true,receipt.get("mustChangePassword"));assertFalse(created.body().contains(password));
        var lookup=request("GET","/api/identity/employee-create-operations/"+op,manager.cookie,null,Map.of());status(200,lookup);assertEquals(receipt,body(lookup));assertFalse(lookup.body().contains(password));
        error(409,"OPERATION_ALREADY_OBSERVED",request("POST","/api/identity/employees",manager.cookie,payload,headers));error(404,"OPERATION_NOT_OBSERVED",request("GET","/api/identity/employee-create-operations/"+op,otherManager.cookie,null,Map.of()));
        String next=UUID.randomUUID().toString(),denied=json.writeValueAsString(Map.of("operationId",next,"account","no."+suffix,"displayName","普通员工尝试","initialPassword",password));status(403,request("POST","/api/identity/employees",ordinary.cookie,denied,Map.of("Origin","http://127.0.0.1:5173","X-CSRF-TOKEN",ordinary.csrf,"Idempotency-Key",next)));
        long id=Long.parseLong(receipt.get("userId").toString());assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_employee_configuration WHERE user_id=? AND tenant_id=?",id,tenantA));assertEquals(0,TestDatabase.scalar("SELECT COUNT(*) FROM identity_user_capability WHERE user_id=?",id));assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_security_audit WHERE target_id=? AND action='EMPLOYEE_CREATED'",id));
        User createdUser=new User(id,account,"TENANT",codeA,password);assertEquals("PASSWORD_CHANGE_REQUIRED",body(me(login(createdUser))).get("accessState"));
    }
    @Test void employeeScopeIsTenantBoundAuditedAndRevokesOldSession()throws Exception{
        TestDatabase.update("INSERT INTO identity_employee_configuration(user_id,tenant_id,created_at,updated_at) VALUES(?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",member.id,tenantA);
        TestDatabase.update("INSERT INTO identity_department(tenant_id,code,name,enabled,created_at,updated_at) VALUES(?,?,?,1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",tenantA,"D_"+suffix,"测试部门");long department=TestDatabase.scalar("SELECT id FROM identity_department WHERE tenant_id=? AND code=?",tenantA,"D_"+suffix);
        Session manager=normal(admin),employee=normal(member);String op=UUID.randomUUID().toString(),before=Long.toString(version(member));var command=new LinkedHashMap<String,Object>();command.put("operationId",op);command.put("expectedSecurityVersion",before);command.put("departmentId",Long.toString(department));command.put("capabilities",List.of("ticket.read","ticket.record"));command.put("companyIds",List.of("123"));command.put("assignmentDepartmentIds",List.of(Long.toString(department)));command.put("approvalDepartmentIds",List.of());command.put("reopenDepartmentIds",List.of());command.put("warehouseIds",List.of("456"));command.put("reason","员工授权变更测试");String payload=json.writeValueAsString(command),path="/api/identity/users/"+member.id+"/scope";var headers=Map.of("Origin","http://127.0.0.1:5173","X-CSRF-TOKEN",manager.csrf,"Idempotency-Key",op);
        status(200,request("POST",path,manager.cookie,payload,headers));status(401,me(employee));assertEquals(Long.parseLong(before)+1,version(member));var facts=body(get(manager,"/"+member.id+"/access"));assertEquals(List.of("123"),facts.get("companyIds"));assertEquals(List.of("456"),facts.get("warehouseIds"));assertEquals(List.of("ticket.read","ticket.record"),facts.get("capabilities"));
        status(200,request("POST",path,manager.cookie,payload,headers));assertEquals(Long.parseLong(before)+1,version(member));status(200,get(manager,"/"+member.id+"/scope-operations/"+op));assertEquals(1,TestDatabase.scalar("SELECT COUNT(*) FROM identity_security_audit WHERE target_id=? AND action='EMPLOYEE_SCOPE_CHANGED'",member.id));
        org.mockito.Mockito.verify(scopeReferences).verify(org.mockito.ArgumentMatchers.eq(tenantA),org.mockito.ArgumentMatchers.eq(List.of("123")),org.mockito.ArgumentMatchers.eq(List.of("456")),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong());
        command.put("reason","不同参数冲突测试");error(409,"IDEMPOTENCY_CONFLICT",request("POST",path,manager.cookie,json.writeValueAsString(command),headers));command.put("reason","员工授权变更测试");
        String badReference=UUID.randomUUID().toString();command.put("operationId",badReference);command.put("expectedSecurityVersion",Long.toString(version(member)));org.mockito.Mockito.doThrow(new com.ias.trust.Problem(404,"TARGET_NOT_ACCESSIBLE")).when(scopeReferences).verify(org.mockito.ArgumentMatchers.eq(tenantA),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyLong());error(404,"TARGET_NOT_ACCESSIBLE",request("POST",path,manager.cookie,json.writeValueAsString(command),Map.of("Origin","http://127.0.0.1:5173","X-CSRF-TOKEN",manager.csrf,"Idempotency-Key",badReference)));assertEquals(Long.parseLong(before)+1,version(member));
        org.mockito.Mockito.reset(scopeReferences);String rollback=UUID.randomUUID().toString();command.put("operationId",rollback);command.put("reason","审计写入失败测试");command.put("capabilities",List.of());TestDatabase.update("ALTER TABLE identity_security_audit ADD CONSTRAINT qa_scope_audit CHECK(reason IS NULL OR reason<>'审计写入失败测试')");try{status(503,request("POST",path,manager.cookie,json.writeValueAsString(command),Map.of("Origin","http://127.0.0.1:5173","X-CSRF-TOKEN",manager.csrf,"Idempotency-Key",rollback)));assertEquals(Long.parseLong(before)+1,version(member));assertEquals(List.of("ticket.read","ticket.record"),body(get(manager,"/"+member.id+"/access")).get("capabilities"));assertEquals(0,TestDatabase.scalar("SELECT COUNT(*) FROM identity_employee_scope_operation WHERE operation_id=?",rollback));}finally{TestDatabase.update("ALTER TABLE identity_security_audit DROP CHECK qa_scope_audit");}
        String forbidden=UUID.randomUUID().toString();command.put("operationId",forbidden);command.put("capabilities",List.of("identity.employee.manage"));error(400,"VALIDATION_ERROR",request("POST",path,manager.cookie,json.writeValueAsString(command),Map.of("Origin","http://127.0.0.1:5173","X-CSRF-TOKEN",manager.csrf,"Idempotency-Key",forbidden)));
    }
    @Test void queryValidatesFiltersEscapesWildcardsAndDoesNotTrustTenantOrHeaders()throws Exception{
        Session a=normal(admin),ordinary=normal(member);
        status(403,request("GET","/api/identity/users",ordinary.cookie,null,Map.of("X-User-Id",Long.toString(admin.id),"X-Tenant-Id",Long.toString(tenantA))));
        for(String q:List.of("?page=0","?page=1000001","?size=10","?status=2","?page=1&page=2","?tenantId="+tenantA,"?keyword="+"x".repeat(101)))error(400,"VALIDATION_ERROR",get(a,q));
        TestDatabase.update("UPDATE identity_user SET display_name='literal%_!姓名',status=0 WHERE id=?",member.id);
        var wildcard=get(a,"?keyword="+URLEncoder.encode("%_!",java.nio.charset.StandardCharsets.UTF_8));status(200,wildcard);assertEquals(1,((Number)body(wildcard).get("total")).intValue());
        var injected=get(a,"?keyword="+URLEncoder.encode("' OR 1=1 --",java.nio.charset.StandardCharsets.UTF_8));status(200,injected);assertEquals(0,((Number)body(injected).get("total")).intValue());
        assertEquals(1,((Number)body(get(a,"?status=0")).get("total")).intValue());status(401,loginResponse(member,member.password));
        status(403,request("POST","/api/identity/bootstrap",null,"{}",Map.of("Origin","http://127.0.0.1:5173")));
    }
    @Test void queryStablePaginationTotalAndEmptyPagesUseSameNamespace()throws Exception{
        var users=new ArrayList<>(plan.users());var passwords=new HashMap<>(secrets);
        for(int i=0;i<22;i++){String ref="extra"+i;users.add(spec(ref,codeA,false));passwords.put(ref,new AuthCrypto().randomToken());}
        try(var c=TestDatabase.connection()){AccountInitializer.initialize(c,new AccountInitializer.Plan(plan.tenants(),users),passwords,encoder);}
        TestDatabase.update("UPDATE identity_user SET updated_at='2026-10-15 00:00:00.000' WHERE tenant_id=?",tenantA);
        Session a=normal(admin);var first=get(a,"?page=1&size=20");var second=get(a,"?page=2&size=20");var empty=get(a,"?page=3&size=20");
        assertEquals(25,((Number)body(first).get("total")).intValue());assertEquals(20,items(first).size());assertEquals(5,items(second).size());assertEquals(0,items(empty).size());assertEquals(25,((Number)body(empty).get("total")).intValue());
        Set<String> ids=new HashSet<>();for(var r:items(first))ids.add((String)r.get("id"));for(var r:items(second))assertTrue(ids.add((String)r.get("id")));assertEquals(25,ids.size());assertEquals(body(first),body(get(a,"?page=1&size=20")));
    }
    @SuppressWarnings("unchecked") private List<Map<String,Object>> items(HttpResponse<String> r){status(200,r);return (List<Map<String,Object>>)body(r).get("items");}
    @Test void toolReplayAfterPasswordChangeDoesNotResetPasswordFlagVersionOrGrant()throws Exception{
        Session a=normal(admin);long v=version(admin),audits=TestDatabase.scalar("SELECT COUNT(*) FROM identity_security_audit WHERE target_type='USER' AND target_id=? AND action IN ('BOOTSTRAP_USER','BOOTSTRAP_CAPABILITY','PASSWORD_CHANGE')",admin.id);
        try(var c=TestDatabase.connection()){assertTrue(AccountInitializer.initialize(c,plan,secrets,encoder).stream().allMatch(r->r.state().equals("ALREADY_EXISTS")));}
        assertEquals(v,version(admin));assertEquals(0,TestDatabase.scalar("SELECT must_change_password FROM identity_user WHERE id=?",admin.id));status(200,me(a));status(200,me(login(admin)));
        assertEquals(audits,TestDatabase.scalar("SELECT COUNT(*) FROM identity_security_audit WHERE target_type='USER' AND target_id=? AND action IN ('BOOTSTRAP_USER','BOOTSTRAP_CAPABILITY','PASSWORD_CHANGE')",admin.id));
    }
    @Test void passwordSecurityVersionCannotOverflow()throws Exception{
        TestDatabase.update("UPDATE identity_user SET security_version=? WHERE id=?",Long.MAX_VALUE,member.id);Session s=login(member);String n=new AuthCrypto().randomToken();
        error(409,"ACCOUNT_STATE_CONFLICT",password(s,member.password,n,n));assertEquals(Long.MAX_VALUE,version(member));
    }
    @Test void pendingCustomerCannotLoginAndManagementSummaryExposesOnlyItsState()throws Exception{
        Session a=normal(admin);TestDatabase.update("UPDATE identity_user SET identity_type='CUSTOMER',binding_state=1 WHERE id=?",member.id);
        error(401,"AUTHENTICATION_FAILED",loginResponse(member,member.password));var detail=get(a,"/"+member.id);status(200,detail);assertEquals("PENDING",body(detail).get("bindingState"));assertEquals("CUSTOMER",body(detail).get("identityType"));
    }
}
