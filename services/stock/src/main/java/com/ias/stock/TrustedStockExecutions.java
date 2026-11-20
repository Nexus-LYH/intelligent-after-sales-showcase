package com.ias.stock;

import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.*;
import java.time.Instant;
import java.util.*;
import static com.ias.stock.StockExecutions.*;

public final class TrustedStockExecutions implements StockExecutions.Access {
 @FunctionalInterface interface Peer {Map<String,Object> call(String edge,String path,Map<String,Object> body,HttpServletRequest r);}
 private final StockAccess warehouse;private final org.springframework.jdbc.core.JdbcTemplate db;private final Peer peer;
 public TrustedStockExecutions(StockAccess warehouse){this(warehouse,null);}
 public TrustedStockExecutions(StockAccess warehouse,org.springframework.jdbc.core.JdbcTemplate db){this(warehouse,db,(edge,path,body,r)->{var fact=Chain.call(edge,path,body,Chain.trace(r),Chain.deadline(r));Chain.fresh(fact,Chain.deadline(r));return fact;});}
 TrustedStockExecutions(StockAccess warehouse,org.springframework.jdbc.core.JdbcTemplate db,Peer peer){this.warehouse=warehouse;this.db=db;this.peer=peer;}
 static HttpServletRequest request(){return ((ServletRequestAttributes)RequestContextHolder.currentRequestAttributes()).getRequest();}
 public Actor actor(HttpServletRequest r,boolean engineer,boolean write,long warehouseId){
  if(!engineer){var p=write?warehouse.manage(r,warehouseId):warehouse.balance(r,warehouseId);if(!p.warehouseIds().contains(warehouseId))throw new Problem(404,"NOT_FOUND");return new Actor(p.tenantId(),p.userId());}
  var p=Chain.identity("stock-identity",Chain.cookie(r),write?"TICKET_WORK":"TICKET_READ",write?r.getHeader("X-CSRF-TOKEN"):null,Chain.trace(r),Chain.deadline(r));Chain.fresh(p,Chain.deadline(r));
  if(!"EMPLOYEE".equals(p.get("identityType")))throw new Problem(403,"ACCESS_DENIED");return new Actor(TrustedStockAccess.id(p.get("tenantId")),TrustedStockAccess.id(p.get("userId")));
 }
 public Approval context(HttpServletRequest r,Actor a,UUID id,long warehouseId,String phase){
  var body=new HashMap<String,Object>();body.put("sessionToken",Chain.cookie(r));body.put("approvalId",id.toString());body.put("warehouseId",Long.toString(warehouseId));body.put("phase",phase);
  var fact=Chain.call("stock-approval","/internal/approval/stock-context",body,Chain.trace(r),Chain.deadline(r));Chain.fresh(fact,Chain.deadline(r));
  if(a.tenantId()!=TrustedStockAccess.id(fact.get("tenantId"))||a.userId()!=TrustedStockAccess.id(fact.get("userId"))||!id.toString().equals(fact.get("approvalId"))||warehouseId!=TrustedStockAccess.id(fact.get("warehouseId")))throw new Problem(404,"NOT_FOUND");
  try{var quantities=new TreeMap<Long,Long>();if(!(fact.get("lines")instanceof List<?> lines)||lines.isEmpty()||lines.size()>50)throw new Exception();for(Object raw:lines){if(!(raw instanceof Map<?,?> line))throw new Exception();long p=TrustedStockAccess.id(line.get("partId")),q=TrustedStockAccess.id(line.get("quantity"));if(quantities.put(p,q)!=null)throw new Exception();}
   return new Approval(id,Long.parseLong(fact.get("approvalVersion").toString()),a.tenantId(),warehouseId,StockController.uuid(fact.get("ticketId")),StockController.uuid(fact.get("roundId")),StockController.uuid(fact.get("assignmentId")),TrustedStockAccess.id(fact.get("applicantUserId")),Instant.parse(fact.get("approvedAt").toString()),fact.get("chargeMode").toString(),quantities);
  }catch(Exception ex){throw new Problem(503,"INVALID_APPROVAL_EXECUTION_FACT");}
 }
 public Approval authorize(Actor actor,Command command){
  var r=request();boolean engineer=command.action()==Action.USE;String path=r.getRequestURI();long w=warehouseId(path);
  var current=actor(r,engineer,true,w);if(!actor.equals(current))throw new Problem(401,"SESSION_INVALID");
  if(engineer){Object resolved=r.getAttribute("ias.stock.warehouse");if(!(resolved instanceof Long))throw new Problem(503,"STOCK_CONTEXT_REQUIRED");w=(Long)resolved;}
  boolean known=db!=null&&db.queryForObject("SELECT COUNT(*) FROM stock_execution_operation WHERE tenant_id=? AND actor_id=? AND operation_id=? AND order_id=? AND request_hash=?",Long.class,actor.tenantId(),actor.userId(),command.operationId().toString(),command.orderId().toString(),StockExecutions.digest(command))==1;
  String phase=known?(engineer?"ENGINEER_READ":"READ"):engineer?"USE":Set.of(Action.RESERVE,Action.ISSUE).contains(command.action())?command.action().name():"READ";
  var approval=context(r,actor,command.orderId(),w,phase);
  if(!known&&(command.action()==Action.ISSUE||command.action()==Action.USE)&&!Set.of("FREE","CUSTOMER_PAID").contains(approval.chargeMode()))throw new Problem(503,approval.chargeMode().equals("UNKNOWN")?"EXPLICIT_CHARGE_POLICY_REQUIRED":"PAID_EXECUTION_AUTHORITY_NOT_AVAILABLE");
  var body=new LinkedHashMap<String,Object>();body.put("sessionToken",Chain.cookie(r));body.put("csrfToken",Chain.single(r,"X-CSRF-TOKEN"));body.put("operationId",command.operationId().toString());body.put("commandDigest",StockExecutions.digest(command));body.put("ticketId",approval.ticketId());body.put("roundId",approval.roundId());body.put("assignmentId",approval.assignmentId());body.put("approvalId",approval.id().toString());body.put("warehouseId",Long.toString(w));body.put("phase",command.action().name());
  var permit=Chain.call("stock-ticket","/internal/ticket/stock-executions/prepare",body,Chain.trace(r),Chain.deadline(r));Chain.fresh(permit,Chain.deadline(r));
  for(String k:List.of("operationId","commandDigest","ticketId","roundId","assignmentId","approvalId","warehouseId","phase"))if(!Objects.equals(body.get(k),permit.get(k)))throw new Problem(503,"STOCK_PERMIT_MISMATCH");
  if(!Set.of("OPEN","COMPLETED").contains(permit.get("state")))throw new Problem(503,"STOCK_PERMIT_UNCONFIRMED");return approval;
 }
 @Override public void requirePaidCoverage(Actor actor,Command command,Approval approval){
  if(!"CUSTOMER_PAID".equals(approval.chargeMode()))throw new Problem(503,"PAID_EXECUTION_AUTHORITY_NOT_AVAILABLE");
  var quantities=command.action()==Action.ISSUE?approval.quantities():command.quantities();
  if(quantities.isEmpty())throw new Problem(503,"PAID_COVERAGE_UNAVAILABLE");
  for(var entry:quantities.entrySet()){
   var body=new LinkedHashMap<String,Object>();body.put("tenantId",Long.toString(actor.tenantId()));body.put("approvalId",approval.id().toString());body.put("ticketId",approval.ticketId());body.put("warehouseId",Long.toString(approval.warehouseId()));body.put("partId",Long.toString(entry.getKey()));body.put("quantity",Long.toString(entry.getValue()));
   var fact=peer.call("stock-approval","/internal/approval/paid-coverage",body,request());
   if(!Long.toString(actor.tenantId()).equals(fact.get("tenantId"))||!approval.id().toString().equals(fact.get("approvalId"))||!approval.ticketId().equals(fact.get("ticketId"))||!Long.toString(approval.warehouseId()).equals(fact.get("warehouseId"))||!Long.toString(entry.getKey()).equals(fact.get("partId"))||!Long.toString(entry.getValue()).equals(fact.get("quantity"))||!(fact.get("coverageDigest") instanceof String digest)||!digest.matches("[0-9a-f]{64}"))throw new Problem(503,"PAID_COVERAGE_MISMATCH");
  }
 }
 static long warehouseId(String path){var parts=path.split("/");if(parts.length>4&&parts[3].equals("warehouses"))return StockController.id(parts[4]);return 0;}
}
