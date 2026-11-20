package com.ias.stock;

import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.*;
import com.ias.trust.Problem;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;
import static com.ias.stock.StockExecutions.*;

class StockExecutionsTest {
 javax.sql.DataSource db;StockExecutions ledger;MutableClock clock;Approval approval;AtomicBoolean allowed;
 final Actor actor=new Actor(1,10);
 static final class MutableClock extends Clock{Instant now=Instant.parse("2026-10-22T00:00:00Z");public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId z){return this;}public Instant instant(){return now;}}
 @BeforeEach void setup()throws Exception{
  var h2=new JdbcDataSource();h2.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");db=h2;initialize();
 }
 void initialize()throws Exception{clock=new MutableClock();
  try(var c=db.getConnection()){for(String f:List.of("V1__stock_inbound.sql","V5__stock_execution.sql","V6__finance_generation_barrier.sql","V7__finance_terminal_receipts.sql")){String ddl=new String(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/"+f)).readAllBytes(),StandardCharsets.UTF_8); /* H2 2.4 constant-set CHECK retains a closed migration session; real MySQL keeps the original constraint. */ if(c.getMetaData().getDatabaseProductName().equals("H2")&&f.startsWith("V6__"))ddl=ddl.replace(",CHECK(phase IN('FENCED','OWNER_ACKED'))","");if(c.getMetaData().getDatabaseProductName().equals("H2")&&f.startsWith("V7__"))ddl=ddl.replaceAll(",\\s*CHECK\\(source_state IN\\('COMMITTED','ABORTED'\\)\\)","");RunScript.execute(c,new java.io.StringReader(ddl));}}
  sql("INSERT INTO stock_warehouse VALUES(11,1,'W',TRUE)");sql("INSERT INTO stock_part VALUES(101,1,'P1','part','model','pcs',TRUE,0),(102,1,'P2','part','model','pcs',TRUE,0)");sql("INSERT INTO stock_balance VALUES(1,11,101,10,0,0),(1,11,102,10,0,0)");
  allowed=new AtomicBoolean(true);approval=approval("FREE",Map.of(101L,2L));ledger=new StockExecutions(db,(a,c)->{if(!allowed.get())throw new Problem(403,"CURRENT_PERMISSION_DENIED");return approval;},clock);
 }
 Approval approval(String mode,Map<Long,Long> lines){return new Approval(UUID.randomUUID(),1,1,11,UUID.randomUUID().toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),10,clock.instant(),mode,new TreeMap<>(lines));}
 Command cmd(Action action,long version,Map<Long,Long> q){return new Command(UUID.randomUUID(),approval.id(),action,version,new TreeMap<>(q));}
 Result exec(Action action,long version,Map<Long,Long> q)throws Exception{return ledger.execute(actor,cmd(action,version,q));}
 void sql(String sql)throws Exception{try(var c=db.getConnection();var s=c.createStatement()){s.execute(sql);}}
 long number(String sql)throws Exception{try(var c=db.getConnection();var s=c.createStatement();var r=s.executeQuery(sql)){r.next();return r.getLong(1);}}
 long hand()throws Exception{return number("SELECT on_hand FROM stock_balance WHERE part_id=101");}
 long held()throws Exception{return number("SELECT reserved FROM stock_balance WHERE part_id=101");}
 @Test void originalPrdQuantityWalkTenReserveTwoIssueUseOneReturnOneEqualsNine()throws Exception{
  assertEquals("RESERVED",exec(Action.RESERVE,0,Map.of()).state());assertEquals(10,hand());assertEquals(2,held());
  assertEquals("ISSUED",exec(Action.ISSUE,1,Map.of()).state());assertEquals(8,hand());assertEquals(0,held());
  assertEquals("ISSUED",exec(Action.USE,2,Map.of(101L,1L)).state());assertEquals(8,hand());
  assertEquals("ACCOUNTED",exec(Action.RETURN,3,Map.of(101L,1L)).state());assertEquals(9,hand());
  assertEquals(4,number("SELECT COUNT(*) FROM stock_execution_operation"));assertEquals(4,number("SELECT COUNT(*) FROM stock_execution_audit"));assertEquals(4,number("SELECT COUNT(*) FROM stock_execution_outbox"));
 }
 @Test void insufficientOneLineNeverPartiallyReservesAnother()throws Exception{
  approval=approval("FREE",Map.of(101L,2L,102L,11L));assertEquals("WAITING_STOCK",exec(Action.RESERVE,0,Map.of()).state());assertEquals(0,number("SELECT SUM(reserved) FROM stock_balance"));
  sql("UPDATE stock_balance SET on_hand=20 WHERE part_id=102");assertEquals("RESERVED",exec(Action.RESERVE,1,Map.of()).state());assertEquals(13,number("SELECT SUM(reserved) FROM stock_balance"));
 }
 @Test void sameKeyConcurrencyAndChangedParametersCannotRepeatStock()throws Exception{
  var command=cmd(Action.RESERVE,0,Map.of());try(var pool=Executors.newFixedThreadPool(6)){var calls=new ArrayList<Callable<Result>>();for(int i=0;i<6;i++)calls.add(()->ledger.execute(actor,command));for(var f:pool.invokeAll(calls))assertEquals("RESERVED",f.get().state());}
  assertEquals(2,held());assertEquals(1,number("SELECT COUNT(*) FROM stock_execution_operation"));
  var changed=new Command(command.operationId(),command.orderId(),Action.RESERVE,1,new TreeMap<>());assertEquals(409,assertThrows(Problem.class,()->ledger.execute(actor,changed)).status);
 }
 @Test void historicalReceiptDoesNotFollowLaterStateAndQueryNeverWrites()throws Exception{
  var command=cmd(Action.RESERVE,0,Map.of());var original=ledger.execute(actor,command);exec(Action.ISSUE,1,Map.of());assertEquals(original,ledger.original(actor,command.operationId(),id->{}).orElseThrow());assertEquals(original,ledger.execute(actor,command));
  assertTrue(ledger.original(new Actor(1,12),command.operationId(),id->{}).isEmpty());assertTrue(ledger.original(actor,UUID.randomUUID(),id->{}).isEmpty());assertEquals(2,number("SELECT COUNT(*) FROM stock_execution_operation"));
 }
 @Test void revokedCurrentPermissionDeniesOriginalPostAndOriginalQuery()throws Exception{
  var command=cmd(Action.RESERVE,0,Map.of());ledger.execute(actor,command);allowed.set(false);assertEquals(403,assertThrows(Problem.class,()->ledger.execute(actor,command)).status);assertThrows(SecurityException.class,()->ledger.original(actor,command.operationId(),id->{throw new SecurityException();}));assertEquals(2,held());
 }
 @Test void exactTwentyFourHourDeadlineDeniesIssueAndScanReleasesOnce()throws Exception{
  exec(Action.RESERVE,0,Map.of());clock.now=clock.now.plus(Duration.ofHours(24));assertEquals(409,assertThrows(Problem.class,()->exec(Action.ISSUE,1,Map.of())).status);
  assertEquals(1,ledger.expireDue(50));assertEquals(0,ledger.expireDue(50));assertEquals(0,held());assertEquals(10,hand());assertEquals("RESERVED",exec(Action.RESERVE,2,Map.of()).state());
 }
 @Test void deadlineIsMinimumOfReservationDayAndApprovalWeek()throws Exception{
  clock.now=clock.now.plus(Duration.ofDays(6)).plus(Duration.ofHours(23));var r=exec(Action.RESERVE,0,Map.of());assertEquals(approval.approvedAt().plus(Duration.ofDays(7)).toString(),r.expiresAt());clock.now=approval.approvedAt().plus(Duration.ofDays(7));assertEquals(1,ledger.expireDue(50));assertEquals(409,assertThrows(Problem.class,()->exec(Action.RESERVE,2,Map.of())).status);assertEquals(0,held());
 }
 @Test void expiryCleanupContinuesWithDisabledWarehouseAndPart()throws Exception{
  exec(Action.RESERVE,0,Map.of());sql("UPDATE stock_warehouse SET active=FALSE");sql("UPDATE stock_part SET active=FALSE");clock.now=clock.now.plus(Duration.ofHours(24));assertEquals(1,ledger.expireDue(50));assertEquals(0,held());
 }
 @Test void unknownAndPaidPoliciesCanReserveButNeverImplicitlyIssue()throws Exception{
  for(String mode:List.of("UNKNOWN","CUSTOMER_PAID")){approval=approval(mode,Map.of(101L,2L));exec(Action.RESERVE,0,Map.of());assertEquals(503,assertThrows(Problem.class,()->exec(Action.ISSUE,1,Map.of())).status);}assertEquals(10,hand());assertEquals(4,held());
 }
 @Test void fullReturnCannotReuseOldApprovalOrReturnConsumedQuantity()throws Exception{
  exec(Action.RESERVE,0,Map.of());exec(Action.ISSUE,1,Map.of());assertEquals("RETURNED",exec(Action.RETURN,2,Map.of(101L,2L)).state());assertEquals(10,hand());assertEquals(409,assertThrows(Problem.class,()->exec(Action.RESERVE,3,Map.of())).status);assertEquals(409,assertThrows(Problem.class,()->exec(Action.RETURN,3,Map.of(101L,1L))).status);
 }
 @Test void useDoesNotDoubleDeductAndReturnCannotExceedUnaccounted()throws Exception{
  exec(Action.RESERVE,0,Map.of());exec(Action.ISSUE,1,Map.of());exec(Action.USE,2,Map.of(101L,1L));assertEquals(8,hand());assertEquals(409,assertThrows(Problem.class,()->exec(Action.RETURN,3,Map.of(101L,2L))).status);assertEquals(8,hand());assertEquals(4,number("SELECT COUNT(*) FROM stock_execution_operation"));assertEquals(1,number("SELECT COUNT(*) FROM stock_execution_audit WHERE action_name='STOCK_REJECTED'"));
 }
 @Test void auditFailureRollsBackBalanceOrderAndReceiptOriginalKeyRecovers()throws Exception{
  sql("ALTER TABLE stock_execution_audit ADD CONSTRAINT fail_audit CHECK(actor_id<>10)");var command=cmd(Action.RESERVE,0,Map.of());assertThrows(SQLException.class,()->ledger.execute(actor,command));for(String t:List.of("stock_execution_order","stock_execution_line","stock_execution_operation","stock_execution_movement","stock_execution_audit","stock_execution_outbox"))assertEquals(0,number("SELECT COUNT(*) FROM "+t));assertEquals(0,held());sql("ALTER TABLE stock_execution_audit DROP CONSTRAINT fail_audit");assertEquals("RESERVED",ledger.execute(actor,command).state());
 }
 @Test void outboxFailureDuringIssueKeepsReservationAndAllPhysicalQuantities()throws Exception{
  exec(Action.RESERVE,0,Map.of());sql("ALTER TABLE stock_execution_outbox ADD CONSTRAINT fail_event CHECK(event_type<>'stock.issue')");var command=cmd(Action.ISSUE,1,Map.of());assertThrows(SQLException.class,()->ledger.execute(actor,command));assertEquals(10,hand());assertEquals(2,held());assertEquals(1,number("SELECT COUNT(*) FROM stock_execution_operation"));sql("ALTER TABLE stock_execution_outbox DROP CONSTRAINT fail_event");assertEquals("ISSUED",ledger.execute(actor,command).state());assertEquals(8,hand());
 }
 @Test void systemExpiryCannotBeInvokedByPublicActor()throws Exception{assertEquals(403,assertThrows(Problem.class,()->ledger.execute(actor,cmd(Action.EXPIRE,0,Map.of()))).status);}
 @Test void knownRejectionIsDurableAndLateOriginalCannotExecuteAfterMoreStock()throws Exception{
  exec(Action.RESERVE,0,Map.of());exec(Action.ISSUE,1,Map.of());var bad=cmd(Action.RETURN,2,Map.of(101L,3L));assertEquals(409,assertThrows(Problem.class,()->ledger.execute(actor,bad)).status);
  var receipt=ledger.original(actor,bad.operationId(),id->{}).orElseThrow();assertEquals("REJECTED:QUANTITY_EXCEEDS_UNACCOUNTED",receipt.state());assertEquals(receipt,ledger.execute(actor,bad));assertEquals(8,hand());assertEquals(0,number("SELECT COUNT(*) FROM stock_execution_movement WHERE operation_id='"+bad.operationId()+"'"));
 }
}
