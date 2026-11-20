package com.ias.stock;

import com.ias.trust.Chain;
import com.ias.trust.Problem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.*;

/** Stock-owned observation; ticket must hold its row lock and reject any in-flight permit. */
@RestController
public final class StockCleanupProof {
 private static final JsonMapper JSON=JsonMapper.builder().build();
 private final JdbcTemplate db;
 public StockCleanupProof(JdbcTemplate db){this.db=db;}

 @PostMapping("/internal/stock/parts-cleanup-proof")
 public Map<String,Object> proof(@RequestBody Map<String,Object> body,HttpServletRequest request){
  if(!Chain.active()||!"stock".equals(Chain.role())||!request.isSecure()||!"ticket-stock".equals(request.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");
  if(!request.getParameterMap().isEmpty())throw StockController.bad();return observe(body);
 }
 Map<String,Object> observe(Map<String,Object> body){
  if(!body.keySet().equals(Set.of("tenantId","ticketId","roundId","assignmentId","approvalId")))throw StockController.bad();
  long tenant=StockController.id(body.get("tenantId"));
  String ticket=StockController.uuid(body.get("ticketId")),round=StockController.uuid(body.get("roundId")),assignment=StockController.uuid(body.get("assignmentId")),approval=StockController.uuid(body.get("approvalId"));
  var orders=db.queryForList("SELECT approval_json,state,row_version FROM stock_execution_order WHERE tenant_id=? AND order_id=?",tenant,approval);
  boolean complete=false;String version=null;
  if(orders.size()==1){
   var row=orders.getFirst();StockExecutions.Approval snapshot;
   try{snapshot=JSON.readValue(row.get("approval_json").toString(),StockExecutions.Approval.class);}catch(Exception e){throw new Problem(503,"STOCK_CLEANUP_FACT_INVALID");}
   if(snapshot.tenantId()!=tenant||!snapshot.id().toString().equals(approval)||!snapshot.ticketId().equals(ticket)||!snapshot.roundId().equals(round)||!snapshot.assignmentId().equals(assignment))throw new Problem(503,"STOCK_CLEANUP_SCOPE_MISMATCH");
   var lines=db.queryForList("SELECT part_id,quantity,issued,used,returned FROM stock_execution_line WHERE tenant_id=? AND order_id=? ORDER BY part_id",tenant,approval);
   complete=settled(Objects.toString(row.get("state"),""),snapshot.quantities(),lines);
   if(complete&&snapshot.chargeMode().equals("CUSTOMER_PAID")){
    Long missing=db.queryForObject("SELECT COUNT(*) FROM stock_execution_operation o JOIN stock_execution_movement m ON m.tenant_id=o.tenant_id AND m.operation_id=o.operation_id WHERE o.tenant_id=? AND o.order_id=? AND o.action_name='USE' AND (m.used_delta<=0 OR NOT EXISTS (SELECT 1 FROM stock_finance_part_execution f WHERE f.tenant_id=o.tenant_id AND f.order_id=o.order_id AND f.use_operation_id=o.operation_id AND f.part_id=m.part_id AND f.quantity=m.used_delta AND f.ticket_id=? AND f.round_id=?))",Long.class,tenant,approval,ticket,round);
    Long inconsistent=db.queryForObject("SELECT COUNT(*) FROM stock_execution_line l WHERE l.tenant_id=? AND l.order_id=? AND l.used<>(SELECT COALESCE(SUM(m.used_delta),0) FROM stock_execution_operation o JOIN stock_execution_movement m ON m.tenant_id=o.tenant_id AND m.operation_id=o.operation_id WHERE o.tenant_id=l.tenant_id AND o.order_id=l.order_id AND o.action_name='USE' AND m.part_id=l.part_id)",Long.class,tenant,approval);
    if(missing==null||inconsistent==null)throw new Problem(503,"STOCK_CLEANUP_FACT_INVALID");complete=missing==0&&inconsistent==0;
   }else if(complete&&!snapshot.chargeMode().equals("FREE"))complete=false;
   version=row.get("row_version").toString();
  }
  var result=new LinkedHashMap<String,Object>();result.put("tenantId",Long.toString(tenant));result.put("ticketId",ticket);result.put("roundId",round);result.put("assignmentId",assignment);result.put("approvalId",approval);result.put("complete",complete);result.put("orderVersion",version);result.put("checkedAt",Instant.now().toString());return result;
 }

 static boolean settled(String state,SortedMap<Long,Long> expected,List<Map<String,Object>> lines){
  if(!Set.of("ACCOUNTED","RETURNED").contains(state)||expected.isEmpty()||lines.size()!=expected.size())return false;
  var remaining=new TreeMap<>(expected);boolean anyUsed=false;
  try{
   for(var line:lines){long part=((Number)line.get("part_id")).longValue(),quantity=((Number)line.get("quantity")).longValue(),issued=((Number)line.get("issued")).longValue(),used=((Number)line.get("used")).longValue(),returned=((Number)line.get("returned")).longValue();Long approved=remaining.remove(part);if(approved==null||approved!=quantity||quantity<=0||issued!=quantity||used<0||returned<0||used>issued||returned!=issued-used)return false;if(state.equals("RETURNED")&&used!=0)return false;if(used>0)anyUsed=true;}
  }catch(RuntimeException e){throw new Problem(503,"STOCK_CLEANUP_FACT_INVALID");}
  return remaining.isEmpty()&&(!state.equals("ACCOUNTED")||anyUsed);
 }
}
