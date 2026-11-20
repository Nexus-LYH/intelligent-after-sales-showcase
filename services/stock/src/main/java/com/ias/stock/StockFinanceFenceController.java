package com.ias.stock;

import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import javax.sql.DataSource;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import static com.ias.stock.StockFinanceGeneration.*;

/** Only W can request a fence. Owner authority is read back from W over the reverse trusted edge. */
@RestController
public final class StockFinanceFenceController {
 static final Set<String> FIELDS=Set.of("tenantId","actorUserId","companyId","faultRelationId","ticketId","roundId","groupId","expectedGroupVersion","quoteId","operationId","commandDigest","freezeId","generation","ticketFenceDigest");
 final DataSource source;
 public StockFinanceFenceController(DataSource source){this.source=source;}
 void trust(HttpServletRequest r){if(!Chain.active()||!r.isSecure()||!"ticket-stock".equals(r.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");}
 static String text(Map<String,Object> b,String key){if(!(b.get(key) instanceof String s)||s.isBlank())throw new Problem(400,"INVALID_REQUEST");return s;}
 static long number(Map<String,Object> b,String key,boolean zero){try{String s=text(b,key);if(!s.matches(zero?"0|[1-9][0-9]{0,18}":"[1-9][0-9]{0,18}"))throw new Exception();return Long.parseLong(s);}catch(Exception e){throw new Problem(400,"INVALID_REQUEST");}}
 static UUID id(Map<String,Object> b,String key){try{String s=text(b,key);UUID id=UUID.fromString(s);if(!s.equals(id.toString()))throw new Exception();return id;}catch(Exception e){throw new Problem(400,"INVALID_REQUEST");}}
 static Binding binding(Map<String,Object> b){if(!b.keySet().equals(FIELDS))throw new Problem(400,"INVALID_REQUEST");number(b,"actorUserId",false);if(!hex(text(b,"ticketFenceDigest"))||!id(b,"faultRelationId").equals(id(b,"ticketId")))throw new Problem(400,"INVALID_REQUEST");try{return new Binding(number(b,"tenantId",false),number(b,"companyId",false),id(b,"ticketId"),id(b,"roundId"),id(b,"groupId"),number(b,"expectedGroupVersion",true),id(b,"quoteId"),id(b,"operationId"),text(b,"commandDigest"),id(b,"freezeId"),number(b,"generation",false));}catch(IllegalArgumentException e){throw new Problem(400,"INVALID_REQUEST");}}
 static VerifiedFence proof(Binding binding,Map<String,Object> body,Map<String,Object> fact,boolean terminal,long deadline){Chain.fresh(fact,deadline);for(String field:FIELDS)if(!body.get(field).equals(fact.get(field)))throw new Problem(503,"FINANCE_FENCE_BINDING_MISMATCH");String state=Objects.toString(fact.get("sourceState"),"");if(terminal?!Set.of("COMMITTED","ABORTED").contains(state)||!Set.of("RECONCILING","RELEASED").contains(fact.get("coordinationState")):!state.equals("IN_PROGRESS")||!Set.of("FENCING","FROZEN").contains(fact.get("coordinationState")))throw new Problem(503,"FINANCE_SOURCE_STATE_NOT_CONFIRMED");Object result=fact.get("resultDigest");if(state.equals("COMMITTED")?!(result instanceof String s&&hex(s)):result!=null)throw new Problem(503,"FINANCE_SOURCE_RESULT_MISMATCH");return new VerifiedFence(binding,state,text(body,"ticketFenceDigest"),ids(fact.get("scopedOperationIds")),ids(fact.get("inFlightOperationIds")),(String)result);}
 static List<UUID> ids(Object value){if(!(value instanceof List<?> list)||list.size()>1000)throw new Problem(503,"FINANCE_SCOPE_NOT_CONFIRMED");try{return list.stream().map(o->{if(!(o instanceof String s))throw new IllegalArgumentException();UUID id=UUID.fromString(s);if(!id.toString().equals(s))throw new IllegalArgumentException();return id;}).toList();}catch(Exception e){throw new Problem(503,"FINANCE_SCOPE_NOT_CONFIRMED");}}
 StockFinanceGeneration owner(Binding b,Map<String,Object> body,HttpServletRequest r,boolean terminal){return new StockFinanceGeneration(source,ignored->proof(b,body,Chain.call("stock-ticket","/internal/ticket/finance-quotes/proof",body,Chain.trace(r),Chain.deadline(r)),terminal,Chain.deadline(r)));}
 @PostMapping("/internal/stock/finance-fences/prepare") Map<String,Object> prepare(@RequestBody Map<String,Object> body,HttpServletRequest r)throws SQLException{trust(r);var b=binding(body);var result=owner(b,body,r,false).freeze(b);var out=new LinkedHashMap<>(body);out.put("owner","stock");out.put("state",result.state());out.put("globalFreezeAck",false);out.put("unresolved",result.unresolved());if(result.receipt()!=null){out.put("ackDigest",result.receipt().ackDigest());out.put("coverageDigest",result.receipt().coverageDigest());out.put("localSnapshot",result.receipt().localSnapshot());out.put("recordedAt",result.receipt().recordedAt());}out.put("checkedAt",Instant.now().toString());return out;}
 @PostMapping("/internal/stock/finance-fences/reconcile") Map<String,Object> reconcile(@RequestBody Map<String,Object> body,HttpServletRequest r)throws SQLException{trust(r);var b=binding(body);var receipt=owner(b,body,r,true).release(b);var out=new LinkedHashMap<>(body);out.putAll(receipt);out.put("checkedAt",Instant.now().toString());out.put("globalFreezeAck",false);return out;}
 @ExceptionHandler(Problem.class) ResponseEntity<Map<String,Object>> problem(Problem e){return ResponseEntity.status(e.status).header("Cache-Control","no-store").body(Map.of("code",e.code,"retryable",e.status==503));}
 @ExceptionHandler({SQLException.class,org.springframework.dao.DataAccessException.class}) ResponseEntity<Map<String,Object>> unknown(Exception e){return problem(new Problem(503,"OPERATION_RESULT_UNKNOWN"));}
}
