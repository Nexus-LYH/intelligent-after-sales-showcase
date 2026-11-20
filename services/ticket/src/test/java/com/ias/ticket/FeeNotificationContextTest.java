package com.ias.ticket;

import com.ias.trust.Chain;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FeeNotificationContextTest {
 @Test void exactBindingCurrentScopeAndReadRevocationFailClosed(){
  var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");var db=new JdbcTemplate(ds);
  db.execute("CREATE TABLE ticket_create_operation(operation_id CHAR(36) PRIMARY KEY,state VARCHAR(20))");
  db.execute("CREATE TABLE ticket_record(id CHAR(36) PRIMARY KEY,operation_id CHAR(36),tenant_id BIGINT,round_id CHAR(36),assignment_id CHAR(36),company_id BIGINT,reporter_user_id BIGINT,status VARCHAR(40))");
  db.execute("CREATE TABLE ticket_assignment(id CHAR(36) PRIMARY KEY,ticket_id CHAR(36),department_id BIGINT)");
  db.execute("CREATE TABLE ticket_finance_quote_fence(tenant_id BIGINT,ticket_id CHAR(36),generation BIGINT,group_id CHAR(36))");
  db.execute("CREATE TABLE ticket_fee_notification_outbox(event_id CHAR(36) PRIMARY KEY,tenant_id BIGINT,event_type VARCHAR(64),aggregate_id CHAR(36),aggregate_version BIGINT,fee_group_id CHAR(36),generation BIGINT,ticket_id CHAR(36),round_id CHAR(36),assignment_id CHAR(36),company_id BIGINT,department_id BIGINT,applicant_user_id BIGINT,envelope LONGTEXT,event_digest CHAR(64),delivery_state VARCHAR(16),attempt_count INT,next_attempt_at TIMESTAMP,lease_until TIMESTAMP,last_error VARCHAR(64),created_at TIMESTAMP,delivered_at TIMESTAMP)");
  String op=id(),ticket=id(),round=id(),assignment=id(),group=id(),event=id(),receipt=id();db.update("INSERT INTO ticket_create_operation VALUES(?,'COMPLETED')",op);db.update("INSERT INTO ticket_record VALUES(?,?,?,?,?,?,?,?)",ticket,op,1L,round,assignment,3L,2L,"IN_PROGRESS");db.update("INSERT INTO ticket_assignment VALUES(?,?,?)",assignment,ticket,8L);db.update("INSERT INTO ticket_finance_quote_fence VALUES(?,?,?,?)",1L,ticket,1L,group);db.update("INSERT INTO ticket_fee_notification_outbox VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",event,1L,"ticket.fee_execution_recorded",receipt,7L,group,1L,ticket,round,assignment,3L,8L,2L,"{}","d".repeat(64),"PENDING",0,java.sql.Timestamp.from(Instant.now()),null,null,java.sql.Timestamp.from(Instant.now()),null);
  var body=new LinkedHashMap<String,Object>();body.put("eventId",event);body.put("tenantId","1");body.put("eventType","ticket.fee_execution_recorded");body.put("aggregateId",receipt);body.put("aggregateVersion","7");body.put("feeGroupId",group);body.put("generation","1");body.put("purpose","DELIVERY");var request=new MockHttpServletRequest();request.setSecure(true);request.setAttribute("ias.chain.edge","notification-ticket");
  try(var chain=mockStatic(Chain.class,CALLS_REAL_METHODS)){chain.when(Chain::active).thenReturn(true);chain.when(Chain::role).thenReturn("ticket");var controller=new FeeNotificationContextController(ds);var result=controller.context(body,request);assertEquals("3",result.get("companyId"));assertEquals("8",result.get("departmentId"));assertEquals("2",result.get("applicantUserId"));var stale=new LinkedHashMap<>(body);stale.put("generation","2");assertEquals(409,assertThrows(com.ias.trust.Problem.class,()->controller.context(stale,request)).status);var read=new LinkedHashMap<>(body);read.put("purpose","READ");read.put("sessionToken","s");chain.when(()->Chain.call(eq("ticket-identity"),eq("/internal/identity/domain-authorizations"),anyMap(),anyString(),anyLong())).thenReturn(Map.of("enabled",false,"tenantId","1","companyIds",List.of("3"),"checkedAt",Instant.now().toString()));assertEquals(403,assertThrows(com.ias.trust.Problem.class,()->controller.context(read,request)).status);db.update("UPDATE ticket_record SET assignment_id=? WHERE id=?",id(),ticket);assertEquals(409,assertThrows(com.ias.trust.Problem.class,()->controller.context(body,request)).status);}
 }
 static String id(){return UUID.randomUUID().toString();}
}
