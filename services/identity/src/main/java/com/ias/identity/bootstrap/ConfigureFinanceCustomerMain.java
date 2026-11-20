package com.ias.identity.bootstrap;

import com.ias.identity.auth.AuthCrypto;
import com.ias.trust.Chain;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static com.ias.identity.bootstrap.ConfigureStaffMain.*;

/** Explicit two-capability customer plan, separate physical operator connection; never assigns an asset role. */
public final class ConfigureFinanceCustomerMain {
 public static final Set<String> CAPS=Set.of("finance.quote.read","finance.quote.confirm");
 public record Customer(String userId,String expectedVersion,Map<String,Boolean> capabilities){}
 public record Plan(String operationId,String tenantCode,List<Customer> customers){}
 public static void main(String[] args){
  try{
   if(args.length!=1||!Set.of("--dry-run","--execute").contains(args[0]))throw new IllegalArgumentException();
   Path path=Path.of(System.getenv("IAS_FINANCE_CUSTOMER_PLAN"));if(Files.size(path)>65536)throw new IllegalArgumentException();
   Plan plan=parsePlan(Files.readString(path));
   try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3307/ias_identity_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",System.getenv("IAS_MIGRATION_USER"),System.getenv("IAS_MIGRATION_PASSWORD"))){
    try(var s=c.createStatement();var r=s.executeQuery("SELECT @@port,DATABASE(),@@datadir")){r.next();if(r.getInt(1)!=3307||!"ias_identity_test".equals(r.getString(2))||!r.getString(3).replace('\\','/').equalsIgnoreCase("C:/ias-local/Develop/IAS-MySQL84/data/"))throw new IllegalStateException();}
    System.out.println(Chain.JSON.writeValueAsString(configure(c,plan,args[0].equals("--execute"))));
   }
  }catch(Exception ignored){System.err.println("CUSTOMER_FINANCE_CONFIGURATION_NOT_CONFIRMED; reconcile original operation and restricted plan; no password or role changed by this tool");System.exit(1);}
 }
 public static Plan parsePlan(String json){
  var mapper=tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  Map<?,?> raw=mapper.readValue(json,Map.class);if(!raw.keySet().equals(Set.of("operationId","tenantCode","customers"))||!(raw.get("operationId") instanceof String operation)||!(raw.get("tenantCode") instanceof String tenant)||!(raw.get("customers") instanceof List<?> users)||users.isEmpty()||users.size()>50)throw new IllegalArgumentException();var customers=new ArrayList<Customer>();
  for(Object value:users){if(!(value instanceof Map<?,?> user)||!user.keySet().equals(Set.of("userId","expectedVersion","capabilities"))||!(user.get("userId") instanceof String id)||!(user.get("expectedVersion") instanceof String version)||!(user.get("capabilities") instanceof Map<?,?> caps)||!caps.keySet().equals(CAPS)||!(caps.get("finance.quote.read") instanceof Boolean read)||!(caps.get("finance.quote.confirm") instanceof Boolean confirm))throw new IllegalArgumentException();customers.add(new Customer(id,version,Map.of("finance.quote.read",read,"finance.quote.confirm",confirm)));}
  Plan p=new Plan(operation,tenant,List.copyOf(customers));validate(p);return p;
 }
 static long nonnegative(String s){if(s==null||!s.matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException();return Long.parseLong(s);}
 static long id(String s){long v=nonnegative(s);if(v==0)throw new IllegalArgumentException();return v;}
 public static void validate(Plan p){
  if(p==null||p.operationId()==null||!UUID.fromString(p.operationId()).toString().equals(p.operationId())||p.customers()==null||p.customers().isEmpty()||p.customers().size()>50)throw new IllegalArgumentException();AuthCrypto.tenantCode(p.tenantCode());var ids=new HashSet<Long>();
  for(var c:p.customers()){if(c==null||!ids.add(id(c.userId())))throw new IllegalArgumentException();nonnegative(c.expectedVersion());if(c.capabilities()==null||!c.capabilities().keySet().equals(CAPS)||c.capabilities().values().stream().anyMatch(Objects::isNull))throw new IllegalArgumentException();if(c.capabilities().get("finance.quote.confirm")&&!c.capabilities().get("finance.quote.read"))throw new IllegalArgumentException();}
 }
 static String digest(Plan p)throws Exception{var users=p.customers().stream().sorted(Comparator.comparingLong(c->id(c.userId()))).map(c->new Customer(c.userId(),c.expectedVersion(),new TreeMap<>(c.capabilities()))).toList();return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Chain.JSON.writeValueAsBytes(List.of("CUSTOMER_FINANCE_V1",p.operationId(),AuthCrypto.tenantCode(p.tenantCode()),users))));}
 public static Map<String,Object> configure(Connection c,Plan plan,boolean execute)throws Exception{
  validate(plan);String digest=digest(plan);if(!c.getAutoCommit())throw new IllegalArgumentException("Dedicated physical operator connection required");c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
  boolean ended=false,rollbackAttempted=false,committing=false;
  try{
   var tenants=query(c,"SELECT id,status FROM tenant WHERE code_normalized=? FOR UPDATE",AuthCrypto.tenantCode(plan.tenantCode()));if(tenants.size()!=1||((Number)tenants.getFirst().get("status")).intValue()!=1)throw new IllegalArgumentException();long tenant=((Number)tenants.getFirst().get("id")).longValue();
   var ordered=new TreeMap<Long,Customer>();for(var customer:plan.customers())ordered.put(id(customer.userId()),customer);
   var records=new TreeMap<Long,Map<String,Object>>();
   for(long user:ordered.keySet()){
    var rows=query(c,"SELECT id,realm,identity_type,status,binding_state,must_change_password,row_version,security_version FROM identity_user WHERE tenant_id=? AND id=? FOR UPDATE",tenant,user);
    if(rows.size()!=1)throw new IllegalArgumentException();var row=rows.getFirst();if(!"TENANT".equals(row.get("realm"))||!"CUSTOMER".equals(row.get("identity_type"))||((Number)row.get("status")).intValue()!=1||((Number)row.get("binding_state")).intValue()!=2||((Number)row.get("must_change_password")).intValue()!=0)throw new IllegalArgumentException();records.put(user,row);
   }
   var old=query(c,"SELECT request_digest,result_json FROM identity_configuration_operation WHERE operation_id=?",plan.operationId());
   if(!old.isEmpty()){if(!digest.equals(old.getFirst().get("request_digest")))throw new IllegalArgumentException();rollbackAttempted=true;c.rollback();ended=true;return Chain.JSON.readValue(old.getFirst().get("result_json").toString(),Map.class);}
   var output=new ArrayList<Map<String,Object>>();
   for(var e:ordered.entrySet()){
    long user=e.getKey();var row=records.get(user);long version=((Number)row.get("row_version")).longValue(),security=((Number)row.get("security_version")).longValue();if(version!=nonnegative(e.getValue().expectedVersion())||version==Long.MAX_VALUE||security==Long.MAX_VALUE)throw new IllegalArgumentException();
    for(var cap:new TreeMap<>(e.getValue().capabilities()).entrySet())update(c,"INSERT INTO identity_user_capability(user_id,capability_code,enabled,created_at,updated_at) VALUES(?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),row_version=row_version+1,updated_at=UTC_TIMESTAMP(3)",user,cap.getKey(),cap.getValue()?1:0);
    try(var p=c.prepareStatement("UPDATE identity_user SET row_version=row_version+1,security_version=security_version+1,updated_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND id=? AND row_version=?")){p.setLong(1,tenant);p.setLong(2,user);p.setLong(3,version);if(p.executeUpdate()!=1)throw new IllegalArgumentException();}
    update(c,"INSERT INTO identity_security_audit(occurred_at,action,actor_type,target_type,target_id,tenant_id,result,reason_code,user_security_version_before,user_security_version_after,trace_id) VALUES(UTC_TIMESTAMP(3),'CUSTOMER_FINANCE_CAPS_CHANGED','SYSTEM','USER',?,?,'SUCCESS','EXPLICIT_CUSTOMER_FINANCE_PLAN',?,?,?)",user,tenant,security,security+1,plan.operationId());
    output.add(Map.of("userId",Long.toString(user),"rowVersion",Long.toString(version+1),"securityVersion",Long.toString(security+1)));
   }
   Map<String,Object> result=Map.of("operationId",plan.operationId(),"tenantId",Long.toString(tenant),"state",execute?"COMMITTED":"DRY_RUN","customers",output);
   if(execute){update(c,"INSERT INTO identity_configuration_operation(operation_id,request_digest,result_json,created_at) VALUES(?,?,?,UTC_TIMESTAMP(3))",plan.operationId(),digest,Chain.JSON.writeValueAsString(result));committing=true;c.commit();ended=true;}
   else{rollbackAttempted=true;c.rollback();ended=true;}return result;
  }catch(Exception failure){if(!ended&&!rollbackAttempted)try{rollbackAttempted=true;c.rollback();ended=true;}catch(Exception rollback){failure.addSuppressed(rollback);}if(!ended||committing)abort(c,failure);throw failure;}
  // This dedicated physical connection is closed by the CLI. Never restore auto-commit, including failure paths.
 }
 static void abort(Connection c,Exception original){try{c.abort(Runnable::run);}catch(Exception failure){original.addSuppressed(failure);}}
}
