package com.ias.ticket;
import com.ias.trust.Chain;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Durable delivery for customer-visible ticket events. */
@Component
@ConditionalOnProperty(name="ias.ticket.customer-notification-delivery.enabled",havingValue="true")
final class TicketCustomerNotificationDelivery {
 interface Sender{Map<String,Object> send(Map<String,Object> event);}
 record Claim(String event,String lease,int attempt,Map<String,Object> envelope){}
 private final JdbcTemplate db;private final TransactionTemplate tx;private final Sender sender;
 @org.springframework.beans.factory.annotation.Autowired TicketCustomerNotificationDelivery(JdbcTemplate db,TransactionTemplate tx){this(db,tx,event->Chain.call("ticket-notification","/internal/notification/events",Map.of("event",event),UUID.randomUUID().toString(),System.currentTimeMillis()+10000));}
 TicketCustomerNotificationDelivery(JdbcTemplate db,TransactionTemplate tx,Sender sender){this.db=db;this.tx=tx;this.sender=sender;}
 @Scheduled(fixedDelay=1000,initialDelay=5000) void tick(){for(int i=0;i<10;i++){var c=claim();if(c==null)return;try{var ack=sender.send(c.envelope);if(!c.event.equals(ack.get("eventId"))||!Set.of("COMMITTED","COMMITTED_DUPLICATE").contains(ack.get("ack")))throw new IllegalStateException("ACK_UNCONFIRMED");complete(c);}catch(Exception e){failed(c);}}}
 @SuppressWarnings("unchecked") Claim claim(){return tx.execute(s->{var rows=db.queryForList("SELECT event_id,attempt_count,envelope FROM ticket_customer_notification_outbox WHERE ((delivery_state='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(3)) OR (delivery_state='IN_FLIGHT' AND lease_until<=CURRENT_TIMESTAMP(3))) ORDER BY next_attempt_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED");if(rows.isEmpty())return null;var r=rows.getFirst();int attempt=((Number)r.get("attempt_count")).intValue()+1;String event=r.get("event_id").toString(),lease=UUID.randomUUID().toString();db.update("UPDATE ticket_customer_notification_outbox SET delivery_state='IN_FLIGHT',attempt_count=?,lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3)),last_error=? WHERE event_id=?",attempt,lease,event);return new Claim(event,lease,attempt,Chain.JSON.readValue(r.get("envelope").toString(),Map.class));});}
 void complete(Claim c){tx.executeWithoutResult(s->db.update("UPDATE ticket_customer_notification_outbox SET delivery_state='DELIVERED',lease_until=NULL,last_error=NULL,delivered_at=CURRENT_TIMESTAMP(3) WHERE event_id=? AND delivery_state='IN_FLIGHT' AND last_error=?",c.event,c.lease));}
 void failed(Claim c){long seconds=Math.min(300,1L<<Math.min(8,Math.max(0,c.attempt-1)));tx.executeWithoutResult(s->db.update("UPDATE ticket_customer_notification_outbox SET delivery_state='PENDING',next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)),lease_until=NULL,last_error='DELIVERY_UNCONFIRMED' WHERE event_id=? AND delivery_state='IN_FLIGHT' AND last_error=?",seconds,c.event,c.lease));}
}
