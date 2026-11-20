package com.ias.ai;

import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ias.ai.BudgetAdmission.*;

class JdbcBudgetLedgerTest {
 JdbcDataSource db;JdbcBudgetLedger ledger;AtomicBoolean allowed;AtomicInteger checks;Connection fixture;
 final String budget="tenant-model",period="2026-10-21";
 Bound bound(){return BudgetAdmission.bound(new Price("fictional-model","cfg1","price1","CNY",new BigDecimal("1"),new BigDecimal("2")),100000,100000,1);}
 JdbcBudgetLedger.Command command(UUID op){return new JdbcBudgetLedger.Command(1,10,budget,period,op,bound());}
 @BeforeEach void setup()throws Exception {
  db=new JdbcDataSource();db.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000");
  fixture=db.getConnection();RunScript.execute(fixture,new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V1__budget_ledger.sql")),StandardCharsets.UTF_8));
  sql("INSERT INTO ai_budget(tenant_id,budget_id,period_key,currency,limit_amount) VALUES(1,'tenant-model','2026-10-21','CNY',0.9),(1,'tenant-model','2026-10-22','CNY',0.9),(2,'tenant-model','2026-10-21','CNY',0.9)");
  allowed=new AtomicBoolean(true);checks=new AtomicInteger();ledger=new JdbcBudgetLedger(db,(t,a,b,p,action)->{checks.incrementAndGet();return allowed.get();});
 }
 @AfterEach void cleanup()throws Exception{if(fixture!=null){try(var q=fixture.createStatement()){q.execute("SHUTDOWN");}finally{fixture.close();}}}
 void sql(String text)throws Exception{try(var q=fixture.createStatement()){q.execute(text);}}
 long count(String table)throws Exception{try(var c=db.getConnection();var q=c.createStatement();var r=q.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}
 BigDecimal balanceHeld()throws Exception{return ledger.balance(1,10,budget,period).held();}
 @Test void durableOriginalKeySurvivesNewStoreAndEquivalentPriceScale()throws Exception {
  UUID op=UUID.randomUUID();ledger.reserve(command(op));var reloaded=new JdbcBudgetLedger(db,(t,a,b,p,x)->true);
  var saved=reloaded.read(1,10,budget,period,op);var replay=reloaded.reserve(new JdbcBudgetLedger.Command(1,10,budget,period,op,saved.bound()));
  assertEquals(State.RESERVED,replay.state());assertEquals(0,new BigDecimal("0.3").compareTo(balanceHeld()));assertEquals(1,count("ai_reservation"));assertEquals(1,count("ai_budget_audit"));assertTrue(checks.get()>=2);
 }
 @Test void concurrentHundredRequestsCannotOverAdmit()throws Exception {
  AtomicInteger admitted=new AtomicInteger(),exhausted=new AtomicInteger();try(var pool=Executors.newFixedThreadPool(12)){List<Callable<Void>> calls=new ArrayList<>();for(int i=0;i<100;i++)calls.add(()->{try{ledger.reserve(command(UUID.randomUUID()));admitted.incrementAndGet();}catch(IllegalArgumentException e){assertEquals("BUDGET_EXHAUSTED",e.getMessage());exhausted.incrementAndGet();}return null;});for(var f:pool.invokeAll(calls))f.get();}
  assertEquals(3,admitted.get());assertEquals(97,exhausted.get());assertEquals(0,new BigDecimal("0.9").compareTo(balanceHeld()));assertEquals(3,count("ai_budget_audit"));
 }
 @Test void concurrentSameKeyProducesOneReservationAndOneAudit()throws Exception {
  UUID op=UUID.randomUUID();try(var pool=Executors.newFixedThreadPool(8)){List<Callable<JdbcBudgetLedger.Receipt>> calls=new ArrayList<>();for(int i=0;i<8;i++)calls.add(()->ledger.reserve(command(op)));for(var f:pool.invokeAll(calls))assertEquals(op,f.get().operation());}
  assertEquals(1,count("ai_reservation"));assertEquals(1,count("ai_budget_audit"));assertEquals(0,new BigDecimal("0.3").compareTo(balanceHeld()));
 }
 @Test void unknownKeepsFullHoldAndNoImplicitCancellationRelease()throws Exception {
  UUID op=UUID.randomUUID(),unknown=UUID.randomUUID();ledger.reserve(command(op));ledger.markUnknown(1,10,budget,period,op,unknown);ledger.markUnknown(1,10,budget,period,op,unknown);
  assertEquals(State.UNKNOWN,ledger.read(1,10,budget,period,op).state());assertEquals(0,new BigDecimal("0.3").compareTo(balanceHeld()));assertEquals(2,count("ai_budget_audit"));
  assertThrows(IllegalArgumentException.class,()->ledger.releaseVerifiedNoCharge(1,10,budget,period,op,UUID.randomUUID()," "));
 }
 @Test void settleExactActualOnceAndSameCommandConflictsOnChangedCostOrEvidence()throws Exception {
  UUID op=UUID.randomUUID(),settle=UUID.randomUUID();ledger.reserve(command(op));ledger.settle(1,10,budget,period,op,settle,new BigDecimal("0.2"),"usage-ref-1");ledger.settle(1,10,budget,period,op,settle,new BigDecimal("0.20"),"usage-ref-1");
  assertEquals(0,BigDecimal.ZERO.compareTo(balanceHeld()));assertEquals(0,new BigDecimal("0.2").compareTo(ledger.balance(1,10,budget,period).spent()));assertEquals(2,count("ai_budget_audit"));
  assertThrows(IllegalArgumentException.class,()->ledger.settle(1,10,budget,period,op,settle,new BigDecimal("0.3"),"usage-ref-1"));
  assertThrows(IllegalArgumentException.class,()->ledger.settle(1,10,budget,period,op,settle,new BigDecimal("0.2"),"other-proof"));
 }
 @Test void actualOverrunIsNotHiddenAndStopsFutureAdmission()throws Exception {
  UUID op=UUID.randomUUID();ledger.reserve(command(op));ledger.settle(1,10,budget,period,op,UUID.randomUUID(),new BigDecimal("1.5"),"confirmed-provider-usage");
  assertEquals(0,new BigDecimal("1.5").compareTo(ledger.balance(1,10,budget,period).spent()));assertThrows(IllegalArgumentException.class,()->ledger.reserve(command(UUID.randomUUID())));
 }
 @Test void verifiedReleaseOnceDoesNotInventPaidSettlement()throws Exception {
  UUID op=UUID.randomUUID(),release=UUID.randomUUID();ledger.reserve(command(op));ledger.markUnknown(1,10,budget,period,op,UUID.randomUUID());ledger.releaseVerifiedNoCharge(1,10,budget,period,op,release,"provider-no-charge-1");ledger.releaseVerifiedNoCharge(1,10,budget,period,op,release,"provider-no-charge-1");
  assertEquals(State.RELEASED,ledger.read(1,10,budget,period,op).state());assertEquals(0,BigDecimal.ZERO.compareTo(balanceHeld()));assertEquals(0,BigDecimal.ZERO.compareTo(ledger.balance(1,10,budget,period).spent()));
  assertThrows(IllegalArgumentException.class,()->ledger.settle(1,10,budget,period,op,UUID.randomUUID(),new BigDecimal("0.1"),"late-charge-requires-special-reconciliation"));
 }
 @Test void tenantActorPeriodAndParametersStayBoundToOriginalKey()throws Exception {
  UUID op=UUID.randomUUID();ledger.reserve(command(op));assertThrows(IllegalArgumentException.class,()->ledger.reserve(new JdbcBudgetLedger.Command(1,11,budget,period,op,bound())));
  assertThrows(IllegalArgumentException.class,()->ledger.reserve(new JdbcBudgetLedger.Command(1,10,budget,"2026-10-22",op,bound())));
  assertThrows(IllegalArgumentException.class,()->ledger.read(1,11,budget,period,op));assertThrows(IllegalArgumentException.class,()->ledger.read(2,10,budget,period,op));
  assertEquals(State.RESERVED,ledger.reserve(new JdbcBudgetLedger.Command(2,10,budget,period,op,bound())).state());assertEquals(2,count("ai_reservation"));
 }
 @Test void revokedAccessRejectsOriginalKeyAndReconciliationBeforeAnyWrite()throws Exception {
  UUID op=UUID.randomUUID();ledger.reserve(command(op));allowed.set(false);assertThrows(IllegalArgumentException.class,()->ledger.reserve(command(op)));assertThrows(IllegalArgumentException.class,()->ledger.settle(1,10,budget,period,op,UUID.randomUUID(),new BigDecimal("0.1"),"usage-ref"));assertEquals(1,count("ai_budget_audit"));
 }
 @Test void authorizationIsRecheckedAfterBudgetLockEvenForReplay()throws Exception {
  UUID op=UUID.randomUUID();ledger.reserve(command(op));AtomicInteger calls=new AtomicInteger();var racing=new JdbcBudgetLedger(db,(t,a,b,p,x)->calls.incrementAndGet()==1);
  assertThrows(IllegalArgumentException.class,()->racing.reserve(command(op)));assertEquals(2,calls.get());assertEquals(1,count("ai_budget_audit"));
 }
 @Test void admissionAuditFailureRollsBackBalanceAndReservationOriginalKeyRecovers()throws Exception {
  sql("ALTER TABLE ai_budget_audit ADD CONSTRAINT fail_admission CHECK(action_name<>'RESERVE')");UUID op=UUID.randomUUID();assertThrows(SQLException.class,()->ledger.reserve(command(op)));assertEquals(0,count("ai_reservation"));assertEquals(0,count("ai_budget_audit"));assertEquals(0,BigDecimal.ZERO.compareTo(balanceHeld()));sql("ALTER TABLE ai_budget_audit DROP CONSTRAINT fail_admission");assertEquals(State.RESERVED,ledger.reserve(command(op)).state());
 }
 @Test void settlementAuditFailureRollsBackHeldSpentAndState()throws Exception {
  UUID op=UUID.randomUUID(),key=UUID.randomUUID();ledger.reserve(command(op));sql("ALTER TABLE ai_budget_audit ADD CONSTRAINT fail_settle CHECK(action_name<>'SETTLE')");assertThrows(SQLException.class,()->ledger.settle(1,10,budget,period,op,key,new BigDecimal("0.2"),"usage-1"));assertEquals(State.RESERVED,ledger.read(1,10,budget,period,op).state());assertEquals(0,new BigDecimal("0.3").compareTo(balanceHeld()));assertEquals(0,BigDecimal.ZERO.compareTo(ledger.balance(1,10,budget,period).spent()));sql("ALTER TABLE ai_budget_audit DROP CONSTRAINT fail_settle");assertEquals(State.SETTLED,ledger.settle(1,10,budget,period,op,key,new BigDecimal("0.2"),"usage-1").state());
 }
 @Test void finiteBoundCurrencyAndStoragePrecisionAreRequired()throws Exception {
  var eur=BudgetAdmission.bound(new Price("model","cfg","v1","EUR",BigDecimal.ONE,BigDecimal.ONE),1,1,1);assertThrows(IllegalArgumentException.class,()->ledger.reserve(new JdbcBudgetLedger.Command(1,10,budget,period,UUID.randomUUID(),eur)));
  assertThrows(IllegalArgumentException.class,()->new JdbcBudgetLedger.Command(1,10,budget,period,UUID.randomUUID(),BudgetAdmission.bound(new Price("huge","cfg","v1","CNY",new BigDecimal("1e29"),BigDecimal.ONE),Long.MAX_VALUE,1,1)));assertEquals(0,count("ai_reservation"));
 }
}
