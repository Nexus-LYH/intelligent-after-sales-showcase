package com.ias.stock;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class StockPartFeeNotificationDeliveryTest {
 FinancePartExecutionStoreTest f;JdbcTemplate db;String event;
 @BeforeEach void setup()throws Exception{f=new FinancePartExecutionStoreTest();f.setup();event=UUID.randomUUID().toString();f.store.execute(f.command(event));db=new JdbcTemplate(f.db);}
 StockPartFeeNotificationDelivery worker(StockPartFeeNotificationDelivery.Sender s){return new StockPartFeeNotificationDelivery(db,new TransactionTemplate(new DataSourceTransactionManager(f.db)),s);}
 @SuppressWarnings("unchecked")
 @Test void unavailableRestartAndDuplicateAckRecoverExactUseEvent(){var seen=new ArrayList<Map<String,Object>>();worker(e->{seen.add(e);throw new IllegalStateException();}).tick();assertEquals("PENDING",db.queryForObject("SELECT delivery_state FROM stock_part_fee_notification_outbox WHERE event_id=?",String.class,event));db.update("UPDATE stock_part_fee_notification_outbox SET next_attempt_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE event_id=?",event);worker(e->{seen.add(e);return Map.of("eventId",e.get("eventId"),"ack","COMMITTED_DUPLICATE");}).tick();assertEquals("DELIVERED",db.queryForObject("SELECT delivery_state FROM stock_part_fee_notification_outbox WHERE event_id=?",String.class,event));assertEquals(seen.get(0),seen.get(1));assertEquals("inventory.consumed",seen.getFirst().get("type"));var payload=(Map<String,Object>)seen.getFirst().get("payload");assertEquals("11",payload.get("warehouseId"));assertEquals("101",payload.get("partId"));assertEquals("2",payload.get("quantity"));}
}
