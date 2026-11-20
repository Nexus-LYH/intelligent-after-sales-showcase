package com.ias.identity.bootstrap;

import com.ias.identity.auth.*;
import com.ias.trust.Chain;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import static com.ias.identity.bootstrap.ConfigureStaffMain.*;

/** Explicit additive domain capability/scope plan. Never starts during normal application boot. */
public final class ConfigureDomainStaffMain {
 public record Employee(String userId,String expectedVersion,Map<String,Boolean> capabilities,List<String> approvalReviewDepartmentIds,List<String> warehouseIds){}
 public record Plan(String operationId,String tenantCode,List<Employee> employees){}
 public static void main(String[] args){
  try{
   if(args.length!=1||!Set.of("--dry-run","--execute").contains(args[0]))throw new IllegalArgumentException();
   Path file=Path.of(System.getenv("IAS_DOMAIN_STAFF_PLAN"));if(Files.size(file)>65536)throw new IllegalArgumentException();Plan plan=Chain.JSON.readValue(Files.readString(file),Plan.class);
   try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3307/ias_identity_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",System.getenv("IAS_MIGRATION_USER"),System.getenv("IAS_MIGRATION_PASSWORD"))){
    try(var s=c.createStatement();var r=s.executeQuery("SELECT @@port,DATABASE(),@@datadir")){r.next();if(r.getInt(1)!=3307||!r.getString(2).equals("ias_identity_test")||!r.getString(3).replace('\\','/').equalsIgnoreCase("C:/ias-local/Develop/IAS-MySQL84/data/"))throw new IllegalStateException();}
    System.out.println(Chain.JSON.writeValueAsString(configure(c,plan,args[0].equals("--execute"))));
   }
  }catch(Exception ignored){System.err.println("DOMAIN_CONFIGURATION_NOT_CONFIRMED; inspect original operation using the restricted plan reference");System.exit(1);}
 }
 public static Map<String,Object> configure(Connection c,Plan plan,boolean execute)throws Exception{
  validate(plan);String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Chain.JSON.writeValueAsBytes(plan)));boolean ended=false,committing=false;
  c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
  try{
   long tenant=number(c,"SELECT id FROM tenant WHERE code_normalized=? FOR UPDATE",AuthCrypto.tenantCode(plan.tenantCode()));
   var old=query(c,"SELECT * FROM identity_configuration_operation WHERE operation_id=?",plan.operationId());
   if(!old.isEmpty()){if(!digest.equals(old.getFirst().get("request_digest")))throw new IllegalArgumentException();c.rollback();ended=true;return Chain.JSON.readValue(old.getFirst().get("result_json").toString(),Map.class);}
   var ordered=new TreeMap<Long,Employee>();for(var e:plan.employees())if(ordered.put(id(e.userId()),e)!=null)throw new IllegalArgumentException();
   for(long user:ordered.keySet())query(c,"SELECT id FROM identity_user WHERE tenant_id=? AND id=? FOR UPDATE",tenant,user);
   int changes=0;
   for(var entry:ordered.entrySet()){
    long user=entry.getKey();var e=entry.getValue();var rows=query(c,"SELECT u.row_version,u.security_version FROM identity_user u JOIN identity_employee_configuration f ON f.tenant_id=u.tenant_id AND f.user_id=u.id WHERE u.tenant_id=? AND u.id=? AND u.identity_type='EMPLOYEE'",tenant,user);if(rows.size()!=1)throw new IllegalArgumentException();
    long version=((Number)rows.getFirst().get("row_version")).longValue(),security=((Number)rows.getFirst().get("security_version")).longValue();if(version!=nonnegative(e.expectedVersion())||version==Long.MAX_VALUE||security==Long.MAX_VALUE)throw new IllegalArgumentException();
    for(var cap:e.capabilities().entrySet())update(c,"INSERT INTO identity_user_capability(user_id,capability_code,enabled,created_at,updated_at) VALUES(?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),row_version=row_version+1,updated_at=UTC_TIMESTAMP(3)",user,cap.getKey(),cap.getValue()?1:0);
    if(e.approvalReviewDepartmentIds()!=null){for(String department:e.approvalReviewDepartmentIds())if(number(c,"SELECT COUNT(*) FROM identity_department WHERE tenant_id=? AND id=?",tenant,id(department))!=1)throw new IllegalArgumentException();update(c,"UPDATE identity_approval_department_scope SET enabled=0 WHERE tenant_id=? AND user_id=?",tenant,user);for(String department:new TreeSet<>(e.approvalReviewDepartmentIds()))update(c,"INSERT INTO identity_approval_department_scope VALUES(?,?,?,1) ON DUPLICATE KEY UPDATE enabled=1",tenant,user,id(department));}
    if(e.warehouseIds()!=null){update(c,"UPDATE identity_warehouse_scope SET enabled=0 WHERE tenant_id=? AND user_id=?",tenant,user);for(String warehouse:new TreeSet<>(e.warehouseIds()))update(c,"INSERT INTO identity_warehouse_scope VALUES(?,?,?,1) ON DUPLICATE KEY UPDATE enabled=1",tenant,user,id(warehouse));}
    update(c,"UPDATE identity_user SET row_version=row_version+1,security_version=security_version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",user);audit(c,tenant,user,"DOMAIN_SCOPE_CHANGED",security,security+1,plan.operationId());changes++;
   }
   var result=Map.<String,Object>of("operationId",plan.operationId(),"state",execute?"COMMITTED":"DRY_RUN_ROLLED_BACK","changedObjects",changes);
   if(execute){update(c,"INSERT INTO identity_configuration_operation VALUES(?,?,?,UTC_TIMESTAMP(3))",plan.operationId(),digest,Chain.JSON.writeValueAsString(result));committing=true;c.commit();ended=true;}else{c.rollback();ended=true;}return result;
  }catch(Exception failure){if(!ended)try{c.rollback();ended=true;}catch(Exception rollback){failure.addSuppressed(rollback);}if(!ended||committing)discard(c,failure);throw failure;}
 }
 public static void validate(Plan plan){if(plan==null||plan.operationId()==null||!UUID.fromString(plan.operationId()).toString().equals(plan.operationId())||plan.employees()==null||plan.employees().isEmpty()||plan.employees().size()>50)throw new IllegalArgumentException();AuthCrypto.tenantCode(plan.tenantCode());for(var e:plan.employees()){id(e.userId());nonnegative(e.expectedVersion());if(e.capabilities()==null||!DomainPolicy.CAPABILITIES.containsAll(e.capabilities().keySet())||e.capabilities().values().stream().anyMatch(Objects::isNull))throw new IllegalArgumentException();for(var values:Arrays.asList(e.approvalReviewDepartmentIds(),e.warehouseIds()))if(values!=null){if(values.size()>100||new HashSet<>(values).size()!=values.size())throw new IllegalArgumentException();values.forEach(ConfigureDomainStaffMain::id);}}}
 private static long id(String value){long n=nonnegative(value);if(n<1)throw new IllegalArgumentException();return n;}
 private static long nonnegative(String value){if(value==null||!value.matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException();return Long.parseLong(value);}
}
