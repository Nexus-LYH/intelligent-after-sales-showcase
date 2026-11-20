package com.ias.stock;
import com.ias.trust.Chain;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
@Component @ConditionalOnProperty(name="ias.stock.part-fee-notification-delivery.enabled",havingValue="true")
final class StockPartFeeNotificationDelivery {
 interface Sender{Map<String,Object> send(Map<String,Object> event);}record Claim(String event,String lease,int attempt,Map<String,Object> envelope){}
 private final JdbcTemplate db;private final TransactionTemplate tx;private final Sender sender;
 @org.springframework.beans.factory.annotation.Autowired StockPartFeeNotificationDelivery(JdbcTemplate db,org.springframework.transaction.PlatformTransactionManager m){this(db,new TransactionTemplate(m),e->Chain.call("stock-notification","/internal/notification/events",Map.of("event",e),UUID.randomUUID().toString(),System.currentTimeMillis()+10000));}
 StockPartFeeNotificationDelivery(JdbcTemplate db,TransactionTemplate tx,Sender sender){this.db=db;this.tx=tx;this.sender=sender;}
 @Scheduled(fixedDelay=1000,initialDelay=5000)void tick(){for(int i=0;i<10;i++){var c=claim();if(c==null)return;try{var a=sender.send(c.envelope);if(!c.event.equals(a.get("eventId"))||!Set.of("COMMITTED","COMMITTED_DUPLICATE").contains(a.get("ack")))throw new IllegalStateException();complete(c);}catch(Exception e){failed(c);}}}
 @SuppressWarnings("unchecked")Claim claim(){return tx.execute(s->{var x=db.queryForList("SELECT event_id,attempt_count,envelope FROM stock_part_fee_notification_outbox WHERE ((delivery_state='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(3)) OR (delivery_state='IN_FLIGHT' AND lease_until<=CURRENT_TIMESTAMP(3))) ORDER BY next_attempt_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED");if(x.isEmpty())return null;var r=x.getFirst();String id=r.get("event_id").toString(),lease=UUID.randomUUID().toString();int n=((Number)r.get("attempt_count")).intValue()+1;db.update("UPDATE stock_part_fee_notification_outbox SET delivery_state='IN_FLIGHT',attempt_count=?,lease_id=?,lease_until=TIMESTAMPADD(SECOND,30,CURRENT_TIMESTAMP(3)) WHERE event_id=?",n,lease,id);return new Claim(id,lease,n,Chain.JSON.readValue(r.get("envelope").toString(),Map.class));});}
 void complete(Claim c){tx.executeWithoutResult(s->db.update("UPDATE stock_part_fee_notification_outbox SET delivery_state='DELIVERED',lease_id=NULL,lease_until=NULL,last_error=NULL,delivered_at=CURRENT_TIMESTAMP(3) WHERE event_id=? AND lease_id=?",c.event,c.lease));}
 void failed(Claim c){long d=Math.min(300,1L<<Math.min(8,Math.max(0,c.attempt-1)));tx.executeWithoutResult(s->db.update("UPDATE stock_part_fee_notification_outbox SET delivery_state='PENDING',next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)),lease_id=NULL,lease_until=NULL,last_error='DELIVERY_UNCONFIRMED' WHERE event_id=? AND lease_id=?",d,c.event,c.lease));}
}
