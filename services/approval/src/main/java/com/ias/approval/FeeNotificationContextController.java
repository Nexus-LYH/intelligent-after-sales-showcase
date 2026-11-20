package com.ias.approval;

import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;

/** Exact owner projection for approval.fee_completed notifications. */
@RestController
public final class FeeNotificationContextController {
 private static final Set<String> BASE=Set.of("eventId","tenantId","eventType","aggregateId","aggregateVersion","feeGroupId","generation","purpose");
 private final JdbcTemplate db;
 public FeeNotificationContextController(DataSource source){this.db=new JdbcTemplate(source);}
 @PostMapping("/internal/approval/fee-notification-context") Map<String,Object> context(@RequestBody Map<String,Object> body,HttpServletRequest r){
  edge(r);String purpose=text(body,"purpose");var expected=new HashSet<>(BASE);if("READ".equals(purpose))expected.add("sessionToken");if(!Set.of("DELIVERY","READ").contains(purpose)||!body.keySet().equals(expected))throw fail(400,"INVALID_REQUEST");
  String tenant=positive(body,"tenantId"),version=number(body,"aggregateVersion"),generation=number(body,"generation");UUID event=id(body,"eventId"),application=id(body,"aggregateId"),group=id(body,"feeGroupId");if(!"approval.fee_completed".equals(body.get("eventType")))throw fail(400,"INVALID_EVENT_TYPE");
  var rows=db.queryForList("SELECT o.event_type,o.aggregate_version,a.snapshot,b.ticket_context,c.payload,g.generation,g.state FROM fee_approval_outbox o JOIN fee_approval_application a ON a.tenant_id=o.tenant_id AND a.id=o.application_id JOIN fee_approval_binding b ON b.tenant_id=a.tenant_id AND b.application_id=a.id JOIN fee_approval_completion c ON c.tenant_id=a.tenant_id AND c.application_id=a.id JOIN approval_finance_generation g ON g.tenant_id=a.tenant_id AND g.ticket_id=a.ticket_id WHERE o.event_id=? AND o.tenant_id=? AND o.application_id=?",event.toString(),Long.parseLong(tenant),application.toString());if(rows.size()!=1)throw fail(404,"FEE_NOTIFICATION_FACT_NOT_FOUND");var row=rows.getFirst();
  if(!"FEE_APPROVAL_COMPLETED".equals(row.get("event_type"))||!version.equals(row.get("aggregate_version").toString())||!generation.equals(row.get("generation").toString()))throw fail(409,"FEE_NOTIFICATION_FACT_STALE");
  Map<String,Object> app=json(row.get("snapshot")),ticket=json(row.get("ticket_context")),completion=json(row.get("payload"));if(!"COMPLETED".equals(app.get("state"))||!group.toString().equals(completion.get("groupId"))||!group.toString().equals(ticket.get("groupId"))||!generation.equals(completion.get("generation")))throw fail(503,"FEE_NOTIFICATION_FACT_MISMATCH");
  String company=factPositive(completion,"companyId"),department=factPositive(ticket,"departmentId"),applicant=factPositive(app,"applicantId");boolean current="OPEN".equals(row.get("state"))&&generation.equals(row.get("generation").toString());if(!current)throw fail(409,"FEE_NOTIFICATION_SCOPE_REVOKED");if("READ".equals(purpose))read(r,text(body,"sessionToken"),tenant,company);
  var out=new LinkedHashMap<String,Object>();for(String key:List.of("eventId","tenantId","eventType","aggregateId","aggregateVersion","feeGroupId","generation"))out.put(key,body.get(key));out.put("companyId",company);out.put("departmentId",department);out.put("applicantUserId",applicant);out.put("factVersion",version);out.put("eventVersion","1");out.put("currentScope",true);out.put("qualified",true);out.put("revoked",false);out.put("checkedAt",Instant.now().toString());return out;
 }
 private void read(HttpServletRequest r,String token,String tenant,String company){var fact=Chain.call("approval-identity","/internal/identity/domain-authorizations",Map.of("sessionToken",token,"purpose","FEE_READ"),Chain.trace(r),Chain.deadline(r));if(!Boolean.TRUE.equals(fact.get("enabled"))||!tenant.equals(fact.get("tenantId"))||!((List<?>)fact.getOrDefault("companyIds",List.of())).contains(company))throw fail(403,"ACCESS_DENIED");}
 @ExceptionHandler(Problem.class) ResponseEntity<Map<String,Object>> problem(Problem e){return ResponseEntity.status(e.status).header("Cache-Control","no-store").body(Map.of("code",e.code,"retryable",e.status==503));}
 @ExceptionHandler(Exception.class) ResponseEntity<Map<String,Object>> unavailable(Exception e){return problem(new Problem(503,"DEPENDENCY_UNAVAILABLE"));}
 @ExceptionHandler(ApprovalFailure.class) ResponseEntity<Map<String,Object>> failure(ApprovalFailure e){return ResponseEntity.status(e.status()).header("Cache-Control","no-store").body(Map.of("code",e.getMessage(),"retryable",e.status()==503));}
 @ExceptionHandler({HttpMessageNotReadableException.class,NullPointerException.class}) ResponseEntity<Map<String,Object>> invalid(Exception e){return ResponseEntity.badRequest().header("Cache-Control","no-store").body(Map.of("code","INVALID_REQUEST","retryable",false));}
 private static void edge(HttpServletRequest r){if(!Chain.active()||!"approval".equals(Chain.role())||!r.isSecure()||!"notification-approval".equals(r.getAttribute("ias.chain.edge")))throw fail(403,"SERVICE_AUTHENTICATION_FAILED");}
 @SuppressWarnings("unchecked") private static Map<String,Object> json(Object value){try{return Chain.JSON.readValue(value.toString(),Map.class);}catch(Exception e){throw fail(503,"FEE_NOTIFICATION_FACT_INVALID");}}
 private static UUID id(Map<String,Object>b,String k){try{String s=text(b,k);UUID id=UUID.fromString(s);if(!id.toString().equals(s))throw new Exception();return id;}catch(Exception e){throw fail(400,"INVALID_REQUEST");}}
 private static String positive(Map<String,Object>b,String k){String s=text(b,k);if(!s.matches("[1-9][0-9]{0,18}"))throw fail(400,"INVALID_REQUEST");return s;}
 private static String factPositive(Map<String,Object>b,String k){String s=Objects.toString(b.get(k),"");if(!s.matches("[1-9][0-9]{0,18}"))throw fail(503,"FEE_NOTIFICATION_FACT_INVALID");return s;}
 private static String number(Map<String,Object>b,String k){String s=text(b,k);if(!s.matches("0|[1-9][0-9]{0,18}"))throw fail(400,"INVALID_REQUEST");return s;}
 private static String text(Map<String,Object>b,String k){if(!(b.get(k) instanceof String s)||s.isBlank())throw fail(400,"INVALID_REQUEST");return s;}
 private static ApprovalFailure fail(int status,String code){return new ApprovalFailure(status,code);}
}
