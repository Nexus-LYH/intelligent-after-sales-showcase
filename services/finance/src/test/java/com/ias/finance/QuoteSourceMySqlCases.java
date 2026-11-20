package com.ias.finance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import java.util.concurrent.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
import static com.ias.finance.FeeGroup.*;
import static com.ias.finance.JdbcFeeGroups.*;

/** Third explicit fresh-schema test; facts/identity are injected, SQL/locks/grants are actual3307. */
final class QuoteSourceMySqlCases {
 static void run()throws Exception{
  String url=System.getenv("IAS_FINANCE_VALIDATION_URL");var source=new DriverManagerDataSource(url,System.getenv("IAS_FINANCE_VALIDATION_USER"),System.getenv("IAS_FINANCE_VALIDATION_PASSWORD"));var db=new JdbcTemplate(source);var admin=new JdbcTemplate(new DriverManagerDataSource(url,System.getenv("IAS_FINANCE_VALIDATION_OWNER"),System.getenv("IAS_FINANCE_VALIDATION_OWNER_PASSWORD")));
  Actor actor=new Actor(1,10);UUID relation=UUID.randomUUID();Identity owner=new Identity(1,2,UUID.nameUUIDFromBytes(("finance:1:2:"+relation).getBytes(StandardCharsets.UTF_8)));Access authority=new Access(){public void current(Actor a,Identity o,String action){if(!actor.equals(a)||!owner.equals(o))throw new SecurityException();}public void submission(Actor a,Identity o,Submit c,QuoteDetails d){}public void approval(Actor a,Identity o,UUID id,ApprovalFact fact){}public void verifiedCost(Actor a,Identity o,CostFact fact){}public void execution(Actor a,Identity o,UUID line){}};
  var store=new JdbcFeeGroups(source,authority);var sources=new JdbcQuoteSources(source,authority);store.open(actor,owner,UUID.randomUUID());
  for(int i=0;i<3;i++){
   long version=store.read(actor,owner).version();var c=command(version);var d=details(c);sources.register(actor,owner,c,d);var b=JdbcQuoteSources.binding(actor,owner,c,d);var line=c.additions().getFirst();assertEquals("IN_PROGRESS",sources.verified(b,line.ticketId(),line.roundId(),relation).state());
   try(var pool=Executors.newVirtualThreadPerTaskExecutor()){var gate=new CountDownLatch(1);var commit=pool.submit(()->{gate.await();try{store.submit(actor,owner,c,d);return true;}catch(IllegalStateException expected){assertEquals("FINANCE_SOURCE_ABORTED",expected.getMessage());return false;}});var abort=pool.submit(()->{gate.await();return sources.abort(actor,owner,c.operationId(),c.applicationId(),version,b.commandDigest(),"真实MySQL源事务竞争中止样本");});gate.countDown();boolean committed=commit.get(10,TimeUnit.SECONDS);assertEquals(committed?"COMMITTED":"ABORTED",abort.get(10,TimeUnit.SECONDS).state());assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM finance_quote_source_outbox WHERE tenant_id=1 AND operation_id=?",Long.class,c.operationId().toString()));assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM finance_quote_source_event WHERE tenant_id=1 AND operation_id=?",Long.class,c.operationId().toString()));}
  }
  var c=command(store.read(actor,owner).version());var d=details(c);sources.register(actor,owner,c,d);var binding=JdbcQuoteSources.binding(actor,owner,c,d);
  admin.execute("ALTER TABLE finance_quote_source_outbox ADD CONSTRAINT qa_finance_source_outbox CHECK(event_type<>'FINANCE_QUOTE_SOURCE_COMMITTED' OR operation_id<>'"+c.operationId()+"')");
  long ops=db.queryForObject("SELECT COUNT(*) FROM finance_operation",Long.class),quotes=db.queryForObject("SELECT COUNT(*) FROM finance_quote_version",Long.class);
  assertEquals(3819,assertThrows(java.sql.SQLException.class,()->store.submit(actor,owner,c,d)).getErrorCode());assertEquals("IN_PROGRESS",sources.read(actor,owner,c.operationId()).state());assertEquals(ops,db.queryForObject("SELECT COUNT(*) FROM finance_operation",Long.class));assertEquals(quotes,db.queryForObject("SELECT COUNT(*) FROM finance_quote_version",Long.class));assertEquals(c.expectedVersion(),store.read(actor,owner).version());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM finance_quote_source_outbox WHERE operation_id=?",Long.class,c.operationId().toString()));admin.execute("ALTER TABLE finance_quote_source_outbox DROP CHECK qa_finance_source_outbox");
  var receipt=store.submit(actor,owner,c,d);var committed=sources.read(actor,owner,c.operationId());assertEquals("COMMITTED",committed.state());assertEquals(JdbcMoneyLedger.digest(receipt),committed.resultDigest());assertEquals(committed,sources.abort(actor,owner,c.operationId(),c.applicationId(),c.expectedVersion(),binding.commandDigest(),"已提交源事实不得被后来ABORT改写"));
  var late=command(store.read(actor,owner).version());var ld=details(late);sources.register(actor,owner,late,ld);sources.abort(actor,owner,late.operationId(),late.applicationId(),late.expectedVersion(),JdbcFeeGroups.quoteDigest(owner,late,ld),"先中止再迟到提交必须拒绝的真实样本");assertEquals("FINANCE_SOURCE_ABORTED",assertThrows(IllegalStateException.class,()->store.submit(actor,owner,late,ld)).getMessage());
  assertThrows(Exception.class,()->db.update("UPDATE finance_quote_source SET actor_id=99"));assertThrows(Exception.class,()->db.update("DELETE FROM finance_quote_source_event"));assertThrows(Exception.class,()->db.update("UPDATE finance_quote_source_outbox SET payload_json='{}'"));assertThrows(Exception.class,()->db.queryForList("SELECT * FROM finance_quote_source_event FOR UPDATE"));
  assertEquals(0,admin.queryForObject("SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema=DATABASE() AND constraint_name LIKE 'qa_finance_%'",Long.class));
 }
 static Submit command(long version){return new Submit(UUID.randomUUID(),UUID.randomUUID(),version,Set.of(),List.of(new PlanLine(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),10000,false)),new PublishedRule("PUBLISHED_SOURCE_MYSQL",200000));}
 static QuoteDetails details(Submit c){return new QuoteDetails(true,"真实MySQL源意图与中止测试",Map.of(c.additions().getFirst().id(),"LABOR"));}
}
