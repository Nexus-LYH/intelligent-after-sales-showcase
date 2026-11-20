package com.ias.gateway;
import org.junit.jupiter.api.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual production method and actual3307; only selected JDBC failure boundaries are proxied. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StaffRollbackTest {
 final WorkflowTest flow=new WorkflowTest();final ChainIntegrationTest h=flow.h;
 java.net.URLClassLoader loader;Class<?> tool,planType;Method configure;Object previousConfig;Field configField;
 @BeforeAll void setup()throws Exception{
  h.credentials=h.read(h.privateRoot.resolve("mysql-credentials.json"));h.suffix=UUID.randomUUID().toString().replace("-","").substring(0,10);h.codeA="RB_"+h.suffix.toUpperCase();try(var c=h.connection(false,false)){h.update(c,"INSERT INTO tenant(code_normalized,name,status,created_at,updated_at) VALUES(?,? ,1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",h.codeA,"ID06新事务样本");h.tenantA=h.scalar(c,"SELECT id FROM tenant WHERE code_normalized=?",h.codeA);}try(var c=h.connection(true,false)){h.update(c,"INSERT INTO asset_company(tenant_id,code,name,created_at,updated_at) VALUES(?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",h.tenantA,h.codeA,"ID06只读企业引用样本");h.companyA=Map.of("id",Long.toString(h.scalar(c,"SELECT id FROM asset_company WHERE tenant_id=? AND code=?",h.tenantA,h.codeA)));}h.start("asset");loader=flow.files.domainLoader("identity");tool=loader.loadClass("com.ias.identity.bootstrap.ConfigureStaffMain");planType=loader.loadClass("com.ias.identity.bootstrap.ConfigureStaffMain$Plan");configure=tool.getMethod("configure",Connection.class,planType,boolean.class);
  configField=com.ias.trust.Chain.class.getDeclaredField("config");configField.setAccessible(true);previousConfig=configField.get(null);configField.set(null,h.read(h.privateRoot.resolve("chain/identity.json")));long end=System.nanoTime()+30_000_000_000L;boolean ready=false;while(System.nanoTime()<end){try{com.ias.trust.Chain.call("identity-asset","/internal/asset/company-references",Map.of("tenantId",Long.toString(h.tenantA),"companyIds",List.of()),UUID.randomUUID().toString(),System.currentTimeMillis()+10000);ready=true;break;}catch(com.ias.trust.Problem ignored){Thread.sleep(100);}}assertTrue(ready,"Asset trusted reference endpoint not ready");
 }
 @AfterAll void stop()throws Exception{try{if(configField!=null)configField.set(null,previousConfig);if(loader!=null)loader.close();}finally{flow.stop();}}
 record Sample(String operation,String department,long user,Object plan){}
 Sample sample()throws Exception{
  String tag=UUID.randomUUID().toString().replace("-","").substring(0,8),op=UUID.randomUUID().toString(),dept="RB_"+tag.toUpperCase();long user;
  try(var c=h.connection(false,false)){user=h.user(c,h.tenantA,"rb"+tag,"non-loginable-rollback-fixture",false);}
  var employee=new LinkedHashMap<String,Object>();employee.put("account","rb"+tag+"."+h.suffix);employee.put("departmentCode",dept);employee.put("enabled",false);employee.put("capabilities",List.of("ticket.read","ticket.accept","ticket.reject","ticket.assign","ticket.engineer.read","ticket.assignment.accept","ticket.record"));employee.put("companyIds",List.of(h.companyA.get("id")));employee.put("assignableDepartmentCodes",List.of(dept));employee.put("mode","INITIALIZE");
  var document=Map.of("operationId",op,"tenants",List.of(Map.of("tenantCode",h.codeA,"departments",List.of(Map.of("code",dept,"name","回滚故障新样本","enabled",true,"mode","INITIALIZE")),"employees",List.of(employee))));
  return new Sample(op,dept,user,h.json.readValue(h.json.writeValueAsString(document),planType));
 }
 Object invoke(Connection c,Sample s,boolean execute)throws Throwable{try{return configure.invoke(null,c,s.plan(),execute);}catch(InvocationTargetException e){throw e.getCause();}}
 static Object delegate(Method m,Object target,Object[]args)throws Throwable{try{return m.invoke(target,args);}catch(InvocationTargetException e){throw e.getCause();}}
 static class Fault implements InvocationHandler {
  final Connection real;final boolean failOperationInsert;final SQLException original=new SQLException("controlled operation insert failure","45000",9101),rollbackFailure=new SQLException("controlled rollback failure before execution","08007",9102);int rollbackCalls,autoCommitTrue,closeCalls;boolean insertedFailure,closedBeforeOwnerCleanup;
  Fault(Connection real,boolean insert){this.real=real;this.failOperationInsert=insert;}
  Connection connection(){return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class[]{Connection.class},this);}
  public Object invoke(Object proxy,Method method,Object[]args)throws Throwable{
   if(method.getName().equals("rollback")){rollbackCalls++;throw rollbackFailure;}
   if(method.getName().equals("setAutoCommit")&&Boolean.TRUE.equals(args[0]))autoCommitTrue++;
   if(method.getName().equals("close"))closeCalls++;
   Object result=delegate(method,real,args);
   if(failOperationInsert&&method.getName().equals("prepareStatement")&&args[0].toString().startsWith("INSERT INTO identity_configuration_operation")){
    PreparedStatement statement=(PreparedStatement)result;return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class[]{PreparedStatement.class},(p,m,a)->{if(m.getName().equals("executeUpdate")&&!insertedFailure){insertedFailure=true;throw original;}return delegate(m,statement,a);});
   }return result;
  }
 }
 Map<String,Object> snapshot(Sample s)throws Exception{
  try(var c=h.connection(false,false)){
   var out=new LinkedHashMap<String,Object>();out.put("epoch",h.scalar(c,"SELECT security_epoch FROM tenant WHERE id=?",h.tenantA));out.put("tenantVersion",h.scalar(c,"SELECT row_version FROM tenant WHERE id=?",h.tenantA));
   try(var p=c.prepareStatement("SELECT status,department_id,security_version,row_version FROM identity_user WHERE id=?")){p.setLong(1,s.user());try(var row=p.executeQuery()){assertTrue(row.next());out.put("enabled",row.getInt(1));out.put("department",row.getObject(2));out.put("securityVersion",row.getLong(3));out.put("userVersion",row.getLong(4));}}
   out.put("departmentRows",h.scalar(c,"SELECT COUNT(*) FROM identity_department WHERE tenant_id=? AND code=?",h.tenantA,s.department()));out.put("configurationRows",h.scalar(c,"SELECT COUNT(*) FROM identity_employee_configuration WHERE user_id=?",s.user()));out.put("capabilityRows",h.scalar(c,"SELECT COUNT(*) FROM identity_user_capability WHERE user_id=?",s.user()));out.put("enabledCapabilities",h.scalar(c,"SELECT COALESCE(SUM(enabled),0) FROM identity_user_capability WHERE user_id=?",s.user()));out.put("companyScopeRows",h.scalar(c,"SELECT COUNT(*) FROM identity_employee_company_scope WHERE user_id=?",s.user()));out.put("departmentScopeRows",h.scalar(c,"SELECT COUNT(*) FROM identity_employee_department_scope WHERE user_id=?",s.user()));out.put("auditRows",h.scalar(c,"SELECT COUNT(*) FROM identity_security_audit WHERE trace_id=?",s.operation()));out.put("operationRows",h.scalar(c,"SELECT COUNT(*) FROM identity_configuration_operation WHERE operation_id=?",s.operation()));return out;
  }
 }
 void evidence(String name,Sample s,Map<String,Object>before,Map<String,Object>after,Fault fault)throws Exception{var out=new LinkedHashMap<String,Object>();out.put("mode","REAL3307_PRODUCTION_METHOD_CONTROLLED_JDBC_FAILURE");out.put("operationId",s.operation());out.put("userId",s.user());out.put("before",before);out.put("after",after);if(fault!=null){out.put("rollbackCalls",fault.rollbackCalls);out.put("autoCommitTrueCalls",fault.autoCommitTrue);out.put("closeCalls",fault.closeCalls);out.put("physicalConnectionClosed",fault.real.isClosed());out.put("discardedBeforeOwnerCleanup",fault.closedBeforeOwnerCleanup);}Files.writeString(Path.of("target","id06-"+name+".json"),h.json.writeValueAsString(out));}
 @Test void executeFailureAndRollbackFailureDiscardConnectionAndPreserveBothErrors()throws Exception{
  var s=sample();var before=snapshot(s);Fault fault=new Fault(h.connection(false,false),true);Throwable thrown;
  try{thrown=assertThrows(Throwable.class,()->invoke(fault.connection(),s,true));}finally{fault.closedBeforeOwnerCleanup=fault.real.isClosed();if(!fault.closedBeforeOwnerCleanup)fault.real.close();}
  var after=snapshot(s);evidence("execute-rollback-failure",s,before,after,fault);
  assertAll(()->assertEquals(0,fault.autoCommitTrue),()->assertEquals(1,fault.rollbackCalls),()->assertTrue(fault.closeCalls>=1&&fault.closedBeforeOwnerCleanup),()->assertSame(fault.original,thrown),()->assertTrue(Arrays.asList(thrown.getSuppressed()).contains(fault.rollbackFailure)),()->assertEquals(before,after));var errors=tool.getMethod("failureEvidence",Throwable.class).invoke(null,thrown);String safe=h.json.writeValueAsString(errors);assertTrue(safe.contains("45000")&&safe.contains("08007")&&safe.contains("SUPPRESSED"));assertFalse(safe.contains("controlled operation")||safe.contains("controlled rollback"));Files.writeString(Path.of("target","id06-safe-cli-errors.json"),safe);
 }
 @Test void dryRunRollbackFailureNeverRetriesOrEnablesAutoCommit()throws Exception{
  var s=sample();var before=snapshot(s);Fault fault=new Fault(h.connection(false,false),false);Throwable thrown;
  try{thrown=assertThrows(Throwable.class,()->invoke(fault.connection(),s,false));}finally{fault.closedBeforeOwnerCleanup=fault.real.isClosed();if(!fault.closedBeforeOwnerCleanup)fault.real.close();}
  var after=snapshot(s);evidence("dryrun-rollback-failure",s,before,after,fault);assertAll(()->assertSame(fault.rollbackFailure,thrown),()->assertEquals(0,fault.autoCommitTrue),()->assertEquals(1,fault.rollbackCalls),()->assertTrue(fault.closeCalls>=1&&fault.closedBeforeOwnerCleanup),()->assertEquals(before,after));
 }
 @Test void existingOperationReplayRollbackFailureDiscardsWithoutChangingCommittedFacts()throws Exception{
  var s=sample();try(var c=h.connection(false,false)){assertDoesNotThrow(()->invoke(c,s,true));}var before=snapshot(s);Fault fault=new Fault(h.connection(false,false),false);
  try{assertSame(fault.rollbackFailure,assertThrows(Throwable.class,()->invoke(fault.connection(),s,true)));}finally{fault.closedBeforeOwnerCleanup=fault.real.isClosed();if(!fault.closedBeforeOwnerCleanup)fault.real.close();}
  var after=snapshot(s);evidence("replay-rollback-failure",s,before,after,fault);assertAll(()->assertEquals(0,fault.autoCommitTrue),()->assertEquals(1,fault.rollbackCalls),()->assertTrue(fault.closeCalls>=1&&fault.closedBeforeOwnerCleanup),()->assertEquals(before,after));
 }
 @Test void normalExecuteAndOriginalUuidReplayCommitCompleteFactsExactlyOnce()throws Exception{
  var s=sample();var initial=snapshot(s);Object result;try(var c=h.connection(false,false)){result=assertDoesNotThrow(()->invoke(c,s,true));assertTrue(c.getAutoCommit());}var before=snapshot(s);try(var c=h.connection(false,false)){assertEquals(result,assertDoesNotThrow(()->invoke(c,s,true)));assertTrue(c.getAutoCommit());}
  String changedJson=h.json.writeValueAsString(s.plan()).replace("回滚故障新样本","同UUID换参应拒绝");assertNotEquals(h.json.writeValueAsString(s.plan()),changedJson);var changed=new Sample(s.operation(),s.department(),s.user(),h.json.readValue(changedJson,planType));try(var c=h.connection(false,false)){assertInstanceOf(IllegalArgumentException.class,assertThrows(Throwable.class,()->invoke(c,changed,true)));assertTrue(c.getAutoCommit());}
  var after=snapshot(s);evidence("execute-and-idempotency",s,before,after,null);assertEquals(before,after);assertEquals(((Number)initial.get("epoch")).longValue()+1,after.get("epoch"));assertEquals(((Number)initial.get("tenantVersion")).longValue()+1,after.get("tenantVersion"));assertEquals(1L,after.get("operationRows"));assertEquals(14L,after.get("capabilityRows"));assertEquals(1L,after.get("companyScopeRows"));assertEquals(1L,after.get("departmentScopeRows"));assertEquals(2L,after.get("auditRows"));assertEquals(2L,after.get("securityVersion"));assertEquals(1L,after.get("userVersion"));
 }
 @Test void normalDryRunLeavesAllFactsAndVersionsUnchanged()throws Exception{
  var s=sample();var before=snapshot(s);try(var c=h.connection(false,false)){assertDoesNotThrow(()->invoke(c,s,false));assertTrue(c.getAutoCommit());}var after=snapshot(s);evidence("normal-dryrun",s,before,after,null);assertEquals(before,after);
 }
 @Test void actualAuditConstraintFailureRollsBackAllPriorScopeCapabilityAndEpochWrites()throws Exception{
  var s=sample();var before=snapshot(s);String constraint="it_id06_"+s.operation().substring(0,8);
  try(var c=h.connection(false,false)){h.update(c,"ALTER TABLE identity_security_audit ADD CONSTRAINT "+constraint+" CHECK(NOT(trace_id='"+s.operation()+"' AND action='EMPLOYEE_SCOPE_CHANGED'))");}
  try{try(var c=h.connection(false,false)){assertThrows(Throwable.class,()->invoke(c,s,true));assertTrue(c.getAutoCommit());}var after=snapshot(s);evidence("audit-rollback",s,before,after,null);assertEquals(before,after);}
  finally{try(var c=h.connection(false,false)){h.update(c,"ALTER TABLE identity_security_audit DROP CHECK "+constraint);}}
 }
}
