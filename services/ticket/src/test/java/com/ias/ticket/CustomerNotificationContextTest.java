package com.ias.ticket;
import com.ias.trust.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.mockito.MockedStatic;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomerNotificationContextTest {
 JdbcTemplate db;MockedStatic<Chain> chain;MockHttpServletRequest request;String ticket,event,round;long tenant=1,company=2,recipient=10;
 @BeforeEach void setup(){var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:customer_notice;MODE=MySQL;DB_CLOSE_DELAY=-1");db=new JdbcTemplate(source);db.execute("DROP TABLE IF EXISTS ticket_customer_notification_outbox");db.execute("CREATE TABLE ticket_customer_notification_outbox(event_id VARCHAR(36) PRIMARY KEY,tenant_id BIGINT,event_type VARCHAR(64),aggregate_id VARCHAR(36),aggregate_version BIGINT,ticket_id VARCHAR(36),round_id VARCHAR(36),company_id BIGINT,recipient_user_id BIGINT,state_code VARCHAR(64),envelope CLOB,event_digest VARCHAR(64),delivery_state VARCHAR(16),attempt_count INT,next_attempt_at TIMESTAMP,lease_until TIMESTAMP,last_error VARCHAR(64),created_at TIMESTAMP,delivered_at TIMESTAMP)");
  chain=mockStatic(Chain.class,CALLS_REAL_METHODS);chain.when(Chain::active).thenReturn(true);chain.when(Chain::role).thenReturn("ticket");
  request=new MockHttpServletRequest();request.setSecure(true);request.setAttribute("ias.chain.edge","notification-ticket");
  ticket=UUID.randomUUID().toString();event=UUID.randomUUID().toString();round=UUID.randomUUID().toString();var occurred=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
  var payload=new TreeMap<String,String>();payload.put("ticketId",ticket);payload.put("roundId",round);payload.put("companyId",Long.toString(company));payload.put("stateCode","WAITING_ASSIGNMENT");payload.put("action","ACCEPT");payload.put("processingRecordId",UUID.randomUUID().toString());
  var envelope=new LinkedHashMap<String,Object>();envelope.put("eventId",event);envelope.put("type","ticket.accepted");envelope.put("schemaVersion",1);envelope.put("tenantId",Long.toString(tenant));envelope.put("aggregateId",ticket);envelope.put("aggregateVersion","5");envelope.put("occurredAt",String.format(java.util.Locale.ROOT,"%s.%03dZ",occurred.toString().substring(0,19),occurred.getNano()/1_000_000));envelope.put("producer","ticket");envelope.put("correlationId",event);envelope.put("payload",payload);
  String json=Chain.JSON.writeValueAsString(envelope);db.update("INSERT INTO ticket_customer_notification_outbox(event_id,tenant_id,event_type,aggregate_id,aggregate_version,ticket_id,round_id,company_id,recipient_user_id,state_code,envelope,event_digest,delivery_state,attempt_count,next_attempt_at,created_at) VALUES(?,?,'ticket.accepted',?,?,?,?,?,?,?,?,?,'PENDING',0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",event,tenant,ticket,5L,ticket,round,company,recipient,"WAITING_ASSIGNMENT",json,Tickets.hash(json));
 }
 @AfterEach void close(){chain.close();}
 Map<String,Object> body(){var b=new LinkedHashMap<String,Object>();b.put("eventId",event);b.put("tenantId",Long.toString(tenant));b.put("ticketId",ticket);b.put("eventType","ticket.accepted");b.put("aggregateVersion","5");b.put("purpose","DELIVERY");return b;}
 @Test void deliveryReturnsImmutableTicketAndRecipientFacts(){var out=new CustomerNotificationContextController(db).context(body(),request);assertEquals(ticket,out.get("ticketId"));assertEquals("10",out.get("recipientUserId"));assertEquals("2",out.get("companyId"));assertEquals("WAITING_ASSIGNMENT",out.get("stateCode"));assertEquals(event,out.get("eventId"));}
 @Test void digestTamperUnknownEventAndExtraFieldsFailClosed(){db.update("UPDATE ticket_customer_notification_outbox SET event_digest=?", "0".repeat(64));assertEquals(503,assertThrows(Problem.class,()->new CustomerNotificationContextController(db).context(body(),request)).status);
  var unknown=body();unknown.put("eventId",UUID.randomUUID().toString());assertEquals(404,assertThrows(Problem.class,()->new CustomerNotificationContextController(db).context(unknown,request)).status);
  var extra=body();extra.put("sessionToken","forged");assertEquals(400,assertThrows(Problem.class,()->new CustomerNotificationContextController(db).context(extra,request)).status);}
}
