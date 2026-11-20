package com.ias.ticket;

import com.ias.trust.*;
import jakarta.servlet.http.*;
import javax.sql.DataSource;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.json.JsonMapper;
import static com.ias.ticket.FinanceLaborExecution.*;

/** Candidate routes: default deployment/Chain allowlists and V16 are root-owned integration work. */
@RestController
public final class FinanceLaborExecutionController {
 @FunctionalInterface interface Peer {Map<String,Object> call(String edge,String path,Map<String,Object> body,HttpServletRequest r);}
 private final DataSource source;private final Peer peer;
 private static final JsonMapper JSON=JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
 @org.springframework.beans.factory.annotation.Autowired public FinanceLaborExecutionController(DataSource source){this(source,(edge,path,b,r)->{if(!Chain.active()||!"ticket".equals(Chain.role()))throw new Problem(503,"TRUST_NOT_CONFIGURED");var f=Chain.call(edge,path,b,Chain.trace(r),Chain.deadline(r));Chain.fresh(f,Chain.deadline(r));return f;});}
 FinanceLaborExecutionController(DataSource source,Peer peer){this.source=source;this.peer=peer;}
 private FinanceLaborExecutionStore store(HttpServletRequest r,String token,boolean money){return new FinanceLaborExecutionStore(source,new FinanceLaborExecutionStore.Authority(){
  public Actor current(boolean write){
   if(token==null||token.isBlank())throw new Problem(401,"SESSION_INVALID");var body=new LinkedHashMap<String,Object>();body.put("sessionToken",token);body.put("purpose",money?"MONEY_READ":write?"FEE_SUBMIT":"FEE_READ");if(write){Chain.source(r);body.put("csrfToken",Chain.single(r,"X-CSRF-TOKEN"));}
   var f=peer.call("ticket-identity","/internal/identity/domain-authorizations",body,r);fresh(f);
   if(!Boolean.TRUE.equals(f.get("enabled")))throw new Problem(401,"SESSION_INVALID");if(!"EMPLOYEE".equals(f.get("identityType"))||!Boolean.TRUE.equals(f.get("departmentEnabled")))throw new Problem(403,"ACCESS_DENIED");
   var caps=(List<?>)f.getOrDefault("permissions",List.of());if(!caps.contains(money?"finance.money.read":"approval.fees.read")||write&&!caps.containsAll(Set.of("approval.fees.submit","ticket.engineer.read","ticket.record")))throw new Problem(403,"ACCESS_DENIED");
   var companies=new HashSet<Long>();for(Object c:(List<?>)f.getOrDefault("companyIds",List.of()))companies.add(number(c,false));Long dept=f.get("departmentId")==null?null:number(f.get("departmentId"),false);
   return new Actor(number(f.get("tenantId"),false),number(f.get("userId"),false),companies,dept);
  }
  public Map<String,Object> completion(long tenant,long company,String group,String completion){return peer.call("ticket-approval","/internal/approval/fee-completions/query",Map.of("tenantId",Long.toString(tenant),"companyId",Long.toString(company),"groupId",group,"completionId",completion),r);}
 });}
 @SuppressWarnings("unchecked") static Map<String,Object> body(String raw){if(raw==null||raw.length()>65536||raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536)throw new Problem(413,"REQUEST_TOO_LARGE");try{var result=JSON.readValue(raw,Map.class);if(result==null)throw bad();return result;}catch(Exception e){throw bad();}}
 private static void noQuery(HttpServletRequest r){if(!r.getParameterMap().isEmpty())throw bad();}
 @PostMapping("/api/ticket/tickets/{ticket}/fee-executions") public Map<String,Object> execute(@PathVariable String ticket,@RequestBody String raw,HttpServletRequest r){noQuery(r);var c=new Command(ticket,body(raw));if(!c.value("operationId").equals(uuid(Chain.single(r,"Idempotency-Key"))))throw bad();return store(r,Chain.cookie(r),false).execute(c);}
 @GetMapping("/api/ticket/tickets/{ticket}/fee-execution-operations/{operationId}") public Map<String,Object> operation(@PathVariable String ticket,@PathVariable String operationId,HttpServletRequest r){noQuery(r);return store(r,Chain.cookie(r),false).operation(ticket,operationId);}
 @PostMapping("/internal/ticket/fee-executions/query") public Map<String,Object> query(@RequestBody String raw,HttpServletRequest r){noQuery(r);if(!Chain.active()||!"ticket".equals(Chain.role())||!r.isSecure()||!"finance-ticket".equals(r.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");var b=body(raw);if(!(b.get("sessionToken") instanceof String token)||token.isBlank())throw bad();return store(r,token,true).query(b);}
 @PostMapping("/internal/ticket/fee-executions/projection") public Map<String,Object> projection(@RequestBody String raw,HttpServletRequest r){noQuery(r);String edge=(String)r.getAttribute("ias.chain.edge");if(!Chain.active()||!"ticket".equals(Chain.role())||!r.isSecure()||edge==null||!Set.of("finance-ticket","stock-ticket").contains(edge))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");var b=body(raw);if(!(b.get("sessionToken") instanceof String token)||token.isBlank())throw bad();return store(r,token,false).projection(b);}
 @ModelAttribute public void noStore(HttpServletResponse r){r.setHeader("Cache-Control","no-store");}
 @ExceptionHandler(Problem.class) ResponseEntity<Map<String,Object>> problem(Problem p){return ResponseEntity.status(p.status).header("Cache-Control","no-store").body(Map.of("code",p.code,"retryable",p.status==503));}
 @ExceptionHandler(Exception.class) ResponseEntity<Map<String,Object>> unknown(Exception e){return problem(new Problem(503,"OPERATION_RESULT_UNKNOWN"));}
}
