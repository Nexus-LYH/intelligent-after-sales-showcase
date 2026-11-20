package com.ias.ticket;

import com.ias.trust.*;
import jakarta.servlet.http.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

/** Read-only durable proof for the stock owner. This endpoint never creates a permit. */
@RestController
public final class FeePartPermitController {
 private final FeePartPermitStore store;
 public FeePartPermitController(Tickets tickets){store=new FeePartPermitStore(tickets);}
 @PostMapping("/internal/ticket/part-fee-permits/proof")
 public Map<String,Object> proof(@RequestBody String raw,HttpServletRequest request){
  ApprovalBoundaryController.trusted(request,Set.of("stock-ticket"));
  if(!"ticket".equals(Chain.role())||!request.getParameterMap().isEmpty())throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");
  var body=FinanceLaborExecutionController.body(raw);
  if(!body.keySet().equals(Set.of("tenantId","actorUserId","command"))||!(body.get("command") instanceof Map<?,?> input))throw Tickets.bad();
  var command=new LinkedHashMap<String,Object>();for(var entry:input.entrySet()){if(!(entry.getKey() instanceof String key))throw Tickets.bad();command.put(key,entry.getValue());}
  var result=new LinkedHashMap<>(store.activeProof(Tickets.id(body.get("tenantId")),Tickets.id(body.get("actorUserId")),new FeePartCommand(command)));
  result.put("checkedAt",Instant.now().toString());return result;
 }
 @ModelAttribute public void noStore(HttpServletResponse response){response.setHeader("Cache-Control","no-store");}
 @ExceptionHandler(Problem.class) ResponseEntity<Map<String,Object>> problem(Problem p){return ResponseEntity.status(p.status).header("Cache-Control","no-store").body(Map.of("code",p.code,"retryable",p.status==503));}
 @ExceptionHandler(Exception.class) ResponseEntity<Map<String,Object>> unavailable(Exception e){return problem(new Problem(503,"PERMIT_PROOF_UNAVAILABLE"));}
}
