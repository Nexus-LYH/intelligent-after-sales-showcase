package com.ias.ticket;

import com.ias.trust.Chain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named="IAS_TICKET_N1_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:3307/qa_ticket_n1_[a-z0-9_]+.*")
class FeeNotificationContextMySqlTest {
 @Test void mysqlResolvesCurrentFactAndRejectsRevokedAssignment(){
  var ds=new DriverManagerDataSource(System.getenv("IAS_TICKET_N1_MYSQL_URL"),System.getenv("IAS_TICKET_N1_MYSQL_USER"),System.getenv("IAS_TICKET_N1_MYSQL_PASSWORD"));var db=new JdbcTemplate(ds);String create=id(),ticket=id(),round=id(),assignment=id(),group=id(),event=id(),receipt=id(),deviceReservation=id();
  db.update("INSERT INTO ticket_create_operation(operation_id,binding_id,ticket_id,tenant_id,actor_user_id,device_id,request_digest,input_json,state,row_version,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,'COMPLETED',1,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",create,deviceReservation,ticket,1L,2L,4L,"d".repeat(64),"{}");
  db.update("INSERT INTO ticket_record(id,ticket_no,operation_id,tenant_id,reporter_user_id,company_id,device_id,title,description,category_code,impact,contact_name,contact_phone,snapshot_json,submitted_at,created_at,round_id,initial_priority,priority,response_due_at,resolution_due_at,status,row_version,assignment_id,department_id,engineer_user_id) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),?,'P3','P3',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),'IN_PROGRESS',7,?,?,9)",ticket,"T1",create,1L,2L,3L,4L,"t","d","C","CONSULTATION","n","1","{}",round,assignment,8L);
  db.update("INSERT INTO ticket_assignment(id,ticket_id,round_id,tenant_id,department_id,engineer_user_id,assigned_by,note,created_at) VALUES(?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))",assignment,ticket,round,1L,8L,9L,10L,"n");
  db.update("INSERT INTO ticket_finance_quote_fence(freeze_id,tenant_id,actor_user_id,company_id,fault_relation_id,ticket_id,round_id,group_id,expected_group_version,quote_id,operation_id,command_digest,generation,required_owners,recorded_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?, 'approval,stock',UTC_TIMESTAMP(3))",id(),1L,9L,3L,ticket,ticket,round,group,1L,id(),id(),"d".repeat(64),1L);
  db.update("INSERT INTO ticket_fee_notification_outbox(event_id,tenant_id,event_type,aggregate_id,aggregate_version,fee_group_id,generation,ticket_id,round_id,assignment_id,company_id,department_id,applicant_user_id,envelope,event_digest,delivery_state,attempt_count,next_attempt_at,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PENDING',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",event,1L,"ticket.fee_execution_recorded",receipt,7L,group,1L,ticket,round,assignment,3L,8L,2L,"{}","d".repeat(64));
  var b=new LinkedHashMap<String,Object>();b.put("eventId",event);b.put("tenantId","1");b.put("eventType","ticket.fee_execution_recorded");b.put("aggregateId",receipt);b.put("aggregateVersion","7");b.put("feeGroupId",group);b.put("generation","1");b.put("purpose","DELIVERY");var r=new MockHttpServletRequest();r.setSecure(true);r.setAttribute("ias.chain.edge","notification-ticket");
  try(var chain=mockStatic(Chain.class,CALLS_REAL_METHODS)){chain.when(Chain::active).thenReturn(true);chain.when(Chain::role).thenReturn("ticket");var c=new FeeNotificationContextController(ds);assertEquals("3",c.context(b,r).get("companyId"));db.update("UPDATE ticket_record SET assignment_id=NULL,department_id=NULL,engineer_user_id=NULL WHERE id=?",ticket);assertEquals(409,assertThrows(com.ias.trust.Problem.class,()->c.context(b,r)).status);}
 }
 static String id(){return UUID.randomUUID().toString();}
}
