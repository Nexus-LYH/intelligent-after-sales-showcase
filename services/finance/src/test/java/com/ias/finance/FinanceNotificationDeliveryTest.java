package com.ias.finance;

import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.ias.finance.JdbcMoneyLedger.*;
import static com.ias.finance.MoneyLedger.*;

class FinanceNotificationDeliveryTest {
 JdbcFeeGroupsTest f;JdbcTemplate db;UUID operation;
 @BeforeEach void setup()throws Exception{
  f=new JdbcFeeGroupsTest();f.setup();db=new JdbcTemplate(f.source);var ledger=new JdbcMoneyLedger(f.source,new Authority(){public void current(JdbcFeeGroups.Actor a,FeeGroup.Identity o,String x){}public void verify(JdbcFeeGroups.Actor a,FeeGroup.Identity o,UUID op,Command c){}});
  UUID bill=UUID.randomUUID(),line=UUID.randomUUID();ledger.apply(f.actor,f.owner,UUID.randomUUID(),new Assess(0,0,bill,new Approval(UUID.randomUUID(),"a".repeat(64),Map.of(line,1000L))));operation=UUID.randomUUID();var voucher=new Voucher("offline-1",LocalDate.of(2026,10,27),"BANK_OFFLINE",UUID.randomUUID(),"b".repeat(64));ledger.apply(f.actor,f.owner,operation,new Collect(1,0,bill,new Payment(UUID.randomUUID(),bill,1000,voucher)));
 }
 FinanceNotificationDelivery worker(FinanceNotificationDelivery.Sender sender){return new FinanceNotificationDelivery(db,new TransactionTemplate(new DataSourceTransactionManager(f.source)),sender);}
 @Test void failurePersistsAndStableEventRecoversAfterRestart(){var attempts=new AtomicInteger();var seen=new ArrayList<Map<String,Object>>();var first=worker(event->{seen.add(event);attempts.incrementAndGet();throw new IllegalStateException("notification down");});first.tick();assertEquals("PENDING",db.queryForObject("SELECT state FROM finance_money_notification_delivery WHERE event_id=?",String.class,operation.toString()));assertEquals(1,db.queryForObject("SELECT attempts FROM finance_money_notification_delivery WHERE event_id=?",Integer.class,operation.toString()));db.update("UPDATE finance_money_notification_delivery SET next_attempt_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE event_id=?",operation.toString());var restarted=worker(event->{seen.add(event);return Map.of("eventId",event.get("eventId"),"ack","COMMITTED");});restarted.tick();assertEquals("DELIVERED",db.queryForObject("SELECT state FROM finance_money_notification_delivery WHERE event_id=?",String.class,operation.toString()));assertEquals(seen.get(0),seen.get(1));assertEquals(operation.toString(),seen.getFirst().get("eventId"));assertEquals("finance.collection_finalized",seen.getFirst().get("type"));}
 @Test void lostAckRetriesSameEnvelopeAndDuplicateAckCompletes(){var calls=new AtomicInteger();var seen=new ArrayList<Map<String,Object>>();var w=worker(event->{seen.add(event);if(calls.getAndIncrement()==0)throw new IllegalStateException("ACK lost after remote commit");return Map.of("eventId",event.get("eventId"),"ack","COMMITTED_DUPLICATE");});w.tick();db.update("UPDATE finance_money_notification_delivery SET next_attempt_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE event_id=?",operation.toString());w.tick();assertEquals(2,calls.get());assertEquals(seen.get(0),seen.get(1));assertEquals("DELIVERED",db.queryForObject("SELECT state FROM finance_money_notification_delivery WHERE event_id=?",String.class,operation.toString()));}
 @Test void productionDeliveryUsesPermittedServiceRoute(){
  assertTrue(com.ias.trust.Chain.allowedEdge("finance-notification","POST",FinanceNotificationDelivery.ENDPOINT));
 }
 @Test void deliveryPreservesStoredTerminalPayloadForStrictFinanceAdapter(){
  var requests=new ArrayList<Map<String,Object>>();
  worker(event->{
   requests.add(FinanceNotificationDelivery.terminalRequest(event));
   return Map.of("eventId",event.get("eventId"),"ack","COMMITTED");
  }).tick();
  var stored=db.queryForMap("SELECT event_type,payload_json FROM finance_outbox WHERE event_id=?",operation.toString());
  var expected=com.ias.trust.Chain.JSON.readValue(stored.get("payload_json").toString(),Map.class);
  assertEquals(1,requests.size());
  assertEquals(stored.get("event_type"),requests.getFirst().get("eventType"));
  assertEquals(expected,requests.getFirst().get("payload"));
  assertEquals("DELIVERED",db.queryForObject("SELECT state FROM finance_money_notification_delivery WHERE event_id=?",String.class,operation.toString()));
 }
}
