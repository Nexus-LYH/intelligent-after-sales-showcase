package com.ias.approval;

import com.ias.trust.Chain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.time.Duration;
import javax.sql.DataSource;
import com.ias.notification.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Fresh-schema MySQL proof for the exact approval notification owner projection. */
@EnabledIfEnvironmentVariable(named="IAS_APPROVAL_N1_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:3307/qa_approval_n1_[a-z0-9_]+.*")
class FeeNotificationContextMySqlTest {
 @Test void currentFactAndRevokedGenerationAreResolvedByMysql(){
  var source=physical();var db=new JdbcTemplate(source);
  String ticket=id(),round=id(),group=id(),quote=id(),app=id(),completion=id(),event=completion;
  db.update("INSERT INTO approval_finance_generation VALUES(?,?,?,?)",1L,ticket,1L,"OPEN");
  String snapshot=json(Map.of("state","COMPLETED","applicantId","2")),ticketFact=json(Map.of("groupId",group,"departmentId","8")),completionFact=json(Map.of("groupId",group,"generation","1","companyId","3"));
  db.update("INSERT INTO fee_approval_application VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",1L,app,ticket,group,quote,1L,2L,"COMPLETED",2L,snapshot,java.sql.Timestamp.from(Instant.now()),java.sql.Timestamp.from(Instant.now()));
  db.update("INSERT INTO fee_approval_binding VALUES(?,?,?,?,?,?,?)",1L,app,"d".repeat(64),"{}",ticketFact,"[]",java.sql.Timestamp.from(Instant.now()));
  db.update("INSERT INTO fee_approval_completion VALUES(?,?,?,?,?,?,?)",1L,app,completion,3L,group,completionFact,java.sql.Timestamp.from(Instant.now()));
  db.update("INSERT INTO fee_approval_outbox VALUES(?,?,?,?,?,?,?)",event,1L,app,2L,"FEE_APPROVAL_COMPLETED","{}",java.sql.Timestamp.from(Instant.now()));
  var occurred=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);var envelope=new DomainEvent(UUID.fromString(event),"approval.fee_completed",1,"1",app,"2",occurred,"approval",UUID.randomUUID(),Map.of("ticketId",ticket,"roundId",round,"feeGroupId",group,"generation","1","completionId",completion,"applicationId",app,"quoteId",quote,"quoteVersion","1","companyId","3","stateCode","COMPLETED"));try{new JdbcDelivery(source,10,Duration.ofSeconds(30)).transaction(c->{JdbcDelivery.appendOnOwnerTransaction(c,envelope,Set.of("notification"));return null;});}catch(Exception e){throw new AssertionError(e);}
  assertEquals(event,new ApprovalQueries(source).feeCompletion(UUID.fromString(event),"1",UUID.fromString(app),"approval.fee_completed","2").eventId().toString());var first=new JdbcDelivery(source,10,Duration.ofSeconds(30));try{assertTrue(first.pumpOne((consumer,fact)->{throw new IllegalStateException("ACK_LOST");}));}catch(Exception e){throw new AssertionError(e);}assertEquals("PENDING",db.queryForObject("SELECT state FROM event_delivery WHERE event_id=?",String.class,event));db.update("UPDATE event_delivery SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(3)) WHERE event_id=?",event);try{assertTrue(new JdbcDelivery(source,10,Duration.ofSeconds(30)).pumpOne((consumer,fact)->assertEquals(envelope.sha(),fact.sha())));}catch(Exception e){throw new AssertionError(e);}assertEquals("DELIVERED",db.queryForObject("SELECT state FROM event_delivery WHERE event_id=?",String.class,event));assertEquals(2,db.queryForObject("SELECT attempts FROM event_delivery WHERE event_id=?",Integer.class,event));
  var request=new MockHttpServletRequest();request.setSecure(true);request.setAttribute("ias.chain.edge","notification-approval");var body=new LinkedHashMap<String,Object>();body.put("eventId",event);body.put("tenantId","1");body.put("eventType","approval.fee_completed");body.put("aggregateId",app);body.put("aggregateVersion","2");body.put("feeGroupId",group);body.put("generation","1");body.put("purpose","DELIVERY");
  try(var chain=mockStatic(Chain.class,CALLS_REAL_METHODS)){chain.when(Chain::active).thenReturn(true);chain.when(Chain::role).thenReturn("approval");var controller=new FeeNotificationContextController(source);assertEquals("3",controller.context(body,request).get("companyId"));db.update("UPDATE approval_finance_generation SET generation=2,state='FENCED' WHERE tenant_id=1 AND ticket_id=?",ticket);assertEquals(409,assertThrows(ApprovalFailure.class,()->controller.context(body,request)).status());}
 }
 private static DataSource physical(){try{var type=Class.forName("com.mysql.cj.jdbc.MysqlDataSource");var source=(DataSource)type.getConstructor().newInstance();type.getMethod("setURL",String.class).invoke(source,System.getenv("IAS_APPROVAL_N1_MYSQL_URL"));type.getMethod("setUser",String.class).invoke(source,System.getenv("IAS_APPROVAL_N1_MYSQL_USER"));type.getMethod("setPassword",String.class).invoke(source,System.getenv("IAS_APPROVAL_N1_MYSQL_PASSWORD"));return source;}catch(Exception e){throw new AssertionError(e);}}
 private static String json(Object value){try{return Chain.JSON.writeValueAsString(value);}catch(Exception e){throw new AssertionError(e);}}
 private static String id(){return UUID.randomUUID().toString();}
}
