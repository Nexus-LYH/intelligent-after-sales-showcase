package com.ias.ticket;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TicketFeeNotificationDeliveryTest {
 FinanceLaborExecutionStoreTest f;String event;
 @BeforeEach void setup()throws Exception{f=new FinanceLaborExecutionStoreTest();f.setup();f.execute();event=f.original.get("operationId").toString();}
 TicketFeeNotificationDelivery worker(TicketFeeNotificationDelivery.Sender sender){return new TicketFeeNotificationDelivery(f.db,new TransactionTemplate(new DataSourceTransactionManager(f.source)),sender);}
 @Test void failureRestartAndAckLossRecoverExactOriginalEnvelope(){var seen=new ArrayList<Map<String,Object>>();worker(e->{seen.add(e);throw new IllegalStateException("DOWN");}).tick();assertEquals("PENDING",f.db.queryForObject("SELECT delivery_state FROM ticket_fee_notification_outbox WHERE event_id=?",String.class,event));f.db.update("UPDATE ticket_fee_notification_outbox SET next_attempt_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE event_id=?",event);var calls=new AtomicInteger();var w=worker(e->{seen.add(e);if(calls.getAndIncrement()==0)throw new IllegalStateException("ACK_LOST");return Map.of("eventId",e.get("eventId"),"ack","COMMITTED_DUPLICATE");});w.tick();f.db.update("UPDATE ticket_fee_notification_outbox SET next_attempt_at=DATEADD('SECOND',-1,CURRENT_TIMESTAMP) WHERE event_id=?",event);w.tick();assertEquals("DELIVERED",f.db.queryForObject("SELECT delivery_state FROM ticket_fee_notification_outbox WHERE event_id=?",String.class,event));assertEquals(seen.get(0),seen.get(1));assertEquals(seen.get(1),seen.get(2));assertEquals(event,seen.getFirst().get("eventId"));assertEquals("ticket.fee_execution_recorded",seen.getFirst().get("type"));}
}
