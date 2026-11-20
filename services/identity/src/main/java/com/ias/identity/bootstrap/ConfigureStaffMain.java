package com.ias.identity.bootstrap;

import com.ias.identity.auth.AuthCrypto;
import com.ias.trust.*;
import java.nio.file.*;
import java.security.*;
import java.sql.*;
import java.util.*;

/** Explicit, bounded offline plan; never executed by normal service startup. */
public final class ConfigureStaffMain {
 public record Department(String code,String name,boolean enabled,String mode,Long expectedVersion){}
 public record Employee(String account,String departmentCode,boolean enabled,List<String> capabilities,List<String> companyIds,List<String> assignableDepartmentCodes,String mode,Long expectedVersion,@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) List<String> reopenReviewDepartmentCodes){}
 public record Tenant(String tenantCode,List<Department> departments,List<Employee> employees){}
 public record Plan(String operationId,List<Tenant> tenants){}
 static final Set<String> CAPS=Set.of("ticket.read","ticket.accept","ticket.reject","ticket.assign","ticket.engineer.read","ticket.assignment.accept","ticket.record","ticket.solution.submit","ticket.close","ticket.supplement.request","ticket.cancel.review","ticket.transfer.request","ticket.reopen.review","ticket.respond");
 public static void main(String[]args){
  String operationId=null;try{
   if(args.length!=1||!Set.of("--dry-run","--execute").contains(args[0]))throw new IllegalArgumentException();Path input=Path.of(System.getenv("IAS_STAFF_PLAN"));if(Files.size(input)>65536)throw new IllegalArgumentException();var plan=Chain.JSON.readValue(Files.readString(input),Plan.class);if(plan.operationId()!=null&&plan.operationId().matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}"))operationId=plan.operationId();
   try(var c=DriverManager.getConnection("jdbc:mysql://127.0.0.1:3307/ias_identity_test?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true",System.getenv("IAS_MIGRATION_USER"),System.getenv("IAS_MIGRATION_PASSWORD"))){
    try(var s=c.createStatement();var r=s.executeQuery("SELECT @@port,DATABASE(),@@datadir")){r.next();if(r.getInt(1)!=3307||!r.getString(2).equals("ias_identity_test")||!r.getString(3).replace('\\','/').equalsIgnoreCase("C:/ias-local/Develop/IAS-MySQL84/data/"))throw new IllegalStateException();}
    System.out.println(Chain.JSON.writeValueAsString(configure(c,plan,args[0].equals("--execute"))));
   }
  }catch(Exception e){var evidence=new LinkedHashMap<String,Object>();evidence.put("code","STAFF_CONFIGURATION_NOT_CONFIRMED");evidence.put("operationId",operationId);evidence.put("nextAction","READ_ONLY_CHECK_ORIGINAL_OPERATION_AND_CURRENT_VERSIONS");evidence.put("errors",failureEvidence(e));System.err.println(Chain.JSON.writeValueAsString(evidence));System.exit(1);}
 }
 public static Map<String,Object> configure(Connection c,Plan plan,boolean execute)throws Exception{
  if(plan==null||plan.tenants()==null||plan.tenants().isEmpty()||plan.tenants().size()>10||!UUID.fromString(plan.operationId()).toString().equals(plan.operationId()))throw new IllegalArgumentException();
  String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Chain.JSON.writeValueAsBytes(plan)));boolean transactionClosed=false,rollbackAttempted=false;Exception failure=null;
  try{
   c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
   // Tenant locks serialize all security changes; users then departments always sorted by numeric ID.
   var tenants=new TreeMap<Long,Tenant>();for(var spec:plan.tenants()){long tenant=number(c,"SELECT id FROM tenant WHERE code_normalized=?",AuthCrypto.tenantCode(spec.tenantCode()));if(tenants.put(tenant,spec)!=null)throw new IllegalArgumentException();}
   for(long tenant:tenants.keySet())query(c,"SELECT id FROM tenant WHERE id=? FOR UPDATE",tenant);
   var previous=query(c,"SELECT * FROM identity_configuration_operation WHERE operation_id=? FOR UPDATE",plan.operationId());if(!previous.isEmpty()){if(!digest.equals(previous.getFirst().get("request_digest")))throw new IllegalArgumentException();rollbackAttempted=true;c.rollback();transactionClosed=true;return Chain.JSON.readValue(previous.getFirst().get("result_json").toString(),Map.class);}
   int changes=0;var output=new ArrayList<Map<String,Object>>();
   for(var pair:tenants.entrySet()){
    long tenant=pair.getKey();var spec=pair.getValue();if(spec.departments()==null||spec.employees()==null||spec.departments().size()>20||spec.employees().size()>50)throw new IllegalArgumentException();
    var employees=new TreeMap<Long,Employee>();for(var e:spec.employees()){long user=number(c,"SELECT id FROM identity_user WHERE tenant_id=? AND account_normalized=? AND identity_type='EMPLOYEE'",tenant,AuthCrypto.account(e.account()));if(employees.put(user,e)!=null)throw new IllegalArgumentException();}
    for(long user:employees.keySet())query(c,"SELECT id FROM identity_user WHERE id=? FOR UPDATE",user);
    query(c,"SELECT id FROM identity_department WHERE tenant_id=? ORDER BY id FOR UPDATE",tenant);
    var codes=new HashSet<String>();boolean departmentChanged=false;
    for(var d:spec.departments()){
     if(d.code()==null||!d.code().matches("[A-Z0-9_-]{1,40}")||!codes.add(d.code())||d.name()==null||d.name().isBlank()||d.name().length()>100||!Set.of("INITIALIZE","CHANGE").contains(d.mode()))throw new IllegalArgumentException();
     var rows=query(c,"SELECT * FROM identity_department WHERE tenant_id=? AND code=?",tenant,d.code());
     if(d.mode().equals("INITIALIZE")){if(!rows.isEmpty())continue;update(c,"INSERT INTO identity_department(tenant_id,code,name,enabled,created_at,updated_at) VALUES(?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",tenant,d.code(),d.name(),d.enabled()?1:0);}
     else{if(rows.isEmpty()||d.expectedVersion()==null||((Number)rows.getFirst().get("row_version")).longValue()!=d.expectedVersion()||d.expectedVersion()==Long.MAX_VALUE)throw new IllegalArgumentException();update(c,"UPDATE identity_department SET name=?,enabled=?,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",d.name(),d.enabled()?1:0,rows.getFirst().get("id"));}
     departmentChanged=true;changes++;output.add(Map.of("type","DEPARTMENT","code",d.code(),"state","CHANGED"));
    }
    if(departmentChanged){long epoch=number(c,"SELECT security_epoch FROM tenant WHERE id=?",tenant);if(epoch==Long.MAX_VALUE)throw new IllegalStateException();update(c,"UPDATE tenant SET security_epoch=security_epoch+1,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",tenant);audit(c,tenant,tenant,"DEPARTMENT_SCOPE_CHANGED",epoch,epoch+1,plan.operationId());}
    for(var pairUser:employees.entrySet()){
     long user=pairUser.getKey();var e=pairUser.getValue();if(e.capabilities()==null||!CAPS.containsAll(e.capabilities())||e.companyIds()==null||e.companyIds().size()>100||e.assignableDepartmentCodes()==null||e.assignableDepartmentCodes().size()>20||!Set.of("INITIALIZE","CHANGE").contains(e.mode()))throw new IllegalArgumentException();
     boolean exists=!query(c,"SELECT user_id FROM identity_employee_configuration WHERE user_id=?",user).isEmpty();if(e.mode().equals("INITIALIZE")&&exists){output.add(Map.of("type","EMPLOYEE","userId",Long.toString(user),"state","ALREADY_CONFIGURED_NO_OVERWRITE"));continue;}
     long version=number(c,"SELECT row_version FROM identity_user WHERE id=?",user),security=number(c,"SELECT security_version FROM identity_user WHERE id=?",user);if(security==Long.MAX_VALUE||version==Long.MAX_VALUE||e.mode().equals("CHANGE")&&(!exists||e.expectedVersion()==null||version!=e.expectedVersion()))throw new IllegalArgumentException();
     Long department=e.departmentCode()==null?null:number(c,"SELECT id FROM identity_department WHERE tenant_id=? AND code=?",tenant,e.departmentCode());
     var departments=new TreeSet<Long>();for(String code:e.assignableDepartmentCodes())departments.add(number(c,"SELECT id FROM identity_department WHERE tenant_id=? AND code=?",tenant,code));var companyIds=new TreeSet<Long>();for(String company:e.companyIds()){long id=Long.parseLong(company);if(id<1)throw new IllegalArgumentException();companyIds.add(id);}
     if(e.reopenReviewDepartmentCodes()!=null&&e.reopenReviewDepartmentCodes().size()>20)throw new IllegalArgumentException();var reviewDepartments=new TreeSet<Long>();if(e.reopenReviewDepartmentCodes()!=null)for(String code:e.reopenReviewDepartmentCodes())reviewDepartments.add(number(c,"SELECT id FROM identity_department WHERE tenant_id=? AND code=?",tenant,code));
     long deadline=System.currentTimeMillis()+10000;var proof=Chain.call("identity-asset","/internal/asset/company-references",Map.of("tenantId",Long.toString(tenant),"companyIds",companyIds.stream().map(Object::toString).toList()),plan.operationId(),deadline);Chain.fresh(proof,deadline);if(!Boolean.TRUE.equals(proof.get("valid")))throw new IllegalStateException();
     if(!exists)update(c,"INSERT INTO identity_employee_configuration(user_id,tenant_id,created_at,updated_at) VALUES(?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",user,tenant);
     update(c,"UPDATE identity_employee_company_scope SET enabled=0 WHERE user_id=?",user);update(c,"UPDATE identity_employee_department_scope SET enabled=0 WHERE user_id=?",user);
     for(long company:companyIds)update(c,"INSERT INTO identity_employee_company_scope(tenant_id,user_id,company_id,enabled) VALUES(?,?,?,1) ON DUPLICATE KEY UPDATE enabled=1",tenant,user,company);
     for(long dept:departments)update(c,"INSERT INTO identity_employee_department_scope(tenant_id,user_id,department_id,enabled) VALUES(?,?,?,1) ON DUPLICATE KEY UPDATE enabled=1",tenant,user,dept);
     if(e.reopenReviewDepartmentCodes()!=null){update(c,"UPDATE identity_reopen_department_scope SET enabled=0 WHERE user_id=?",user);for(long dept:reviewDepartments)update(c,"INSERT INTO identity_reopen_department_scope(tenant_id,user_id,department_id,enabled) VALUES(?,?,?,1) ON DUPLICATE KEY UPDATE enabled=1",tenant,user,dept);}
     for(String cap:CAPS)update(c,"INSERT INTO identity_user_capability(user_id,capability_code,enabled,created_at,updated_at) VALUES(?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),row_version=row_version+1,updated_at=UTC_TIMESTAMP(3)",user,cap,e.capabilities().contains(cap)?1:0);
     update(c,"UPDATE identity_user SET department_id=?,status=?,security_version=security_version+1,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",department,e.enabled()?1:0,user);update(c,"UPDATE identity_employee_configuration SET updated_at=UTC_TIMESTAMP(3) WHERE user_id=?",user);audit(c,tenant,user,"EMPLOYEE_SCOPE_CHANGED",security,security+1,plan.operationId());changes++;output.add(Map.of("type","EMPLOYEE","userId",Long.toString(user),"state","CHANGED","rowVersion",Long.toString(version+1)));
    }
   }
   var result=Map.<String,Object>of("operationId",plan.operationId(),"state",execute?"COMMITTED":"DRY_RUN_ROLLED_BACK","changedObjects",changes,"objects",output);
   if(execute){update(c,"INSERT INTO identity_configuration_operation(operation_id,request_digest,result_json,created_at) VALUES(?,?,?,UTC_TIMESTAMP(3))",plan.operationId(),digest,Chain.JSON.writeValueAsString(result));c.commit();transactionClosed=true;}else{rollbackAttempted=true;c.rollback();transactionClosed=true;}return result;
  }catch(Exception e){
   failure=e;
   if(!transactionClosed&&!rollbackAttempted){rollbackAttempted=true;try{c.rollback();transactionClosed=true;}catch(Exception rollbackFailure){if(e!=rollbackFailure)e.addSuppressed(rollbackFailure);}}
   if(!transactionClosed)discard(c,e);
   throw e;
  }finally{
   // false -> true may COMMIT. Never reset a connection whose transaction did not explicitly end.
   if(transactionClosed)try{c.setAutoCommit(true);}catch(Exception resetFailure){discard(c,resetFailure);if(failure!=null)failure.addSuppressed(resetFailure);else throw resetFailure;}
  }
 }
 static void discard(Connection c,Exception failure){try{c.close();}catch(Exception closeFailure){if(failure!=closeFailure)failure.addSuppressed(closeFailure);try{c.abort(Runnable::run);}catch(Exception abortFailure){if(failure!=abortFailure)failure.addSuppressed(abortFailure);}}}
 /** Redacted CLI evidence; never serializes an exception, SQL text, message or stack. */
 public static List<Map<String,Object>> failureEvidence(Throwable failure){var out=new ArrayList<Map<String,Object>>();var seen=Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());failureEvidence(failure,"ORIGINAL",out,seen);return out;}
 static void failureEvidence(Throwable e,String relation,List<Map<String,Object>>out,Set<Throwable>seen){if(e==null||out.size()>=16||!seen.add(e))return;var row=new LinkedHashMap<String,Object>();row.put("relation",relation);row.put("type",e.getClass().getSimpleName());if(e instanceof SQLException sql){String state=sql.getSQLState();row.put("sqlState",state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNAVAILABLE");row.put("vendorCode",sql.getErrorCode());}out.add(row);for(Throwable suppressed:e.getSuppressed())failureEvidence(suppressed,"SUPPRESSED",out,seen);failureEvidence(e.getCause(),"CAUSE",out,seen);}
 static List<Map<String,Object>> query(Connection c,String sql,Object...args)throws SQLException{try(var p=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);try(var r=p.executeQuery()){var rows=new ArrayList<Map<String,Object>>();while(r.next()){var row=new HashMap<String,Object>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)row.put(r.getMetaData().getColumnLabel(i),r.getObject(i));rows.add(row);}return rows;}}}
 static long number(Connection c,String sql,Object...args)throws SQLException{var rows=query(c,sql,args);if(rows.size()!=1)throw new IllegalArgumentException();return ((Number)rows.getFirst().values().iterator().next()).longValue();}
 static void update(Connection c,String sql,Object...args)throws SQLException{try(var p=c.prepareStatement(sql)){for(int i=0;i<args.length;i++)p.setObject(i+1,args[i]);p.executeUpdate();}}
 static void audit(Connection c,long tenant,long target,String action,Long before,Long after,String trace)throws SQLException{update(c,"INSERT INTO identity_security_audit(occurred_at,action,actor_type,target_type,target_id,tenant_id,result,reason_code,user_security_version_before,user_security_version_after,tenant_security_epoch_before,tenant_security_epoch_after,trace_id) VALUES(UTC_TIMESTAMP(3),?,'SYSTEM',?,?,?,'SUCCESS','EXPLICIT_STAFF_PLAN',?,?,?,?,?)",action,action.startsWith("DEPARTMENT")?"TENANT":"USER",target,tenant,action.startsWith("DEPARTMENT")?null:before,action.startsWith("DEPARTMENT")?null:after,action.startsWith("DEPARTMENT")?before:null,action.startsWith("DEPARTMENT")?after:null,trace);}
}
