package com.ias.finance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import java.util.concurrent.*;
import java.time.LocalDate;
import static org.junit.jupiter.api.Assertions.*;
import static com.ias.finance.FeeGroup.*;
import static com.ias.finance.JdbcFeeGroups.*;

/** Invoked only after the fresh-schema migration/minimum grant test. No production authority is mocked as passed. */
final class FinanceLedgerMySqlCases {
 static void run()throws Exception{
  String url=System.getenv("IAS_FINANCE_VALIDATION_URL");var source=new DriverManagerDataSource(url,System.getenv("IAS_FINANCE_VALIDATION_USER"),System.getenv("IAS_FINANCE_VALIDATION_PASSWORD"));var db=new JdbcTemplate(source);var admin=new JdbcTemplate(new DriverManagerDataSource(url,System.getenv("IAS_FINANCE_VALIDATION_OWNER"),System.getenv("IAS_FINANCE_VALIDATION_OWNER_PASSWORD")));
  Actor actor=new Actor(1,10);Identity owner=new Identity(1,2,UUID.randomUUID());
  Access authority=new Access(){public void current(Actor a,Identity o,String action){if(!a.equals(actor)||!o.equals(owner))throw new SecurityException();}public void submission(Actor a,Identity o,Submit c,QuoteDetails d){}public void approval(Actor a,Identity o,UUID id,ApprovalFact f){}public void verifiedCost(Actor a,Identity o,CostFact f){}public void execution(Actor a,Identity o,UUID id){}public void customerConfirmation(Actor a,Identity o,QuoteVersion q){}public void quoteDecision(Actor a,Identity o,QuoteVersion q,UUID op,String action,long version,String reason){}};
  var store=new JdbcFeeGroups(source,authority);store.open(actor,owner,UUID.randomUUID());UUID quote=UUID.randomUUID(),quoteLine=UUID.randomUUID();store.submit(actor,owner,new Submit(UUID.randomUUID(),quote,0,Set.of(),List.of(new PlanLine(quoteLine,UUID.randomUUID(),UUID.randomUUID(),250000,false)),new PublishedRule("PUBLISHED_QA_LEDGER",200000)),new QuoteDetails(true,"真实MySQL新报价拒绝样本",Map.of(quoteLine,"LABOR")));
  UUID reject=UUID.randomUUID();admin.execute("ALTER TABLE finance_audit ADD CONSTRAINT qa_finance_reject CHECK(action_name<>'CUSTOMER_QUOTE_REJECTED')");
  assertEquals(3819,assertThrows(java.sql.SQLException.class,()->store.decide(actor,owner,reject,1,quote,1,"CUSTOMER_QUOTE_REJECTED","新样本明确拒绝当前报价")).getErrorCode());
  assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_quote_decision",Long.class));assertEquals(1,store.read(actor,owner).version());admin.execute("ALTER TABLE finance_audit DROP CHECK qa_finance_reject");
  var decision=store.decide(actor,owner,reject,1,quote,1,"CUSTOMER_QUOTE_REJECTED","新样本明确拒绝当前报价");assertEquals(decision,store.decide(actor,owner,reject,1,quote,1,"CUSTOMER_QUOTE_REJECTED","新样本明确拒绝当前报价"));assertEquals(0,store.read(actor,owner).qCents());assertTrue(store.read(actor,owner).everRequiredFinance());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_approval_request WHERE application_id=?",Long.class,quote.toString()));
  var ledger=new JdbcMoneyLedger(source,new JdbcMoneyLedger.Authority(){public void current(Actor a,Identity o,String action){authority.current(a,o,action);}public void verify(Actor a,Identity o,UUID op,JdbcMoneyLedger.Command c){if(c instanceof JdbcMoneyLedger.Reverse)throw new SecurityException("REAL_FUNDS_REQUIRE_REFUND");}});
  UUID bill=UUID.randomUUID(),line=UUID.randomUUID(),assessment=UUID.randomUUID();var assess=new JdbcMoneyLedger.Assess(0,2,bill,new MoneyLedger.Approval(UUID.randomUUID(),"a".repeat(64),Map.of(line,230000L)));
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var gate=new CountDownLatch(1);var jobs=new ArrayList<Future<JdbcMoneyLedger.Receipt>>();for(int n=0;n<6;n++)jobs.add(pool.submit(()->{gate.await();return ledger.apply(actor,owner,assessment,assess);}));gate.countDown();var first=jobs.getFirst().get();for(var job:jobs)assertEquals(first,job.get());}
  assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_money_entry",Long.class));
  var payment=new MoneyLedger.Payment(UUID.randomUUID(),bill,230000,voucher("mysql-collection"));var collect=new JdbcMoneyLedger.Collect(1,2,bill,payment);UUID collectOp=UUID.randomUUID();long ops=db.queryForObject("SELECT COUNT(*) FROM finance_operation",Long.class);
  admin.execute("ALTER TABLE finance_audit ADD CONSTRAINT qa_finance_money CHECK(action_name<>'COLLECTION_RECORDED')");assertEquals(3819,assertThrows(java.sql.SQLException.class,()->ledger.apply(actor,owner,collectOp,collect)).getErrorCode());assertEquals(0,ledger.read(actor,owner).collected());assertEquals(ops,db.queryForObject("SELECT COUNT(*) FROM finance_operation",Long.class));assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_money_reference",Long.class));admin.execute("ALTER TABLE finance_audit DROP CHECK qa_finance_money");
  ledger.apply(actor,owner,collectOp,collect);ledger.apply(actor,owner,UUID.randomUUID(),new JdbcMoneyLedger.Lower(2,2,bill,line,180000,UUID.randomUUID(),"b".repeat(64),"已核实客户确认和财务审批的测试事实"));assertEquals(50000,ledger.read(actor,owner).overpayment());
  assertThrows(SecurityException.class,()->ledger.apply(actor,owner,UUID.randomUUID(),new JdbcMoneyLedger.Reverse(3,2,payment.id(),UUID.randomUUID(),"真实到账不能用冲正伪装退款")));
  UUID refundOp=UUID.randomUUID();var refund=new JdbcMoneyLedger.RefundPaid(3,2,new MoneyLedger.Refund(UUID.randomUUID(),payment.id(),30000,voucher("mysql-refund")));var receipt=ledger.apply(actor,owner,refundOp,refund);assertEquals(receipt,ledger.apply(actor,owner,refundOp,refund));assertEquals(20000,ledger.read(actor,owner).overpayment());
  assertThrows(IllegalStateException.class,()->ledger.apply(actor,owner,UUID.randomUUID(),new JdbcMoneyLedger.RefundPaid(4,2,new MoneyLedger.Refund(UUID.randomUUID(),payment.id(),30000,voucher("mysql-over-refund")))));
  // Cross-process-style reconstruction of the store retains original-key recovery.
  var recreated=new JdbcMoneyLedger(source,new JdbcMoneyLedger.Authority(){public void current(Actor a,Identity o,String action){authority.current(a,o,action);}public void verify(Actor a,Identity o,UUID op,JdbcMoneyLedger.Command command){}});assertEquals(receipt,recreated.operation(actor,owner,refundOp));
  assertEquals(4,db.queryForObject("SELECT COUNT(*) FROM finance_money_entry",Long.class));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_money_reference",Long.class));
  assertThrows(Exception.class,()->db.update("UPDATE finance_money_entry SET action_name='tampered'"));assertThrows(Exception.class,()->db.update("DELETE FROM finance_money_reference"));assertThrows(Exception.class,()->db.update("UPDATE finance_quote_decision SET reason='tampered'"));
  assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND constraint_name LIKE 'qa_finance_%'",Long.class));
 }
 static MoneyLedger.Voucher voucher(String ref){return new MoneyLedger.Voucher(ref,LocalDate.of(2026,10,23),"OFFLINE_BANK",UUID.randomUUID(),"c".repeat(64));}
}
