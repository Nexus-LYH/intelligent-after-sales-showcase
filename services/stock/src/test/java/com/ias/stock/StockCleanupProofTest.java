package com.ias.stock;

import org.junit.jupiter.api.Test;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import com.ias.trust.Problem;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StockCleanupProofTest {
 private static Map<String,Object> line(long part,long quantity,long issued,long used,long returned){return Map.of("part_id",part,"quantity",quantity,"issued",issued,"used",used,"returned",returned);}
 private final SortedMap<Long,Long> approved=new TreeMap<>(Map.of(11L,2L));
 @Test void acceptsAccountedAndEntirelyReturnedOrders(){assertTrue(StockCleanupProof.settled("ACCOUNTED",approved,List.of(line(11,2,2,1,1))));assertTrue(StockCleanupProof.settled("RETURNED",approved,List.of(line(11,2,2,0,2))));}
 @Test void refusesReservationsMissingLinesAndUnaccountedQuantities(){for(String state:List.of("WAITING_STOCK","RESERVED","EXPIRED","ISSUED"))assertFalse(StockCleanupProof.settled(state,approved,List.of(line(11,2,2,1,1))));assertFalse(StockCleanupProof.settled("ACCOUNTED",approved,List.of()));assertFalse(StockCleanupProof.settled("ACCOUNTED",approved,List.of(line(11,2,2,1,0))));}
 @Test void refusesMismatchedApprovalQuantitiesOrParts(){assertFalse(StockCleanupProof.settled("ACCOUNTED",approved,List.of(line(12,2,2,1,1))));assertFalse(StockCleanupProof.settled("ACCOUNTED",approved,List.of(line(11,3,3,2,1))));assertFalse(StockCleanupProof.settled("ACCOUNTED",approved,List.of(line(11,2,1,1,0))));assertFalse(StockCleanupProof.settled("RETURNED",approved,List.of(line(11,2,2,1,1))));}
 @Test void readsPersistedOwnerFactsAndRejectsCrossTicketScope(){
  var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:cleanup"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");var db=new JdbcTemplate(source);
  db.execute("CREATE TABLE stock_execution_order(tenant_id BIGINT,order_id VARCHAR(36),approval_json CLOB,state VARCHAR(24),row_version BIGINT)");db.execute("CREATE TABLE stock_execution_line(tenant_id BIGINT,order_id VARCHAR(36),part_id BIGINT,quantity BIGINT,issued BIGINT,used BIGINT,returned BIGINT)");
  var id=UUID.randomUUID();String ticket=UUID.randomUUID().toString(),round=UUID.randomUUID().toString(),assignment=UUID.randomUUID().toString();
  var snapshot=new StockExecutions.Approval(id,1,7,2,ticket,round,assignment,3,Instant.parse("2026-01-01T00:00:00Z"),"FREE",approved);
  db.update("INSERT INTO stock_execution_order VALUES(?,?,?,?,?)",7,id.toString(),JsonMapper.builder().build().writeValueAsString(snapshot),"ACCOUNTED",4);
  db.update("INSERT INTO stock_execution_line VALUES(?,?,?,?,?,?,?)",7,id.toString(),11,2,2,1,1);
  var binding=Map.<String,Object>of("tenantId","7","ticketId",ticket,"roundId",round,"assignmentId",assignment,"approvalId",id.toString());var owner=new StockCleanupProof(db);
  assertEquals(true,owner.observe(binding).get("complete"));var wrong=new HashMap<>(binding);wrong.put("ticketId",UUID.randomUUID().toString());assertEquals("STOCK_CLEANUP_SCOPE_MISMATCH",assertThrows(Problem.class,()->owner.observe(wrong)).code);
  db.update("UPDATE stock_execution_line SET returned=0");assertEquals(false,owner.observe(binding).get("complete"));
  db.update("UPDATE stock_execution_line SET returned=1");
  db.execute("CREATE TABLE stock_execution_operation(tenant_id BIGINT,operation_id VARCHAR(36),order_id VARCHAR(36),action_name VARCHAR(24))");db.execute("CREATE TABLE stock_execution_movement(tenant_id BIGINT,operation_id VARCHAR(36),part_id BIGINT,used_delta BIGINT)");db.execute("CREATE TABLE stock_finance_part_execution(tenant_id BIGINT,order_id VARCHAR(36),use_operation_id VARCHAR(36),part_id BIGINT,quantity BIGINT,ticket_id VARCHAR(36),round_id VARCHAR(36))");
  var paid=new StockExecutions.Approval(id,1,7,2,ticket,round,assignment,3,Instant.parse("2026-01-01T00:00:00Z"),"CUSTOMER_PAID",approved);db.update("UPDATE stock_execution_order SET approval_json=?",JsonMapper.builder().build().writeValueAsString(paid));String use=UUID.randomUUID().toString();db.update("INSERT INTO stock_execution_operation VALUES(?,?,?,?)",7,use,id.toString(),"USE");db.update("INSERT INTO stock_execution_movement VALUES(?,?,?,?)",7,use,11,1);
  assertEquals(false,owner.observe(binding).get("complete"));db.update("INSERT INTO stock_finance_part_execution VALUES(?,?,?,?,?,?,?)",7,id.toString(),use,11,1,ticket,round);assertEquals(true,owner.observe(binding).get("complete"));db.update("UPDATE stock_finance_part_execution SET quantity=2");assertEquals(false,owner.observe(binding).get("complete"));db.update("DELETE FROM stock_execution_movement");assertEquals(false,owner.observe(binding).get("complete"));
 }
}
