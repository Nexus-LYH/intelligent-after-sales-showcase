package com.ias.gateway;

import com.ias.trust.*;
import org.junit.jupiter.api.*;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import javax.net.ssl.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Independent processes, TLS and real service-owned databases; all fixture writes guard3307 first. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS) @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChainIntegrationTest {
 final JsonMapper json=JsonMapper.builder().build();final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
 final Path privateRoot=Path.of("C:/ias-local/Develop/IAS-MySQL84/secrets"),services=Path.of("..").toAbsolutePath().normalize();
 final Map<String,Process> processes=new HashMap<>();final Map<String,Path> roleConfigurations=new HashMap<>();final Map<String,Map<String,String>> roleEnvironment=new HashMap<>();Map<String,Object> credentials;String suffix,codeA,codeB,password;long tenantA,tenantB,adminA,adminB,ordinary,platform;
 Session a,b,customer;Map<String,Object> companyA,companyA2,companyB,companyB2,modelA,modelB,deviceA,deviceA2,deviceB;String customerAccount,customerPassword;
 static final class Session{String cookie,csrf,user;@Override public String toString(){return "Session[redacted]";}}
 @SuppressWarnings("unchecked") Map<String,Object> read(Path p)throws Exception{return json.readValue(Files.readString(p),Map.class);}
 @BeforeAll void prepare()throws Exception{
  credentials=read(privateRoot.resolve("mysql-credentials.json"));suffix=UUID.randomUUID().toString().replace("-","").substring(0,10);codeA="CH_"+suffix.toUpperCase()+"A";codeB="CH_"+suffix.toUpperCase()+"B";password=random();
  try(var c=connection(false,false)){
   for(String code:List.of(codeA,codeB))update(c,"INSERT INTO tenant(code_normalized,name,status,created_at,updated_at) VALUES(?,'可信链测试',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",code);
   tenantA=scalar(c,"SELECT id FROM tenant WHERE code_normalized=?",codeA);tenantB=scalar(c,"SELECT id FROM tenant WHERE code_normalized=?",codeB);
   String verifier=new Argon2PasswordEncoder(16,32,1,19456,2).encode(password);
   adminA=user(c,tenantA,"a",verifier,true);adminB=user(c,tenantB,"b",verifier,true);ordinary=user(c,tenantA,"e",verifier,false);platform=user(c,null,"p",verifier,false);
  }
  start("identity");start("asset");start("gateway");
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);boolean ready=false;
  while(System.nanoTime()<end){try{if(send(null,"GET","/api/auth/csrf",null,Map.of()).statusCode()==200){ready=true;break;}}catch(Exception ignored){}Thread.sleep(200);}
  assertTrue(ready,"Three-service startup not ready; inspect private-safe target/chain-*.log");a=login(codeA,"a."+suffix,password);b=login(codeB,"b."+suffix,password);
 }
 @AfterAll void stop()throws Exception{for(Process p:processes.values())if(p.isAlive()){p.destroy();if(!p.waitFor(10,TimeUnit.SECONDS))p.destroyForcibly();}}
 void start(String role)throws Exception{start(role,0);}
 void start(String role,int overridePort)throws Exception{
  var configPath=roleConfigurations.getOrDefault(role,privateRoot.resolve("chain/"+role+".json"));var config=read(configPath);var pb=new ProcessBuilder(Path.of("C:/ias-local/Develop/jdk21/bin/java.exe").toString(),"-Xms32m","-Xmx96m","-XX:MaxMetaspaceSize=96m","-XX:ReservedCodeCacheSize=32m","-XX:ActiveProcessorCount=2","-XX:TieredStopAtLevel=1","-XX:+UseSerialGC","-jar",services.resolve(role+"/target/"+role+"-0.0.1-SNAPSHOT.jar").toString(),"--server.tomcat.threads.max=24","--server.tomcat.threads.min-spare=2","--spring.datasource.hikari.maximum-pool-size=4");
  var e=pb.environment();e.put("IAS_CHAIN_CONFIG",configPath.toString());e.put("SPRING_PROFILES_ACTIVE","local");e.put("SERVER_ADDRESS","127.0.0.1");e.put("SERVER_PORT",role.equals("identity")?"8441":role.equals("asset")?"8442":role.equals("ticket")?"8443":"8080");if(overridePort>0)e.put("SERVER_PORT",Integer.toString(overridePort));e.put("SPRING_FLYWAY_ENABLED","false");e.put("SPRING_SQL_INIT_MODE","never");
  if(!role.equals("gateway")){e.put("SERVER_SSL_ENABLED","true");e.put("SERVER_SSL_KEY_STORE",(String)config.get("serverStore"));e.put("SERVER_SSL_KEY_STORE_PASSWORD",(String)config.get("storePassword"));e.put("SERVER_SSL_KEY_STORE_TYPE","PKCS12");e.put("SERVER_SSL_KEY_ALIAS",role);boolean asset=role.equals("asset"),ticket=role.equals("ticket");e.put("SPRING_DATASOURCE_URL",ticket?"jdbc:mysql://127.0.0.1:3307/ias_ticket_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true":url(asset));e.put("SPRING_DATASOURCE_USERNAME",(String)credentials.get(ticket?"ticketAppUser":asset?"assetAppUser":"appUser"));e.put("SPRING_DATASOURCE_PASSWORD",(String)credentials.get(ticket?"ticketAppPassword":asset?"assetAppPassword":"appPassword"));}
  e.putAll(roleEnvironment.getOrDefault(role,Map.of()));pb.redirectErrorStream(true).redirectOutput(Path.of("target","chain-"+role+".log").toFile());var p=pb.start();p.getOutputStream().close();processes.put(role,p);
 }
 static String url(boolean asset){return "jdbc:mysql://127.0.0.1:3307/"+(asset?"ias_asset_test":"ias_identity_test")+"?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";}
 Connection connection(boolean asset,boolean runtime)throws Exception{
  String user=asset?(runtime?"assetAppUser":"assetMigrationUser"):(runtime?"appUser":"migrationUser"),secret=asset?(runtime?"assetAppPassword":"assetMigrationPassword"):(runtime?"appPassword":"migrationPassword");
  var c=DriverManager.getConnection(url(asset),(String)credentials.get(user),(String)credentials.get(secret));try(var s=c.createStatement();var r=s.executeQuery("SELECT @@port,@@datadir,DATABASE()")){r.next();assertEquals(3307,r.getInt(1));assertEquals("C:/ias-local/Develop/IAS-MySQL84/data/",r.getString(2).replace('\\','/'));assertEquals(asset?"ias_asset_test":"ias_identity_test",r.getString(3));}return c;
 }
 long user(Connection c,Long tenant,String prefix,String verifier,boolean administrator)throws Exception{
  update(c,"INSERT INTO identity_user(realm,tenant_id,account_normalized,identity_type,display_name,password_verifier,status,must_change_password,binding_state,created_at,updated_at) VALUES(?,?,?,?,?,?,1,0,0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",tenant==null?"PLATFORM":"TENANT",tenant,prefix+"."+suffix,tenant==null?"PLATFORM":"EMPLOYEE","可信链员工",verifier);
  long id=scalar(c,"SELECT id FROM identity_user WHERE account_normalized=?",prefix+"."+suffix);
  if(administrator)for(String cap:List.of("identity.session.revoke","asset.read","asset.manage","identity.customer.provision","ticket.read"))update(c,"INSERT INTO identity_user_capability(user_id,capability_code,enabled,created_at,updated_at) VALUES(?,?,1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id,cap);return id;
 }
 static void update(Connection c,String sql,Object...values)throws Exception{try(var p=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.executeUpdate();}}
 static long scalar(Connection c,String sql,Object...values)throws Exception{try(var p=c.prepareStatement(sql)){for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);try(var r=p.executeQuery()){r.next();return r.getLong(1);}}}
 HttpResponse<String> send(Session session,String method,String path,Map<String,Object> body,Map<String,String> extra)throws Exception{
  var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080"+path)).timeout(Duration.ofSeconds(12));request.header("Origin","http://127.0.0.1:5173");if(session!=null&&session.cookie!=null)request.header("Cookie",session.cookie);if(session!=null&&session.csrf!=null)request.header("X-CSRF-TOKEN",session.csrf);extra.forEach(request::header);
  if(!method.equals("GET"))request.header("Content-Type","application/json");var response=http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(),HttpResponse.BodyHandlers.ofString());
  if(session!=null)for(String cookie:response.headers().allValues("Set-Cookie"))if(cookie.startsWith("IAS-SESSION-DEV=")){session.cookie=cookie.contains("Max-Age=0")?null:cookie.split(";",2)[0];}
  return response;
 }
 @SuppressWarnings("unchecked")Map<String,Object> body(HttpResponse<String> r){return json.readValue(r.body(),Map.class);}
 void status(int expected,HttpResponse<String> r){assertEquals(expected,r.statusCode(),"Unexpected HTTP status; body/credentials intentionally omitted; safe code="+safeCode(r));}
 String safeCode(HttpResponse<String>r){try{String code=String.valueOf(body(r).get("code"));return code.matches("[A-Z_]{1,64}")?code:"UNSPECIFIED";}catch(Exception e){return "NON_JSON";}}
 Session login(String tenant,String account,String secret)throws Exception{
  var pre=send(null,"GET","/api/auth/csrf",null,Map.of());status(200,pre);String preCookie=pre.headers().allValues("Set-Cookie").stream().filter(v->v.startsWith("IAS-PREAUTH-DEV=")).findFirst().orElseThrow().split(";",2)[0];
  var s=new Session();Map<String,Object> payload=new HashMap<>();payload.put("realm",tenant==null?"PLATFORM":"TENANT");payload.put("tenantCode",tenant);payload.put("account",account);payload.put("password",secret);
  status(200,send(s,"POST","/api/auth/login",payload,Map.of("Cookie",preCookie,"X-CSRF-TOKEN",(String)body(pre).get("token"),"X-Login-Attempt-Id",UUID.randomUUID().toString())));
  String original=s.cookie;var me=send(s,"GET","/api/auth/me",null,Map.of());status(200,me);assertTrue(!original.equals(s.cookie),"First me must transparently replace authentication Cookie");s.user=(String)body(me).get("userId");
  var csrf=send(s,"GET","/api/auth/csrf",null,Map.of());status(200,csrf);s.csrf=(String)body(csrf).get("token");return s;
 }
 Map<String,Object> create(Session s,String kind,Map<String,Object>b)throws Exception{var r=send(s,"POST","/api/asset/"+kind,b,Map.of("Idempotency-Key",UUID.randomUUID().toString()));status(200,r);return body(r);}
 String random(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
 @Test @Order(1) void realGatewayControlsAndAssetCreateReadThroughTls()throws Exception{
  companyA=create(a,"customer-companies",Map.of("code","A_"+suffix,"name","企业A一"));companyA2=create(a,"customer-companies",Map.of("code","A2_"+suffix,"name","企业A二"));companyB=create(b,"customer-companies",Map.of("code","B_"+suffix,"name","企业B一"));companyB2=create(b,"customer-companies",Map.of("code","B2_"+suffix,"name","企业B二"));
  modelA=create(a,"product-models",Map.of("code","MA_"+suffix,"name","型号A"));modelB=create(b,"product-models",Map.of("code","MB_"+suffix,"name","型号B"));
  deviceA=create(a,"devices",Map.of("code","DA_"+suffix,"serialNumber","shared-"+suffix,"name","设备A一","companyId",companyA.get("id"),"modelId",modelA.get("id"),"warrantyStart","2026-01-01","warrantyEnd","2027-01-01"));
  deviceA2=create(a,"devices",Map.of("code","DA2_"+suffix,"serialNumber","other-"+suffix,"name","设备A二","companyId",companyA2.get("id"),"modelId",modelA.get("id")));
  deviceB=create(b,"devices",Map.of("code","DB_"+suffix,"serialNumber","shared-"+suffix,"name","设备B一","companyId",companyB.get("id"),"modelId",modelB.get("id")));
  status(200,send(a,"GET","/api/asset/devices",null,Map.of()));assertEquals(2,((Number)body(send(a,"GET","/api/asset/customer-companies",null,Map.of())).get("total")).intValue());
 }
 @Test @Order(2) void forgedHeadersCrossTenantPlatformAndOrdinaryAreDenied()throws Exception{
  status(401,send(null,"GET","/api/asset/devices",null,Map.of("X-User-Id",Long.toString(adminA))));
  status(404,send(a,"GET","/api/asset/devices/"+deviceB.get("id"),null,Map.of("X-User-Id",Long.toString(adminB),"X-Tenant-Id",Long.toString(tenantB),"Authorization","Bearer forged","X-IAS-Edge","identity-asset","X-Forwarded-Host","evil.invalid")));
  status(403,send(login(codeA,"e."+suffix,password),"GET","/api/asset/devices",null,Map.of()));status(403,send(login(null,"p."+suffix,password),"GET","/api/asset/devices",null,Map.of()));
  status(403,send(a,"POST","/internal/auth/introspect",Map.of(),Map.of()));status(400,send(a,"GET","/api/asset/devices?tenantId="+tenantB,null,Map.of()));
 }
 HttpClient tls(String peer)throws Exception{var cfg=read(privateRoot.resolve("chain/gateway.json"));KeyStore store=KeyStore.getInstance("PKCS12");try(var in=Files.newInputStream(privateRoot.resolve("chain/"+peer+"-trust.p12"))){store.load(in,((String)cfg.get("storePassword")).toCharArray());}var tm=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());tm.init(store);var ssl=SSLContext.getInstance("TLS");ssl.init(null,tm.getTrustManagers(),null);return HttpClient.newBuilder().sslContext(ssl).connectTimeout(Duration.ofSeconds(1)).build();}
 @SuppressWarnings("unchecked") HttpResponse<String> direct(String edge,Session s,String peer,String path,boolean duplicate)throws Exception{
  var cfg=read(privateRoot.resolve("chain/operator.json"));String token=(String)((Map<String,Object>)cfg.get("edges")).get(edge);
  var r=HttpRequest.newBuilder(URI.create("https://127.0.0.1:"+(peer.equals("identity")?8441:peer.equals("ticket")?8443:8442)+path)).timeout(Duration.ofSeconds(3));if(s!=null)r.header("Cookie",s.cookie);
  r.header("X-IAS-Edge",edge).header("Authorization","Bearer "+token).header("X-IAS-Deadline",Long.toString(System.currentTimeMillis()+9000)).header("X-IAS-Trace",UUID.randomUUID().toString());if(duplicate)r.header("X-IAS-Edge",edge);
  return tls(peer).send(r.GET().build(),HttpResponse.BodyHandlers.ofString());
 }
 @Test @Order(3) void bypassWrongEdgeDuplicateHeadersAndWrongTlsPeerAreRejected()throws Exception{
  var r=HttpRequest.newBuilder(URI.create("https://127.0.0.1:8442/api/asset/devices")).header("Cookie",a.cookie).GET().build();status(403,tls("asset").send(r,HttpResponse.BodyHandlers.ofString()));
  status(403,direct("identity-asset",a,"asset","/api/asset/devices",false));status(403,direct("gateway-asset",a,"asset","/api/asset/devices",true));
  assertThrows(java.io.IOException.class,()->tls("identity").send(r,HttpResponse.BodyHandlers.ofString()));status(200,direct("gateway-asset",a,"asset","/api/asset/devices",false));
  var wrong=HttpRequest.newBuilder(URI.create("https://127.0.0.1:8442/api/asset/devices")).header("Cookie",a.cookie).header("X-IAS-Edge","gateway-asset").header("Authorization","Bearer incorrect").header("X-IAS-Trace",UUID.randomUUID().toString()).header("X-IAS-Deadline",Long.toString(System.currentTimeMillis()+9000)).GET().build();status(403,tls("asset").send(wrong,HttpResponse.BodyHandlers.ofString()));
 }
 @Test @Order(4) void serialReferencesWarrantyAndOwnershipCannotBeBypassed()throws Exception{
  var data=new HashMap<String,Object>(Map.of("code","DUP_"+suffix,"serialNumber","shared-"+suffix,"name","重复序号","companyId",companyA.get("id"),"modelId",modelA.get("id")));
  status(409,send(a,"POST","/api/asset/devices",data,Map.of("Idempotency-Key",UUID.randomUUID().toString())));
  data.put("serialNumber",random());data.put("companyId",companyB.get("id"));status(404,send(a,"POST","/api/asset/devices",data,Map.of("Idempotency-Key",UUID.randomUUID().toString())));
  data.put("companyId",companyA.get("id"));data.put("warrantyStart","2027-01-01");data.put("warrantyEnd","2026-01-01");status(400,send(a,"POST","/api/asset/devices",data,Map.of("Idempotency-Key",UUID.randomUUID().toString())));
  status(400,send(a,"PATCH","/api/asset/devices/"+deviceA.get("id"),Map.of("expectedVersion","1","companyId",companyA2.get("id")),Map.of("Idempotency-Key",UUID.randomUUID().toString())));
  try(var c=connection(true,false)){assertThrows(SQLException.class,()->update(c,"INSERT INTO asset_device(tenant_id,code,serial_number,name,company_id,model_id,created_at,updated_at) VALUES(?,'FK_TEST',?,'跨租户FK',?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",tenantA,random(),Long.parseLong((String)companyB.get("id")),Long.parseLong((String)modelA.get("id"))));}
 }
 @Test @Order(5) void createAndEditIdempotencyConcurrencyAndVersionConflict()throws Exception{
  String key=UUID.randomUUID().toString();Map<String,Object> data=Map.of("code","CON_"+suffix,"name","并发幂等企业");List<HttpResponse<String>> results=parallel(()->send(a,"POST","/api/asset/customer-companies",data,Map.of("Idempotency-Key",key)),()->send(a,"POST","/api/asset/customer-companies",data,Map.of("Idempotency-Key",key)));
  for(var r:results)status(200,r);assertEquals(body(results.getFirst()),body(results.getLast()));status(409,send(a,"POST","/api/asset/customer-companies",Map.of("code","OTHER_"+suffix,"name","不同内容"),Map.of("Idempotency-Key",key)));
  String path="/api/asset/customer-companies/"+body(results.getFirst()).get("id");Map<String,Object> edit=Map.of("expectedVersion","1","name","第一次编辑");String editKey=UUID.randomUUID().toString();var first=send(a,"PATCH",path,edit,Map.of("Idempotency-Key",editKey));status(200,first);status(200,send(a,"PATCH",path,edit,Map.of("Idempotency-Key",editKey)));status(409,send(a,"PATCH",path,edit,Map.of("Idempotency-Key",UUID.randomUUID().toString())));
 }
 @Test @Order(6) void assetAuditFailureRollsBackBusinessAndOperation()throws Exception{
  String key=UUID.randomUUID().toString(),constraint="it_chain_audit_"+suffix;try(var c=connection(true,false)){update(c,"ALTER TABLE asset_audit ADD CONSTRAINT "+constraint+" CHECK(operation_id<>'"+key+"')");}
  Map<String,Object> data=Map.of("code","ROLL_"+suffix,"name","事务回滚企业");try{status(503,send(a,"POST","/api/asset/customer-companies",data,Map.of("Idempotency-Key",key)));try(var c=connection(true,false)){assertEquals(0,scalar(c,"SELECT COUNT(*) FROM asset_company WHERE tenant_id=? AND code=?",tenantA,"ROLL_"+suffix.toUpperCase()));assertEquals(0,scalar(c,"SELECT COUNT(*) FROM asset_operation WHERE operation_id=?",key));}}
  finally{try(var c=connection(true,false)){update(c,"ALTER TABLE asset_audit DROP CHECK "+constraint);}}
  status(200,send(a,"POST","/api/asset/customer-companies",data,Map.of("Idempotency-Key",key)));
 }
 @Test @Order(7) void realCustomerProvisionFirstPasswordAndCurrentMembershipScope()throws Exception{
  customerAccount="customer."+suffix;customerPassword=random();var result=send(a,"POST","/api/asset/customer-companies/"+companyA.get("id")+"/members",Map.of("account",customerAccount,"displayName","客户一","initialPassword",customerPassword),Map.of("Idempotency-Key",UUID.randomUUID().toString()));status(200,result);assertEquals("COMPLETED",body(result).get("state"));
  Session restricted=login(codeA,customerAccount,customerPassword);status(403,send(restricted,"GET","/api/asset/devices",null,Map.of()));String next=random();status(200,send(restricted,"POST","/api/auth/password",Map.of("currentPassword",customerPassword,"newPassword",next,"confirmPassword",next),Map.of()));customerPassword=next;customer=login(codeA,customerAccount,next);
  var list=send(customer,"GET","/api/asset/devices",null,Map.of());status(200,list);assertEquals(1,((Number)body(list).get("total")).intValue());status(200,send(customer,"GET","/api/asset/devices/"+deviceA.get("id"),null,Map.of()));status(404,send(customer,"GET","/api/asset/devices/"+deviceA2.get("id"),null,Map.of()));status(404,send(customer,"GET","/api/asset/devices/"+deviceB.get("id"),null,Map.of()));status(403,send(customer,"GET","/api/asset/product-models",null,Map.of()));status(403,send(customer,"POST","/api/asset/devices",Map.of(),Map.of("Idempotency-Key",UUID.randomUUID().toString())));
 }
 @Test @Order(8) void pendingAccountAndCommittedMemberActivationFailureRecoverWithoutSecretReplay()throws Exception{recoverEnrollment("CUSTOMER_MEMBERSHIP_COMMITTED",false);recoverEnrollment("CUSTOMER_BOUND",true);}
 @Test @Order(8) void registeredWithoutIdentityRequiresExplicitResumeThenSameKeyPassword()throws Exception{
  var company=create(a,"customer-companies",Map.of("code","ID04_"+suffix,"name","恢复入口企业"));String key=UUID.randomUUID().toString(),account="id04."+suffix,secret=random(),constraint="it_id04_"+suffix;
  var input=Map.<String,Object>of("account",account,"displayName","入口恢复客户","initialPassword",secret);
  try(var c=connection(false,false)){long priorUser=scalar(c,"SELECT COALESCE(MAX(id),0) FROM identity_user");update(c,"ALTER TABLE identity_security_audit ADD CONSTRAINT "+constraint+" CHECK(NOT(action='CUSTOMER_PENDING' AND actor_user_id="+adminA+" AND target_id>"+priorUser+"))");}
  try{status(503,send(a,"POST","/api/asset/customer-companies/"+company.get("id")+"/members",input,Map.of("Idempotency-Key",key)));}
  finally{try(var c=connection(false,false)){update(c,"ALTER TABLE identity_security_audit DROP CHECK "+constraint);}}
  try(var c=connection(false,false)){assertEquals(0,scalar(c,"SELECT COUNT(*) FROM identity_customer_enrollment WHERE operation_id=?",key));assertEquals(0,scalar(c,"SELECT COUNT(*) FROM identity_user WHERE tenant_id=? AND account_normalized=?",tenantA,account));}
  for(int i=0;i<3;i++){var queried=send(a,"GET","/api/asset/customer-enrollments/"+key,null,Map.of());status(200,queried);assertEquals("REGISTERED",body(queried).get("state"));assertEquals("RESUME",body(queried).get("nextAction"));}
  try(var c=connection(true,false)){assertEquals(0,scalar(c,"SELECT COUNT(*) FROM asset_membership WHERE operation_id=?",key));assertEquals(0,scalar(c,"SELECT COUNT(*) FROM asset_audit WHERE operation_id=?",key));}
  status(404,send(b,"POST","/api/asset/customer-enrollments/"+key+"/resume",Map.of(),Map.of()));
  try(var c=connection(false,false)){update(c,"UPDATE identity_user_capability SET enabled=0 WHERE user_id=? AND capability_code='identity.customer.provision'",adminA);}
  try{status(403,send(a,"GET","/api/asset/customer-enrollments/"+key,null,Map.of()));status(403,send(a,"POST","/api/asset/customer-enrollments/"+key+"/resume",Map.of(),Map.of()));}
  finally{try(var c=connection(false,false)){update(c,"UPDATE identity_user_capability SET enabled=1 WHERE user_id=? AND capability_code='identity.customer.provision'",adminA);}}
  var waiting=send(a,"POST","/api/asset/customer-enrollments/"+key+"/resume",Map.of(),Map.of());status(200,waiting);assertEquals("AWAITING_INITIAL_PASSWORD",body(waiting).get("state"));assertEquals("ENTER_INITIAL_PASSWORD",body(waiting).get("nextAction"));
  try(var c=connection(false,false)){assertEquals(0,scalar(c,"SELECT COUNT(*) FROM identity_customer_enrollment WHERE operation_id=?",key));}
  var done=send(a,"POST","/api/asset/customer-companies/"+company.get("id")+"/members",input,Map.of("Idempotency-Key",key));status(200,done);assertEquals("COMPLETED",body(done).get("state"));
  status(200,send(a,"POST","/api/asset/customer-enrollments/"+key+"/resume",Map.of(),Map.of()));
  status(200,send(a,"POST","/api/asset/customer-companies/"+company.get("id")+"/members",Map.of("account",account,"displayName","入口恢复客户","initialPassword",random()),Map.of("Idempotency-Key",key)));
  login(codeA,account,secret);
  try(var c=connection(true,false)){assertEquals(1,scalar(c,"SELECT COUNT(*) FROM asset_membership WHERE operation_id=?",key));}
  try(var c=connection(false,false)){assertEquals(1,scalar(c,"SELECT COUNT(*) FROM identity_customer_enrollment WHERE operation_id=?",key));assertEquals(1,scalar(c,"SELECT COUNT(*) FROM identity_user WHERE tenant_id=? AND account_normalized=?",tenantA,account));}
  var detail=send(a,"GET","/api/identity/users/"+a.user,null,Map.of());status(200,detail);String oldCookie=a.cookie;
  status(200,send(a,"POST","/api/identity/users/"+a.user+"/sessions/revoke",Map.of("reason","恢复接口撤权禁止核验","expectedSecurityVersion",body(detail).get("securityVersion")),Map.of("Idempotency-Key",UUID.randomUUID().toString())));
  Session revoked=new Session();revoked.cookie=oldCookie;revoked.csrf=a.csrf;
  status(401,send(revoked,"GET","/api/asset/customer-enrollments/"+key,null,Map.of()));revoked.cookie=oldCookie;
  status(401,send(revoked,"POST","/api/asset/customer-enrollments/"+key+"/resume",Map.of(),Map.of()));a=login(codeA,"a."+suffix,password);
 }
 void recoverEnrollment(String failAction,boolean bound)throws Exception{
  String key=UUID.randomUUID().toString(),account="recover."+UUID.randomUUID().toString().substring(0,8),secret=random(),constraint="it_enrollment_"+key.replace("-","");
  try(var c=connection(true,false)){update(c,"ALTER TABLE asset_audit ADD CONSTRAINT "+constraint+" CHECK(NOT(operation_id='"+key+"' AND action='"+failAction+"'))");}
  try{
   status(503,send(a,"POST","/api/asset/customer-companies/"+companyA.get("id")+"/members",Map.of("account",account,"displayName","恢复客户","initialPassword",secret),Map.of("Idempotency-Key",key)));
   var state=send(a,"GET","/api/asset/customer-enrollments/"+key,null,Map.of());status(200,state);assertFalse(body(state).get("state").equals("COMPLETED"));
   if(!bound)for(int i=0;i<3;i++){var queried=send(a,"GET","/api/asset/customer-enrollments/"+key,null,Map.of());status(200,queried);assertEquals("REGISTERED",body(queried).get("state"));assertEquals("RESUME",body(queried).get("nextAction"));try(var c=connection(true,false)){assertEquals(0,scalar(c,"SELECT COUNT(*) FROM asset_membership WHERE operation_id=?",key));assertEquals(0,scalar(c,"SELECT COUNT(*) FROM asset_audit WHERE operation_id=?",key));}}
   try(var c=connection(false,false)){assertEquals(bound?2:1,scalar(c,"SELECT binding_state FROM identity_user WHERE id=(SELECT user_id FROM identity_customer_enrollment WHERE operation_id=?)",key));}
   if(!bound){var pre=send(null,"GET","/api/auth/csrf",null,Map.of());String pc=pre.headers().allValues("Set-Cookie").getFirst().split(";",2)[0];status(401,send(null,"POST","/api/auth/login",Map.of("realm","TENANT","tenantCode",codeA,"account",account,"password",secret),Map.of("Cookie",pc,"X-CSRF-TOKEN",(String)body(pre).get("token"),"X-Login-Attempt-Id",UUID.randomUUID().toString())));}
  }finally{try(var c=connection(true,false)){update(c,"ALTER TABLE asset_audit DROP CHECK "+constraint);}}
  var result=send(a,"POST","/api/asset/customer-enrollments/"+key+"/resume",Map.of(),Map.of());status(200,result);assertEquals("COMPLETED",body(result).get("state"));
  var repeated=send(a,"POST","/api/asset/customer-companies/"+companyA.get("id")+"/members",Map.of("account",account,"displayName","恢复客户","initialPassword",random()),Map.of("Idempotency-Key",key));status(200,repeated);
  login(codeA,account,secret);try(var c=connection(true,false)){assertEquals(1,scalar(c,"SELECT COUNT(*) FROM asset_membership WHERE operation_id=?",key));}
 }
 @Test @Order(9) void companyDisableDuringPendingProvisionBlocksActivationAndCanResume()throws Exception{
  var c=create(a,"customer-companies",Map.of("code","BLOCK_"+suffix,"name","中断企业"));String key=UUID.randomUUID().toString(),constraint="it_block_"+suffix;
  try(var conn=connection(true,false)){update(conn,"ALTER TABLE asset_audit ADD CONSTRAINT "+constraint+" CHECK(NOT(operation_id='"+key+"' AND action='CUSTOMER_MEMBERSHIP_COMMITTED'))");}
  try{status(503,send(a,"POST","/api/asset/customer-companies/"+c.get("id")+"/members",Map.of("account","blocked."+suffix,"displayName","暂缓客户","initialPassword",random()),Map.of("Idempotency-Key",key)));}finally{try(var conn=connection(true,false)){update(conn,"ALTER TABLE asset_audit DROP CHECK "+constraint);}}
  var disabled=send(a,"POST","/api/asset/customer-companies/"+c.get("id")+"/status",Map.of("expectedVersion","1","status","DISABLED","reason","开通期间停用企业"),Map.of("Idempotency-Key",UUID.randomUUID().toString()));status(200,disabled);
  var blocked=send(a,"POST","/api/asset/customer-enrollments/"+key+"/resume",Map.of(),Map.of());status(200,blocked);assertEquals("BLOCKED",body(blocked).get("state"));
  status(200,send(a,"POST","/api/asset/customer-companies/"+c.get("id")+"/status",Map.of("expectedVersion","2","status","ENABLED","reason","恢复待激活客户开通"),Map.of("Idempotency-Key",UUID.randomUUID().toString())));assertEquals("COMPLETED",body(send(a,"POST","/api/asset/customer-enrollments/"+key+"/resume",Map.of(),Map.of())).get("state"));
 }
 @Test @Order(10) void independentAssetRecheckFailsClosedWhenIdentityUnavailable()throws Exception{
  var asset=processes.get("asset");asset.destroy();assertTrue(asset.waitFor(10,TimeUnit.SECONDS));
  try{status(503,send(a,"GET","/api/asset/devices",null,Map.of()));}finally{start("asset");}
  long assetEnd=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);boolean assetRestored=false;
  while(System.nanoTime()<assetEnd){try{if(send(a,"GET","/api/asset/devices",null,Map.of()).statusCode()==200){assetRestored=true;break;}}catch(Exception ignored){}Thread.sleep(200);}assertTrue(assetRestored);
  var identity=processes.get("identity");identity.destroy();assertTrue(identity.waitFor(10,TimeUnit.SECONDS));
  try{status(503,send(a,"GET","/api/asset/devices",null,Map.of()));status(503,direct("gateway-asset",a,"asset","/api/asset/devices",false));}
  finally{start("identity");}
  long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);boolean restored=false;while(System.nanoTime()<end){try{if(send(a,"GET","/api/asset/devices",null,Map.of()).statusCode()==200){restored=true;break;}}catch(Exception ignored){}Thread.sleep(200);}assertTrue(restored);
 }
 @Test @Order(11) void databaseRolesCannotCrossServiceOrRewriteAudit()throws Exception{
  try(var c=connection(true,true);var s=c.createStatement()){assertThrows(SQLException.class,()->s.executeQuery("SELECT id FROM ias_identity_test.identity_user LIMIT 1"));assertThrows(SQLException.class,()->s.executeUpdate("UPDATE asset_audit SET action=action WHERE 1=0"));assertThrows(SQLException.class,()->s.executeUpdate("DELETE FROM asset_audit WHERE 1=0"));}
  try(var c=connection(false,true);var s=c.createStatement()){assertThrows(SQLException.class,()->s.executeQuery("SELECT id FROM ias_asset_test.asset_device LIMIT 1"));}
 }
 @Test @Order(11) void membershipIsCurrentAssetFactAndCannotBeReplacedByIdentityCache()throws Exception{
  try(var c=connection(true,false)){update(c,"UPDATE asset_membership SET state='PROVISIONING',row_version=row_version+1 WHERE tenant_id=? AND user_id=?",tenantA,Long.parseLong(customer.user));}
  try{status(200,send(customer,"GET","/api/auth/me",null,Map.of()));var empty=send(customer,"GET","/api/asset/devices",null,Map.of());status(200,empty);assertEquals(0,((Number)body(empty).get("total")).intValue());status(404,send(customer,"GET","/api/asset/devices/"+deviceA.get("id"),null,Map.of()));}
  finally{try(var c=connection(true,false)){update(c,"UPDATE asset_membership SET state='ACTIVE',row_version=row_version+1 WHERE tenant_id=? AND user_id=?",tenantA,Long.parseLong(customer.user));}}
 }
 @Test @Order(11) void simultaneousCompanyDisableAndCustomerProvisionHasNoInvalidActiveBinding()throws Exception{
  var company=create(a,"customer-companies",Map.of("code","RACE_"+suffix,"name","并发停用企业"));String op=UUID.randomUUID().toString();
  var results=parallel(()->send(a,"POST","/api/asset/customer-companies/"+company.get("id")+"/members",Map.of("account","race."+suffix,"displayName","竞争客户","initialPassword",random()),Map.of("Idempotency-Key",op)),()->send(a,"POST","/api/asset/customer-companies/"+company.get("id")+"/status",Map.of("expectedVersion","1","status","DISABLED","reason","与客户开通并发停用"),Map.of("Idempotency-Key",UUID.randomUUID().toString())));
  status(200,results.getLast());assertTrue(Set.of(200,409).contains(results.getFirst().statusCode()));
  try(var c=connection(true,false)){long active=scalar(c,"SELECT COUNT(*) FROM asset_membership WHERE operation_id=? AND state='ACTIVE'",op);if(active==1)assertEquals(1,scalar(c,"SELECT COUNT(*) FROM asset_operation WHERE operation_id=? AND state='COMPLETED'",op));else assertEquals(0,scalar(c,"SELECT COUNT(*) FROM asset_membership WHERE operation_id=? AND state='ACTIVE'",op));}
 }
 @Test @Order(12) void revocationOldCustomerDeniedThroughBothVerifiersImmediatelyAndAtSixtySeconds()throws Exception{
  String beforePassword=customer.cookie,newPassword=random();
  status(200,send(customer,"POST","/api/auth/password",Map.of("currentPassword",customerPassword,"newPassword",newPassword,"confirmPassword",newPassword),Map.of()));
  Session passwordOld=new Session();passwordOld.cookie=beforePassword;assertBothDenied(passwordOld);customer=login(codeA,customerAccount,newPassword);
  var employeeDetail=send(b,"GET","/api/identity/users/"+b.user,null,Map.of());status(200,employeeDetail);String employeeOldCookie=b.cookie;
  status(200,send(b,"POST","/api/identity/users/"+b.user+"/sessions/revoke",Map.of("reason","员工可信链撤权核验","expectedSecurityVersion",body(employeeDetail).get("securityVersion")),Map.of("Idempotency-Key",UUID.randomUUID().toString())));
  Session employeeOld=new Session();employeeOld.cookie=employeeOldCookie;assertBothDenied(employeeOld);
  var detail=send(a,"GET","/api/identity/users/"+customer.user,null,Map.of());status(200,detail);
  var revoked=send(a,"POST","/api/identity/users/"+customer.user+"/sessions/revoke",Map.of("reason","可信资产链撤权核验","expectedSecurityVersion",body(detail).get("securityVersion")),Map.of("Idempotency-Key",UUID.randomUUID().toString()));status(200,revoked);long observed=System.nanoTime();String original=customer.cookie;
  status(401,send(customer,"GET","/api/asset/devices",null,Map.of()));customer.cookie=original;status(401,direct("gateway-asset",customer,"asset","/api/asset/devices",false));Thread.sleep(Duration.ofSeconds(60));customer.cookie=original;long later=System.nanoTime();status(401,send(customer,"GET","/api/asset/devices",null,Map.of()));customer.cookie=original;status(401,direct("gateway-asset",customer,"asset","/api/asset/devices",false));
  passwordOld.cookie=beforePassword;assertBothDenied(passwordOld);employeeOld.cookie=employeeOldCookie;assertBothDenied(employeeOld);
  Files.writeString(Path.of("target","chain-revocation-timing.json"),json.writeValueAsString(Map.of("scope","customer revocation, employee self-revocation, customer password change; gateway and independent asset recheck","t0","observed final customer revocation success response, not exact database commit; other changes committed earlier","laterRequestMs",TimeUnit.NANOSECONDS.toMillis(later-observed),"gatewayStatus",401,"assetStatus",401)));
 }
 void assertBothDenied(Session session)throws Exception{String cookie=session.cookie;status(401,send(session,"GET","/api/asset/devices",null,Map.of()));session.cookie=cookie;status(401,direct("gateway-asset",session,"asset","/api/asset/devices",false));session.cookie=cookie;}
 List<HttpResponse<String>> parallel(Callable<HttpResponse<String>>x,Callable<HttpResponse<String>>y)throws Exception{try(var e=Executors.newVirtualThreadPerTaskExecutor()){var a=e.submit(x);var b=e.submit(y);return List.of(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));}}
}




