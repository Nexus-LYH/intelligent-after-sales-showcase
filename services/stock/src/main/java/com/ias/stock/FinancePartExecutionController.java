package com.ias.stock;

import com.ias.trust.*;
import jakarta.servlet.http.*;
import javax.sql.DataSource;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;
import static com.ias.stock.FinancePartExecution.*;

/** Candidate internal routes. BE-0 owns public chain allowlists and default-service activation. */
@RestController
public final class FinancePartExecutionController {
 @FunctionalInterface interface Peer {Map<String,Object> call(String edge,String path,Map<String,Object> body,HttpServletRequest request);}
 private final DataSource source;private final Peer peer;private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
 @org.springframework.beans.factory.annotation.Autowired public FinancePartExecutionController(DataSource source){this(source,(edge,path,body,r)->{if(!Chain.active()||!"stock".equals(Chain.role()))throw new Problem(503,"TRUST_NOT_CONFIGURED");var fact=Chain.call(edge,path,body,Chain.trace(r),Chain.deadline(r));fresh(fact);return fact;});}
 FinancePartExecutionController(DataSource source,Peer peer){this.source=source;this.peer=peer;}
 private FinancePartExecutionStore store(HttpServletRequest request,String token,boolean money){return new FinancePartExecutionStore(source,new Authority(){
  public Actor current(boolean write){if(token==null||token.isBlank())throw new Problem(401,"SESSION_INVALID");var body=new LinkedHashMap<String,Object>();body.put("sessionToken",token);body.put("purpose",money?"MONEY_READ":write?"FEE_SUBMIT":"FEE_READ");if(write)body.put("csrfToken",Chain.single(request,"X-CSRF-TOKEN"));var fact=peer.call("stock-identity","/internal/identity/domain-authorizations",body,request);if(!Boolean.TRUE.equals(fact.get("enabled")))throw new Problem(401,"SESSION_INVALID");if(!"EMPLOYEE".equals(fact.get("identityType"))||!Boolean.TRUE.equals(fact.get("departmentEnabled")))throw new Problem(403,"ACCESS_DENIED");var caps=(List<?>)fact.getOrDefault("permissions",List.of());if(!caps.contains(money?"finance.money.read":"approval.fees.read")||write&&!caps.containsAll(Set.of("approval.fees.submit","ticket.engineer.read","ticket.record")))throw new Problem(403,"ACCESS_DENIED");var companies=new HashSet<Long>();for(Object value:(List<?>)fact.getOrDefault("companyIds",List.of()))companies.add(number(value.toString(),false));return new Actor(number(fact.get("tenantId").toString(),false),number(fact.get("userId").toString(),false),companies);}
  public Map<String,Object> completion(long tenant,long company,String group,String completion){return peer.call("stock-approval","/internal/approval/fee-completions/query",Map.of("tenantId",Long.toString(tenant),"companyId",Long.toString(company),"groupId",group,"completionId",completion),request);}
  public Map<String,Object> permit(Actor actor,Command command){return peer.call("stock-ticket","/internal/ticket/part-fee-permits/proof",Map.of("tenantId",Long.toString(actor.tenantId()),"actorUserId",Long.toString(actor.userId()),"command",command.body()),request);}
  public void projectionScope(Map<String,Object> body){var b=new LinkedHashMap<String,Object>(body);b.put("sessionToken",token);var f=peer.call("stock-ticket","/internal/ticket/fee-executions/projection",b,request);fresh(f);if(!Long.toString(current(false).tenantId()).equals(f.get("tenantId")))throw invalid("PART_EXECUTION_SCOPE_MISMATCH");for(String k:List.of("companyId","groupId","completionId","quoteId","quoteVersion","generation","operationId","authorityDigest"))if(!Objects.equals(body.get(k),f.get(k)))throw invalid("PART_EXECUTION_SCOPE_MISMATCH");if(!"PROJECTION".equals(f.get("state")))throw invalid("PART_EXECUTION_SCOPE_MISMATCH");if(!(f.get("items") instanceof List<?> items)||!(body.get("lines") instanceof List<?> requested)||items.size()!=requested.size())throw invalid("PART_EXECUTION_SCOPE_MISMATCH");var expected=new HashMap<String,Map<?,?>>();for(Object raw:requested){var line=(Map<?,?>)raw;expected.put(line.get("lineId").toString(),line);}for(Object raw:items){if(!(raw instanceof Map<?,?> line)||!"OWNER_REQUIRED".equals(line.get("state")))throw invalid("PART_EXECUTION_SCOPE_MISMATCH");var lineExpected=expected.remove(Objects.toString(line.get("lineId"),""));if(lineExpected==null)throw invalid("PART_EXECUTION_SCOPE_MISMATCH");for(String k:List.of("lineId","ticketId","roundId","kind"))if(!Objects.equals(lineExpected.get(k),line.get(k)))throw invalid("PART_EXECUTION_SCOPE_MISMATCH");}if(!expected.isEmpty())throw invalid("PART_EXECUTION_SCOPE_MISMATCH");}
  @SuppressWarnings("unchecked") public OrderFact order(Actor actor,String order,long warehouse){var fact=peer.call("stock-approval","/internal/approval/stock-context",Map.of("sessionToken",token,"approvalId",order,"warehouseId",Long.toString(warehouse),"phase","ENGINEER_READ"),request);try{var quantities=new TreeMap<Long,Long>();for(Object raw:(List<?>)fact.get("lines")){var line=(Map<String,Object>)raw;if(quantities.put(number(line.get("partId").toString(),false),number(line.get("quantity").toString(),false))!=null)throw new Exception();}return new OrderFact(number(fact.get("tenantId").toString(),false),number(fact.get("warehouseId").toString(),false),uuid(fact.get("ticketId")),uuid(fact.get("roundId")),uuid(fact.get("approvalId")),number(fact.get("applicantUserId").toString(),false),quantities);}catch(Exception e){throw invalid("STOCK_ORDER_FACT_INVALID");}}
 });}
 @SuppressWarnings("unchecked") static Map<String,Object> body(String raw){if(raw==null||raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw new Problem(413,"REQUEST_TOO_LARGE");try{var result=JSON.readValue(raw,Map.class);if(result==null)throw bad();return result;}catch(Exception e){if(e instanceof Problem p)throw p;throw bad();}}
 private static String token(Map<String,Object> body){Object token=body.remove("sessionToken");if(!(token instanceof String s)||s.isBlank())throw bad();return s;}
 private static void trusted(HttpServletRequest r,String edge){if(!Chain.active()||!"stock".equals(Chain.role())||!r.isSecure()||!edge.equals(r.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");if(!r.getParameterMap().isEmpty())throw bad();}
 @PostMapping("/internal/stock/finance-part-executions") public Map<String,Object> execute(@RequestBody String raw,HttpServletRequest request){trusted(request,"ticket-stock");var body=body(raw);String token=token(body);var command=new Command(body);if(!command.value("operationId").equals(uuid(Chain.single(request,"Idempotency-Key"))))throw bad();
  return store(request,token,false).execute(command);}
 @GetMapping("/internal/stock/finance-part-execution-operations/{operationId}") public Map<String,Object> operation(@PathVariable String operationId,@RequestHeader("X-Session-Token") String token,HttpServletRequest request){trusted(request,"ticket-stock");return store(request,token,false).operation(operationId);}
 @PostMapping("/internal/stock/finance-part-executions/refuse") public Map<String,Object> refuse(@RequestBody String raw,HttpServletRequest request){
  trusted(request,"ticket-stock");var body=body(raw);if(!body.keySet().equals(Set.of("tenantId","operationId","commandDigest")))throw bad();
  String op=uuid(body.get("operationId"));if(!op.equals(uuid(Chain.single(request,"Idempotency-Key"))))throw bad();
  return new FinancePartExecutionStore(source,null).refuse(number(body.get("tenantId"),false),op,hex(body.get("commandDigest")));
 }
 @PostMapping("/internal/stock/finance-part-executions/owner-result") public Map<String,Object> ownerResult(@RequestBody String raw,HttpServletRequest request){
  trusted(request,"ticket-stock");var body=body(raw);if(!body.keySet().equals(Set.of("tenantId","operationId","commandDigest")))throw bad();
  return new FinancePartExecutionStore(source,null).ownerResult(number(body.get("tenantId"),false),uuid(body.get("operationId")),hex(body.get("commandDigest")));
 }
 @PostMapping("/internal/stock/finance-part-executions/query") public Map<String,Object> query(@RequestBody String raw,HttpServletRequest request){trusted(request,"finance-stock");var body=body(raw);String token=token(body);return store(request,token,true).query(body);}
 @PostMapping("/internal/stock/finance-part-executions/projection") public Map<String,Object> projection(@RequestBody String raw,HttpServletRequest request){trusted(request,"finance-stock");var body=body(raw);String token=token(body);return store(request,token,false).projection(body);}
 @ModelAttribute public void noStore(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
 @ExceptionHandler(Problem.class) ResponseEntity<Map<String,Object>> problem(Problem p){return ResponseEntity.status(p.status).header("Cache-Control","no-store").body(Map.of("code",p.code,"retryable",p.status==503));}
 @ExceptionHandler(Exception.class) ResponseEntity<Map<String,Object>> unknown(Exception e){return problem(new Problem(503,"OPERATION_RESULT_UNKNOWN"));}
}
