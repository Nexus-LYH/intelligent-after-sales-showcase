package com.ias.finance;

import com.ias.trust.Chain;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Durable at-least-once delivery of committed money terminal facts. */
@Component
@ConditionalOnProperty(name="ias.finance.notification-delivery.enabled",havingValue="true")
final class FinanceNotificationDelivery {
 static final String ENDPOINT="/internal/notification/finance-events";
 interface Sender { Map<String,Object> send(Map<String,Object> event); }
 record Claim(long tenant,UUID event,UUID lease,int attempt,Map<String,Object> envelope){}
 private final JdbcTemplate db;private final TransactionTemplate tx;private final Sender sender;
 @org.springframework.beans.factory.annotation.Autowired FinanceNotificationDelivery(DataSource source,org.springframework.transaction.PlatformTransactionManager manager){this(new JdbcTemplate(source),new TransactionTemplate(manager),event->Chain.call("finance-notification",ENDPOINT,terminalRequest(event),UUID.randomUUID().toString(),System.currentTimeMillis()+10000));}
 FinanceNotificationDelivery(JdbcTemplate db,TransactionTemplate tx,Sender sender){this.db=db;this.tx=tx;this.sender=sender;}
 @Scheduled(fixedDelay=1000,initialDelay=5000) void tick(){for(int i=0;i<10;i++){var c=claim();if(c==null)return;try{var ack=sender.send(c.envelope);if(!c.event.toString().equals(ack.get("eventId"))||!Set.of("COMMITTED","COMMITTED_DUPLICATE").contains(ack.get("ack")))throw new IllegalStateException("DELIVERY_ACK_UNCONFIRMED");complete(c);}catch(Exception failure){failed(c);}}}
 Claim claim(){return tx.execute(status->{var rows=db.queryForList("SELECT tenant_id,event_id,attempts FROM finance_money_notification_delivery WHERE ((state='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(3)) OR (state='CLAIMED' AND lease_until<=CURRENT_TIMESTAMP(3))) ORDER BY next_attempt_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED");if(rows.isEmpty())return null;var r=rows.getFirst();int attempts=((Number)r.get("attempts")).intValue();long tenant=((Number)r.get("tenant_id")).longValue();UUID event=UUID.fromString(r.get("event_id").toString());if(attempts>=10){db.update("UPDATE finance_money_notification_delivery SET state='DEAD',lease_id=NULL,lease_until=NULL,last_error_code='ATTEMPTS_EXHAUSTED',updated_at=CURRENT_TIMESTAMP(3) WHERE tenant_id=? AND event_id=?",tenant,event.toString());return null;}var fact=db.queryForMap("SELECT event_type,payload_json FROM finance_outbox WHERE tenant_id=? AND event_id=?",tenant,event.toString());UUID lease=UUID.randomUUID();db.update("UPDATE finance_money_notification_delivery SET state='CLAIMED',attempts=attempts+1,lease_id=?,lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3)),updated_at=CURRENT_TIMESTAMP(3) WHERE tenant_id=? AND event_id=?",lease.toString(),tenant,event.toString());return new Claim(tenant,event,lease,attempts+1,envelope(event,fact.get("event_type").toString(),fact.get("payload_json").toString()));});}
 @SuppressWarnings("unchecked") private Map<String,Object> envelope(UUID event,String storedType,String json){try{Map<String,Object> p=Chain.JSON.readValue(json,Map.class);String type=switch(storedType){case "FINANCE_COLLECTION_FINALIZED"->"finance.collection_finalized";case "FINANCE_REFUND_FINALIZED"->"finance.refund_finalized";default->throw new IllegalArgumentException("UNSUPPORTED_MONEY_EVENT");};var payload=new TreeMap<String,Object>();for(String key:List.of("companyId","groupId","billId","completionId","accountVersion","action","businessId","amountCents","authorityDigest","executionReceiptsDigest"))payload.put(key,p.get(key));if(p.get("originalPaymentId")!=null)payload.put("originalPaymentId",p.get("originalPaymentId"));payload.put("moneyOccurredAt",p.get("occurredAt"));var out=new LinkedHashMap<String,Object>();out.put("eventId",event.toString());out.put("type",type);out.put("schemaVersion",1);out.put("tenantId",p.get("tenantId"));out.put("aggregateId",p.get("businessId"));out.put("aggregateVersion",p.get("accountVersion"));out.put("occurredAt",p.get("occurredAt"));out.put("producer","finance");out.put("correlationId",p.get("operationId"));out.put("payload",payload);return out;}catch(Exception e){throw new IllegalArgumentException("INVALID_MONEY_EVENT",e);}}
 @SuppressWarnings("unchecked")
 static Map<String,Object> terminalRequest(Map<String,Object> event){
  var payload=new LinkedHashMap<>((Map<String,Object>)event.get("payload"));
  payload.put("schemaVersion","1");
  payload.put("operationId",event.get("eventId"));
  payload.put("tenantId",event.get("tenantId"));
  payload.put("occurredAt",payload.remove("moneyOccurredAt"));
  payload.putIfAbsent("originalPaymentId",null);
  String type=switch(event.get("type").toString()){
   case "finance.collection_finalized"->"FINANCE_COLLECTION_FINALIZED";
   case "finance.refund_finalized"->"FINANCE_REFUND_FINALIZED";
   default->throw new IllegalArgumentException("UNSUPPORTED_MONEY_EVENT");
  };
  return Map.of("eventType",type,"payload",payload);
 }
 void complete(Claim c){tx.executeWithoutResult(s->db.update("UPDATE finance_money_notification_delivery SET state='DELIVERED',delivered_at=CURRENT_TIMESTAMP(3),lease_id=NULL,lease_until=NULL,last_error_code=NULL,updated_at=CURRENT_TIMESTAMP(3) WHERE tenant_id=? AND event_id=? AND state='CLAIMED' AND lease_id=?",c.tenant,c.event.toString(),c.lease.toString()));}
 void failed(Claim c){String state=c.attempt>=10?"DEAD":"PENDING";long delay=Math.min(300,1L<<Math.min(8,Math.max(0,c.attempt-1)));tx.executeWithoutResult(s->db.update("UPDATE finance_money_notification_delivery SET state=?,next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)),lease_id=NULL,lease_until=NULL,last_error_code='DELIVERY_UNCONFIRMED',updated_at=CURRENT_TIMESTAMP(3) WHERE tenant_id=? AND event_id=? AND state='CLAIMED' AND lease_id=?",state,delay,c.tenant,c.event.toString(),c.lease.toString()));}
}

