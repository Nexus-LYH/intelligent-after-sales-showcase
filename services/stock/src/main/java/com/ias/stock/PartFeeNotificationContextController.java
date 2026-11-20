package com.ias.stock;

import com.ias.trust.*;
import jakarta.servlet.http.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;

/** Exact owner context for immutable USE-backed PART fee events. */
@RestController
public final class PartFeeNotificationContextController {
 private static final Set<String> FIELDS=Set.of("eventId","tenantId","eventType","companyId","ticketId","roundId","feeGroupId","generation","executionReceiptId","lineId","warehouseId","partId","quantity","amountCents","purpose");
 private final JdbcTemplate db;
 public PartFeeNotificationContextController(DataSource source){db=new JdbcTemplate(source);}
 @PostMapping("/internal/stock/part-fee-notification-context") Map<String,Object> context(@RequestBody Map<String,Object>b,HttpServletRequest r){
  trusted(r);if(b==null||!b.keySet().equals(FIELDS)||!"DELIVERY".equals(text(b,"purpose"))||!"inventory.consumed".equals(text(b,"eventType")))throw bad();
  String event=uuid(b,"eventId"),tenant=positive(b,"tenantId"),company=positive(b,"companyId"),ticket=uuid(b,"ticketId"),round=uuid(b,"roundId"),group=uuid(b,"feeGroupId"),generation=positive(b,"generation"),receipt=uuid(b,"executionReceiptId"),line=uuid(b,"lineId"),warehouse=positive(b,"warehouseId"),part=positive(b,"partId"),quantity=positive(b,"quantity"),amount=positive(b,"amountCents");
  var rows=db.queryForList("SELECT o.*,e.use_operation_id,e.coverage_digest,e.actor_user_id,g.generation current_generation,g.active_freeze_id,w.active,op.action_name,m.used_delta FROM stock_part_fee_notification_outbox o JOIN stock_finance_part_execution e ON e.tenant_id=o.tenant_id AND e.execution_receipt_id=o.execution_receipt_id AND e.company_id=o.company_id AND e.group_id=o.fee_group_id AND e.generation=o.generation AND e.line_id=o.line_id AND e.ticket_id=o.ticket_id AND e.round_id=o.round_id AND e.warehouse_id=o.warehouse_id AND e.part_id=o.part_id AND e.quantity=o.quantity AND e.amount_cents=o.amount_cents JOIN stock_finance_generation g ON g.tenant_id=e.tenant_id AND g.ticket_id=e.ticket_id JOIN stock_warehouse w ON w.tenant_id=e.tenant_id AND w.id=e.warehouse_id JOIN stock_execution_operation op ON op.tenant_id=e.tenant_id AND op.operation_id=e.use_operation_id AND op.order_id=e.order_id JOIN stock_execution_movement m ON m.tenant_id=op.tenant_id AND m.operation_id=op.operation_id AND m.part_id=e.part_id WHERE o.event_id=? AND o.tenant_id=?",event,Long.parseLong(tenant));
  if(rows.size()!=1)throw new Problem(404,"PART_FEE_NOTIFICATION_FACT_NOT_FOUND");var row=rows.getFirst();exact(row,"event_type","inventory.consumed");exact(row,"company_id",company);exact(row,"ticket_id",ticket);exact(row,"round_id",round);exact(row,"fee_group_id",group);exact(row,"generation",generation);exact(row,"execution_receipt_id",receipt);exact(row,"line_id",line);exact(row,"warehouse_id",warehouse);exact(row,"part_id",part);exact(row,"quantity",quantity);exact(row,"amount_cents",amount);
  boolean current=generation.equals(Objects.toString(row.get("current_generation"),""))&&row.get("active_freeze_id")==null&&Boolean.TRUE.equals(row.get("active"))&&"USE".equals(row.get("action_name"))&&quantity.equals(Objects.toString(row.get("used_delta"),""));if(!current)throw new Problem(409,"PART_FEE_NOTIFICATION_SCOPE_REVOKED");
  var out=new LinkedHashMap<String,Object>();for(String key:FIELDS)if(!key.equals("purpose"))out.put(key,b.get(key));out.put("actorUserId",row.get("actor_user_id").toString());out.put("coverageDigest",row.get("coverage_digest"));out.put("factVersion",generation);out.put("eventVersion","1");out.put("currentScope",true);out.put("qualified",true);out.put("revoked",false);out.put("checkedAt",Instant.now().toString());return out;
 }
 private static void trusted(HttpServletRequest r){if(!Chain.active()||!"stock".equals(Chain.role())||!r.isSecure()||!"notification-stock".equals(r.getAttribute("ias.chain.edge"))||!r.getParameterMap().isEmpty())throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");}
 private static void exact(Map<String,Object>r,String key,String expected){if(!expected.equals(Objects.toString(r.get(key),"")))throw new Problem(409,"PART_FEE_NOTIFICATION_FACT_STALE");}
 private static String text(Map<String,Object>b,String key){if(!(b.get(key) instanceof String s)||s.isBlank())throw bad();return s;}
 private static String uuid(Map<String,Object>b,String key){String s=text(b,key);try{if(!UUID.fromString(s).toString().equals(s))throw bad();return s;}catch(IllegalArgumentException e){throw bad();}}
 private static String positive(Map<String,Object>b,String key){String s=text(b,key);if(!s.matches("[1-9][0-9]{0,18}"))throw bad();return s;}
 private static Problem bad(){return new Problem(400,"INVALID_REQUEST");}
 @ExceptionHandler(Problem.class)ResponseEntity<Map<String,Object>> problem(Problem p){return ResponseEntity.status(p.status).header("Cache-Control","no-store").body(Map.of("code",p.code,"retryable",p.status==503));}
 @ExceptionHandler(Exception.class)ResponseEntity<Map<String,Object>> unknown(Exception e){return problem(new Problem(503,"DEPENDENCY_UNAVAILABLE"));}
}
