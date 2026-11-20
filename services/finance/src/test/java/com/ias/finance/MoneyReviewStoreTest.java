package com.ias.finance;

import org.h2.tools.RunScript;
import org.junit.jupiter.api.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MoneyReviewStoreTest {
 JdbcFeeGroupsTest f;MoneyReviewStore store;UUID bill,line;MoneyReviewStore.Proposal proposal;
 @BeforeEach void setup()throws Exception{
  f=new JdbcFeeGroupsTest();f.setup();
  bill=UUID.randomUUID();line=UUID.randomUUID();var ledger=new JdbcMoneyLedger(f.source,new JdbcMoneyLedger.Authority(){public void current(JdbcFeeGroups.Actor a,FeeGroup.Identity o,String action){}public void verify(JdbcFeeGroups.Actor a,FeeGroup.Identity o,UUID op,JdbcMoneyLedger.Command c){}});
  ledger.apply(f.actor,f.owner,UUID.randomUUID(),new JdbcMoneyLedger.Assess(0,0,bill,new MoneyLedger.Approval(UUID.randomUUID(),"a".repeat(64),Map.of(line,1000L))));
  proposal=new MoneyReviewStore.Proposal(UUID.randomUUID(),"REDUCTION",1,0,line,bill,line,500,"a".repeat(64),null,null,"customer agreed correction");store=new MoneyReviewStore(f.source,(a,o,e,p)->{});
 }
 @Test void reviewRequiresCustomerThenDifferentFinancialActorAndIsIdempotent()throws Exception{
  UUID op=UUID.randomUUID();store.submit(f.actor,f.owner,op,proposal);store.submit(f.actor,f.owner,op,proposal);
  assertThrows(IllegalStateException.class,()->store.decide(new JdbcFeeGroups.Actor(1,11),f.owner,UUID.randomUUID(),proposal.reviewId(),"FINANCE_APPROVED"));
  store.decide(new JdbcFeeGroups.Actor(1,20),f.owner,UUID.randomUUID(),proposal.reviewId(),"CUSTOMER_CONFIRMED");
  assertThrows(com.ias.trust.Problem.class,()->store.decide(f.actor,f.owner,UUID.randomUUID(),proposal.reviewId(),"FINANCE_APPROVED"));
  var approve=UUID.randomUUID();store.decide(new JdbcFeeGroups.Actor(1,11),f.owner,approve,proposal.reviewId(),"FINANCE_APPROVED");store.decide(new JdbcFeeGroups.Actor(1,11),f.owner,approve,proposal.reviewId(),"FINANCE_APPROVED");
  assertEquals(1,f.count("finance_money_review"));assertEquals(3,f.count("finance_money_review_event"));
 }
 @Test void changedOperationAndUnrelatedCoverageCannotCreateReview()throws Exception{
  UUID op=UUID.randomUUID();store.submit(f.actor,f.owner,op,proposal);
  var changed=new MoneyReviewStore.Proposal(proposal.reviewId(),"REDUCTION",1,0,line,bill,line,400,"a".repeat(64),null,null,"customer agreed correction");assertThrows(IllegalStateException.class,()->store.submit(f.actor,f.owner,op,changed));
  var forged=new MoneyReviewStore.Proposal(UUID.randomUUID(),"REDUCTION",1,0,line,bill,line,400,"b".repeat(64),null,null,"wrong basis");assertThrows(IllegalStateException.class,()->store.submit(f.actor,f.owner,UUID.randomUUID(),forged));
  assertEquals(1,f.count("finance_money_review"));assertEquals(1,f.count("finance_money_review_event"));
 }
 @Test void auditFailureRollsBackReviewAndOriginalKeyCanRecover()throws Exception{
  UUID op=UUID.randomUUID();try(var db=f.source.getConnection();var q=db.createStatement()){q.execute("ALTER TABLE finance_money_review_event ADD CONSTRAINT qa_review_failure CHECK(event_kind <> 'SUBMITTED')");}
  assertThrows(java.sql.SQLException.class,()->store.submit(f.actor,f.owner,op,proposal));assertEquals(0,f.count("finance_money_review"));
  try(var db=f.source.getConnection();var q=db.createStatement()){q.execute("ALTER TABLE finance_money_review_event DROP CONSTRAINT qa_review_failure");}
  store.submit(f.actor,f.owner,op,proposal);assertEquals(1,f.count("finance_money_review_event"));
 }
 @Test void accountChangesPreventStaleApproval()throws Exception{
  store.submit(f.actor,f.owner,UUID.randomUUID(),proposal);store.decide(new JdbcFeeGroups.Actor(1,20),f.owner,UUID.randomUUID(),proposal.reviewId(),"CUSTOMER_CONFIRMED");
  try(var db=f.source.getConnection();var q=db.createStatement()){q.execute("UPDATE finance_money_account SET row_version=2 WHERE tenant_id=1");}
  // The persisted state is authoritative and is cross-checked against the versioned projection.
  assertThrows(IllegalStateException.class,()->store.decide(new JdbcFeeGroups.Actor(1,11),f.owner,UUID.randomUUID(),proposal.reviewId(),"FINANCE_APPROVED"));
 }
 @Test void decisionDoesNotHoldReadConnectionWhileAcquiringWriteConnection()throws Exception{
  var config=new com.zaxxer.hikari.HikariConfig();config.setJdbcUrl(f.source.getURL());config.setMaximumPoolSize(1);config.setConnectionTimeout(300);
  try(var pool=new com.zaxxer.hikari.HikariDataSource(config)){
   var limited=new MoneyReviewStore(pool,(a,o,e,p)->{});
   limited.submit(f.actor,f.owner,UUID.randomUUID(),proposal);
   limited.decide(new JdbcFeeGroups.Actor(1,20),f.owner,UUID.randomUUID(),proposal.reviewId(),"CUSTOMER_CONFIRMED");
   assertEquals(2,f.count("finance_money_review_event"));
  }
 }
}
