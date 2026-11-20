package com.ias.approval;

import com.ias.trust.Chain;
import com.ias.trust.Problem;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StockExecutionFeeBindingTest {
 JdbcTemplate db;String ticket,round,assignment,partApproval,feeApproval;Map<String,Object> approved;
 @BeforeEach void setup(){
  var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");db=new JdbcTemplate(source);
  db.execute("CREATE TABLE approval_finance_generation(tenant_id BIGINT,ticket_id VARCHAR(36),generation BIGINT,state VARCHAR(20))");
  db.execute("CREATE TABLE fee_approval_application(tenant_id BIGINT,id VARCHAR(36),ticket_id VARCHAR(36),state VARCHAR(24),snapshot CLOB)");
  db.execute("CREATE TABLE fee_approval_binding(tenant_id BIGINT,application_id VARCHAR(36),lines_json CLOB,ticket_context CLOB)");
  db.execute("CREATE TABLE fee_approval_completion(tenant_id BIGINT,application_id VARCHAR(36))");db.execute("CREATE TABLE fee_approval_termination(tenant_id BIGINT,application_id VARCHAR(36))");
  db.execute("CREATE TABLE approval_finance_write_intent(tenant_id BIGINT,actor_id BIGINT,operation_id VARCHAR(36),generation BIGINT,charge_mode VARCHAR(24),action VARCHAR(24))");db.execute("CREATE TABLE approval_operation(tenant_id BIGINT,actor_id BIGINT,operation_id VARCHAR(36),target_id VARCHAR(36),action VARCHAR(24),receipt VARCHAR(100))");
  ticket=UUID.randomUUID().toString();round=UUID.randomUUID().toString();assignment=UUID.randomUUID().toString();partApproval=UUID.randomUUID().toString();feeApproval=UUID.randomUUID().toString();
  approved=Map.of("approvalId",partApproval,"approvalVersion","1","ticketId",ticket,"roundId",round,"assignmentId",assignment,"applicantUserId","4","warehouseId","5","lines",List.of(Map.of("partId","6","quantity","2")));
  db.update("INSERT INTO approval_finance_generation VALUES(?,?,?,?)",1,ticket,1,"OPEN");
  db.update("INSERT INTO fee_approval_application VALUES(?,?,?,?,?)",1,feeApproval,ticket,"COMPLETED",json(Map.of("basis",Map.of("generation",1))));
  db.update("INSERT INTO fee_approval_binding VALUES(?,?,?,?)",1,feeApproval,json(List.of(Map.of("kind","PART","ticketId",ticket,"roundId",round,"partBinding",Map.of("approvalId",partApproval,"approvalVersion","1","warehouseId","5","partId","6","quantity","2")))),json(Map.of("assignmentId",assignment,"engineerUserId","4")));
  db.update("INSERT INTO fee_approval_completion VALUES(?,?)",1,feeApproval);
 }
 static String json(Object value){return Chain.JSON.writeValueAsString(value);}
 void verify(){StockExecutionContext.requireExecutionGeneration(db,"1",partApproval,ticket,approved);}
 @Test void completedCurrentFeeCanBindDifferentApplicationId(){assertNotEquals(partApproval,feeApproval);assertDoesNotThrow(this::verify);}
 @Test void staleWrongScopeOrTerminatedFeeNeverGrantsExecution(){
  db.update("UPDATE approval_finance_generation SET generation=2");assertThrows(Problem.class,this::verify);db.update("UPDATE approval_finance_generation SET generation=1");
  db.update("UPDATE fee_approval_binding SET ticket_context=?",json(Map.of("assignmentId",UUID.randomUUID().toString(),"engineerUserId","4")));assertThrows(Problem.class,this::verify);
  db.update("UPDATE fee_approval_binding SET ticket_context=?",json(Map.of("assignmentId",assignment,"engineerUserId","4")));db.update("INSERT INTO fee_approval_termination VALUES(?,?)",1,feeApproval);assertThrows(Problem.class,this::verify);
 }
 @Test void wrongPartVersionOrQuantityCannotBeCovered(){
  var wrong=new HashMap<>(approved);wrong.put("approvalVersion","2");assertThrows(Problem.class,()->StockExecutionContext.requireExecutionGeneration(db,"1",partApproval,ticket,wrong));
  wrong.put("approvalVersion","1");wrong.put("lines",List.of(Map.of("partId","6","quantity","3")));assertThrows(Problem.class,()->StockExecutionContext.requireExecutionGeneration(db,"1",partApproval,ticket,wrong));
 }
}
