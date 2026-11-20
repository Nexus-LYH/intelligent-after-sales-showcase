package com.ias.finance;

import com.ias.trust.Problem;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.ias.finance.MoneyAuthorityFacts.*;
import static com.ias.finance.JdbcMoneyLedger.*;

/** Explicit server-fact fixtures and H2 transactions, not production owner authentication. */
class MoneyAuthorityTransactionTest {
 JdbcFeeGroupsTest f;JdbcMoneyLedger ledger;
 final Clock clock=Clock.fixed(Instant.parse("2026-10-24T02:20:00Z"),ZoneOffset.UTC);
 final AtomicReference<String> mode=new AtomicReference<>("VALID");
 UUID line,bill,operation,quote,sourceOperation,confirmation,supervisorDecision,financeDecision,executionReceipt,factReceipt,ticket,round;Assess command;
 @BeforeEach void setup()throws Exception{
  f=new JdbcFeeGroupsTest();f.setup();line=UUID.randomUUID();bill=UUID.randomUUID();operation=UUID.randomUUID();
  quote=UUID.randomUUID();sourceOperation=UUID.randomUUID();confirmation=UUID.randomUUID();supervisorDecision=UUID.randomUUID();financeDecision=UUID.randomUUID();executionReceipt=UUID.randomUUID();factReceipt=UUID.randomUUID();ticket=UUID.randomUUID();round=UUID.randomUUID();
  command=new Assess(0,0,bill,new MoneyLedger.Approval(UUID.randomUUID(),"a".repeat(64),Map.of(line,1000L)));
  ledger=new JdbcMoneyLedger(f.source,new Authority(){
   public void current(JdbcFeeGroups.Actor a,FeeGroup.Identity o,String action){f.authority.current(a,o,action);}
   public void verify(JdbcFeeGroups.Actor a,FeeGroup.Identity o,UUID op,Command c){MoneyAuthorityFacts.verify(MoneyAuthorityTransactionTest.this::facts,a,o,op,c,clock);}
  });
 }
 Bundle facts(Binding binding,Command c){
  if(mode.get().equals("UNAVAILABLE"))return MoneyAuthorityFacts.unavailable().obtain(binding,c);
  var rule=new FeeGroup.PublishedRule("PUBLISHED_TEST_V1",200000);
  var submit=new FeeGroup.Submit(sourceOperation,quote,0,Set.of(),List.of(new FeeGroup.PlanLine(line,ticket,round,1000,false)),rule);
  var basis=new FeeGroup.Basis(f.owner,quote,1,1000,0,0,1000,rule,true,new FeeGroup.FinanceTrigger(quote,rule.version(),1));
  var version=new JdbcFeeGroups.QuoteVersion(submit,new JdbcFeeGroups.QuoteDetails(true,"explicit fixture only",Map.of(line,"LABOR")),basis);
  Instant checked=mode.get().equals("STALE")?clock.instant().minusSeconds(3):clock.instant();
  var header=new Header(binding,factReceipt,checked);
  var approval=((Assess)c).approval();
  var decisions=mode.get().equals("MISSING_FINANCE")?Map.of("SUPERVISOR",supervisorDecision):Map.of("SUPERVISOR",supervisorDecision,"FINANCE",financeDecision);
  return new Bundle(List.of(new Assessment(header,version,confirmation,approval.completionId(),quote,1,approval.coverageDigest(),approval.executedAuthorizedLines(),Map.of(line,executionReceipt),decisions,"COMPLETED")));
 }
 Receipt apply()throws Exception{return ledger.apply(f.actor,f.owner,operation,command);}
 void untouched()throws Exception{
  assertEquals(0,f.count("finance_money_account"));assertEquals(0,f.count("finance_money_entry"));assertEquals(0,f.count("finance_money_reference"));
  assertEquals(1,f.count("finance_operation"));assertEquals(1,f.count("finance_audit"));assertEquals(1,f.count("finance_outbox"));
  assertEquals(0,ledger.read(f.actor,f.owner).version());assertEquals(0,f.store.read(f.actor,f.owner).version());
 }
 void exactlyOnce()throws Exception{
  assertEquals(1,f.count("finance_money_account"));assertEquals(1,f.count("finance_money_entry"));
  assertEquals(2,f.count("finance_operation"));assertEquals(2,f.count("finance_audit"));assertEquals(2,f.count("finance_outbox"));
  assertEquals(1,ledger.read(f.actor,f.owner).version());assertEquals(1000,ledger.read(f.actor,f.owner).receivable());
 }
 @Test void unavailableStaleAndMissingPermanentDecisionWriteNothingThenOriginalKeyRecovers()throws Exception{
  for(String failure:List.of("UNAVAILABLE","STALE","MISSING_FINANCE")){
   mode.set(failure);assertEquals(503,assertThrows(Problem.class,this::apply).status);untouched();
  }
  mode.set("VALID");var receipt=apply();assertEquals(receipt,apply());assertEquals(receipt,ledger.operation(f.actor,f.owner,operation));exactlyOnce();
 }
 @Test void completedOriginalKeyStillRequiresCurrentAuthorizationAndFreshOwnerFacts()throws Exception{
  var receipt=apply();mode.set("STALE");assertEquals(503,assertThrows(Problem.class,this::apply).status);exactlyOnce();
  mode.set("VALID");f.allowed.set(false);assertThrows(SecurityException.class,this::apply);
  f.allowed.set(true);assertEquals(receipt,apply());exactlyOnce();
 }
 @Test void auditFailureRollsBackVerifiedAssessmentAndSameUuidRecovers()throws Exception{
  try(var db=f.source.getConnection();var q=db.createStatement()){
   q.execute("ALTER TABLE finance_audit ADD CONSTRAINT qa_authority_audit CHECK(action_name <> 'RECEIVABLE_ASSESSED')");
  }
  assertThrows(java.sql.SQLException.class,this::apply);untouched();
  try(var db=f.source.getConnection();var q=db.createStatement()){q.execute("ALTER TABLE finance_audit DROP CONSTRAINT qa_authority_audit");}
  var receipt=apply();assertEquals(receipt,apply());exactlyOnce();
 }
}
