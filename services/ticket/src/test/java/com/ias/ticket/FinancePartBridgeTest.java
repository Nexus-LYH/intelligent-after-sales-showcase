package com.ias.ticket;
import com.ias.trust.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FinancePartBridgeTest {
 String ticket=UUID.randomUUID().toString(),round=UUID.randomUUID().toString(),assignment=UUID.randomUUID().toString(),use=UUID.randomUUID().toString(),op=UUID.randomUUID().toString();
 JdbcTemplate db;FinancePartBridge bridge;Tickets.Actor actor;int calls;Map<String,Object> body,row;boolean unknown;
 @BeforeEach void setup(){
  var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");db=new JdbcTemplate(ds);
  db.execute("CREATE TABLE ticket_stock_execution(operation_id VARCHAR(36),tenant_id BIGINT,actor_user_id BIGINT,ticket_id VARCHAR(36),round_id VARCHAR(36),assignment_id VARCHAR(36),warehouse_id BIGINT,phase VARCHAR(10))");db.execute("CREATE TABLE ticket_stock_execution_result(operation_id VARCHAR(36))");
  db.update("INSERT INTO ticket_stock_execution VALUES(?,?,?,?,?,?,?,'USE')",use,7,42,ticket,round,assignment,3);db.update("INSERT INTO ticket_stock_execution_result VALUES(?)",use);
  row=new HashMap<>(Map.of("status","IN_PROGRESS","company_id",9,"round_id",round,"assignment_id",assignment));actor=new Tickets.Actor(7,42,"EMPLOYEE","session","csrf","trace",System.currentTimeMillis()+10000,"");
  Tickets t=new Tickets(db,null){@Override Map<String,Object> accessible(String id,Actor a){return row;}};
  bridge=new FinancePartBridge(t,new StaffAccess(),(method,path,b,h,a)->{calls++;if(unknown)throw new Problem(503,"DEPENDENCY_UNAVAILABLE");if(method.equals("POST")){assertEquals("session",b.get("sessionToken"));assertEquals(op,h.get("Idempotency-Key"));assertEquals("csrf",h.get("X-CSRF-TOKEN"));}else assertEquals("session",h.get("X-Session-Token"));return Map.of("ticketId",ticket,"operationId",op,"state","EXECUTED");},(id,r,w)->actor);
  body=new HashMap<>();for(String k:List.of("groupId","completionId","quoteId","lineId","orderId"))body.put(k,UUID.randomUUID().toString());body.putAll(Map.of("operationId",op,"useOperationId",use,"quoteVersion","1","generation","1","partId","2","quantity","1","amountCents","100","warehouseId","3","companyId","9"));
 }
 MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.addHeader("Idempotency-Key",op);r.addHeader("X-CSRF-TOKEN","csrf");return r;}
 @Test void postFailsClosedUntilTransferFenceExistsAndOriginalReadStillWorks(){assertEquals(503,assertThrows(Problem.class,()->bridge.execute(ticket,Chain.JSON.writeValueAsString(body),request())).status);assertEquals(0,calls);assertEquals("EXECUTED",bridge.operation(ticket,op,request()).get("state"));assertEquals(1,calls);}
 @Test void wrongUseAssignmentCompanyOrInputNeverCallsStock(){db.update("UPDATE ticket_stock_execution SET assignment_id=?",UUID.randomUUID().toString());assertEquals(409,assertThrows(Problem.class,()->bridge.execute(ticket,Chain.JSON.writeValueAsString(body),request())).status);assertEquals(0,calls);}
 @Test void unknownReadDoesNotAutomaticallyRetry(){unknown=true;assertEquals(503,assertThrows(Problem.class,()->bridge.operation(ticket,op,request())).status);assertEquals(1,calls);}
}
