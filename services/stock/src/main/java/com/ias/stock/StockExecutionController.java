package com.ias.stock;

import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.sql.SQLException;
import java.util.*;
import static com.ias.stock.StockExecutions.*;

@RestController
public final class StockExecutionController {
 private final StockExecutions ledger;private final TrustedStockExecutions access;private final StockExecutionRuntime.Coordinator coordinator;private final JdbcTemplate db;
 public StockExecutionController(StockExecutions l,TrustedStockExecutions a,StockExecutionRuntime.Coordinator c,JdbcTemplate db){ledger=l;access=a;coordinator=c;this.db=db;}
 @PostMapping("/api/stock/warehouses/{warehouseId}/orders/{orderId}/{action:reserve|issue|return}")
 Result warehouse(@PathVariable String warehouseId,@PathVariable String orderId,@PathVariable String action,@RequestBody Map<String,Object> body,HttpServletRequest r)throws SQLException{return perform(r,StockController.id(warehouseId),UUID.fromString(StockController.uuid(orderId)),Action.valueOf(action.toUpperCase(Locale.ROOT)),body);}
 @PostMapping("/api/stock/engineering/orders/{orderId}/uses")
 Result use(@PathVariable String orderId,@RequestBody Map<String,Object> body,HttpServletRequest r)throws SQLException{UUID id=UUID.fromString(StockController.uuid(orderId));var a=access.actor(r,true,true,0);return perform(r,warehouse(id,a.tenantId()),id,Action.USE,body);}
 private Result perform(HttpServletRequest r,long warehouse,UUID id,Action action,Map<String,Object> body)throws SQLException{
  StockController.query(r,Set.of());boolean account=action==Action.USE||action==Action.RETURN;if(!body.keySet().equals(account?Set.of("expectedVersion","lines"):Set.of("expectedVersion")))throw StockController.bad();
  var actor=access.actor(r,action==Action.USE,true,warehouse);r.setAttribute("ias.stock.warehouse",warehouse);var lines=new TreeMap<Long,Long>();
  if(account){if(!(body.get("lines")instanceof List<?> values)||values.isEmpty()||values.size()>50)throw StockController.bad();for(Object value:values){if(!(value instanceof Map<?,?> row)||!row.keySet().equals(Set.of("partId","quantity")))throw StockController.bad();if(lines.put(StockController.id(row.get("partId")),StockController.id(row.get("quantity")))!=null)throw StockController.bad();}}
  var command=new Command(UUID.fromString(StockController.uuid(Chain.single(r,"Idempotency-Key"))),id,action,version(body.get("expectedVersion")),lines);
  Result result;try{result=ledger.execute(actor,command);}catch(Problem e){if(e.status<500){try{coordinator.complete(actor.tenantId(),command.operationId());}catch(Problem ignored){/* no permit/no durable fact, or owner retry remains pending */}}throw e;}
  coordinator.complete(actor.tenantId(),command.operationId());if(result.state().startsWith("REJECTED:"))throw new Problem(409,result.state().substring(9));return result;
 }
 @GetMapping("/api/stock/warehouses/{warehouseId}/orders/{orderId}") Map<String,Object> order(@PathVariable String warehouseId,@PathVariable String orderId,HttpServletRequest r){return order(r,StockController.id(warehouseId),UUID.fromString(StockController.uuid(orderId)),false);}
 @GetMapping("/api/stock/engineering/orders/{orderId}") Map<String,Object> engineerOrder(@PathVariable String orderId,HttpServletRequest r){UUID id=UUID.fromString(StockController.uuid(orderId));var a=access.actor(r,true,false,0);return order(r,warehouse(id,a.tenantId()),id,true);}
 private Map<String,Object> order(HttpServletRequest r,long warehouse,UUID id,boolean engineer){StockController.query(r,Set.of());var a=access.actor(r,engineer,false,warehouse);access.context(r,a,id,warehouse,engineer?"ENGINEER_READ":"READ");var rows=db.queryForList("SELECT state,row_version,expires_at FROM stock_execution_order WHERE tenant_id=? AND warehouse_id=? AND order_id=?",a.tenantId(),warehouse,id.toString());if(rows.isEmpty())throw new Problem(404,"NOT_FOUND");var row=rows.getFirst();var out=new LinkedHashMap<String,Object>();out.put("orderId",id.toString());out.put("warehouseId",Long.toString(warehouse));out.put("state",row.get("state"));out.put("version",row.get("row_version").toString());out.put("expiresAt",row.get("expires_at")==null?null:((java.sql.Timestamp)row.get("expires_at")).toInstant().toString());out.put("lines",db.queryForList("SELECT CAST(part_id AS CHAR) AS partId,CAST(quantity AS CHAR) AS quantity,CAST(issued AS CHAR) AS issued,CAST(used AS CHAR) AS used,CAST(returned AS CHAR) AS returned FROM stock_execution_line WHERE tenant_id=? AND order_id=? ORDER BY part_id",a.tenantId(),id.toString()));return out;}
 @GetMapping("/api/stock/warehouses/{warehouseId}/execution-operations/{operationId}") Result original(@PathVariable String warehouseId,@PathVariable String operationId,HttpServletRequest r)throws SQLException{return original(r,StockController.id(warehouseId),UUID.fromString(StockController.uuid(operationId)),false);}
 @GetMapping("/api/stock/engineering/execution-operations/{operationId}") Result engineerOriginal(@PathVariable String operationId,HttpServletRequest r)throws SQLException{return original(r,0,UUID.fromString(StockController.uuid(operationId)),true);}
 private Result original(HttpServletRequest r,long warehouse,UUID operation,boolean engineer)throws SQLException{StockController.query(r,Set.of());var a=access.actor(r,engineer,false,warehouse);return ledger.original(a,operation,id->access.context(r,a,id,engineer?warehouse(id,a.tenantId()):warehouse,engineer?"ENGINEER_READ":"READ")).orElseThrow(()->new Problem(404,"OPERATION_NOT_FOUND"));}
 private long warehouse(UUID id,long tenant){var rows=db.queryForList("SELECT warehouse_id FROM stock_execution_order WHERE tenant_id=? AND order_id=?",tenant,id.toString());if(rows.size()!=1)throw new Problem(404,"NOT_FOUND");return ((Number)rows.getFirst().get("warehouse_id")).longValue();}
 private static long version(Object value){try{if(!(value instanceof String s)||!s.matches("0|[1-9][0-9]{0,18}"))throw new Exception();return Long.parseLong(s);}catch(Exception e){throw StockController.bad();}}
 @ExceptionHandler(Problem.class)ResponseEntity<Map<String,Object>> problem(Problem e){return ResponseEntity.status(e.status).header("Cache-Control","no-store").body(Map.of("code",e.code,"retryable",e.status==503));}
 @ExceptionHandler({SQLException.class,org.springframework.dao.DataAccessException.class})ResponseEntity<Map<String,Object>> unknown(Exception e){return problem(new Problem(503,"OPERATION_RESULT_UNKNOWN"));}
}
