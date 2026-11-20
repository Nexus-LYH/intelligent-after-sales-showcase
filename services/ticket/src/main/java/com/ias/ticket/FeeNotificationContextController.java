package com.ias.ticket;

import com.ias.trust.*;
import jakarta.servlet.http.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;

/** Exact current owner projection for ticket fee terminal events. */
@RestController
public final class FeeNotificationContextController {
 private static final Set<String> BASE=Set.of("eventId","tenantId","eventType","aggregateId","aggregateVersion","feeGroupId","generation","purpose");
 private final JdbcTemplate db;
 public FeeNotificationContextController(DataSource source){db=new JdbcTemplate(source);}
 @PostMapping("/internal/ticket/fee-notification-context") Map<String,Object> context(@RequestBody Map<String,Object>b,HttpServletRequest r){
  edge(r);String purpose=text(b,"purpose");var fields=new HashSet<>(BASE);if("READ".equals(purpose))fields.add("sessionToken");if(!Set.of("DELIVERY","READ").contains(purpose)||!b.keySet().equals(fields))throw bad();
  String tenant=positive(b,"tenantId"),version=positive(b,"aggregateVersion"),generation=positive(b,"generation"),eventType=text(b,"eventType");if(!"ticket.fee_execution_recorded".equals(eventType))throw bad();
  String event=uuid(b,"eventId"),aggregate=uuid(b,"aggregateId"),group=uuid(b,"feeGroupId");
  var rows=db.queryForList("SELECT o.*,r.round_id current_round,r.assignment_id current_assignment,r.company_id current_company,r.reporter_user_id current_applicant,r.status,f.generation current_generation,f.group_id current_group,a.department_id current_department FROM ticket_fee_notification_outbox o JOIN ticket_record r ON r.id=o.ticket_id AND r.tenant_id=o.tenant_id JOIN ticket_create_operation c ON c.operation_id=r.operation_id AND c.state='COMPLETED' JOIN ticket_finance_quote_fence f ON f.tenant_id=r.tenant_id AND f.ticket_id=r.id AND f.generation=o.generation JOIN ticket_assignment a ON a.id=o.assignment_id AND a.ticket_id=r.id WHERE o.event_id=? AND o.tenant_id=?",event,Long.parseLong(tenant));
  if(rows.size()!=1)throw new Problem(404,"FEE_NOTIFICATION_FACT_NOT_FOUND");var row=rows.getFirst();
  exact(row,"event_type",eventType);exact(row,"aggregate_id",aggregate);exact(row,"aggregate_version",version);exact(row,"fee_group_id",group);exact(row,"generation",generation);
  boolean current=Objects.equals(row.get("round_id"),row.get("current_round"))&&Objects.equals(row.get("assignment_id"),row.get("current_assignment"))&&Objects.equals(row.get("company_id"),row.get("current_company"))&&Objects.equals(row.get("applicant_user_id"),row.get("current_applicant"))&&Objects.equals(row.get("fee_group_id"),row.get("current_group"))&&Objects.equals(row.get("generation").toString(),row.get("current_generation").toString())&&Objects.equals(row.get("department_id"),row.get("current_department"))&&!Set.of("CANCELLED").contains(row.get("status"));
  if(!current)throw new Problem(409,"FEE_NOTIFICATION_SCOPE_REVOKED");if("READ".equals(purpose))read(r,text(b,"sessionToken"),tenant,row.get("company_id").toString());
  var out=new LinkedHashMap<String,Object>();for(String key:List.of("eventId","tenantId","eventType","aggregateId","aggregateVersion","feeGroupId","generation"))out.put(key,b.get(key));out.put("companyId",row.get("company_id").toString());out.put("departmentId",row.get("department_id").toString());out.put("applicantUserId",row.get("applicant_user_id").toString());out.put("factVersion",version);out.put("eventVersion","1");out.put("currentScope",true);out.put("qualified",true);out.put("revoked",false);out.put("checkedAt",Instant.now().toString());return out;
 }
 private void read(HttpServletRequest r,String token,String tenant,String company){var f=Chain.call("ticket-identity","/internal/identity/domain-authorizations",Map.of("sessionToken",token,"purpose","FEE_READ"),Chain.trace(r),Chain.deadline(r));Chain.fresh(f,Chain.deadline(r));if(!Boolean.TRUE.equals(f.get("enabled"))||!tenant.equals(f.get("tenantId"))||!((List<?>)f.getOrDefault("companyIds",List.of())).contains(company))throw new Problem(403,"ACCESS_DENIED");}
 private static void edge(HttpServletRequest r){if(!Chain.active()||!"ticket".equals(Chain.role())||!r.isSecure()||!"notification-ticket".equals(r.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");}
 private static void exact(Map<String,Object>r,String key,String expected){if(!expected.equals(Objects.toString(r.get(key),"")))throw new Problem(409,"FEE_NOTIFICATION_FACT_STALE");}
 private static String text(Map<String,Object>b,String k){if(!(b.get(k) instanceof String s)||s.isBlank())throw bad();return s;}
 private static String uuid(Map<String,Object>b,String k){String s=text(b,k);try{if(!UUID.fromString(s).toString().equals(s))throw bad();return s;}catch(IllegalArgumentException e){throw bad();}}
 private static String positive(Map<String,Object>b,String k){String s=text(b,k);if(!s.matches("[1-9][0-9]{0,18}"))throw bad();return s;}
 private static Problem bad(){return new Problem(400,"INVALID_REQUEST");}
 @ExceptionHandler(Problem.class)ResponseEntity<Map<String,Object>> problem(Problem p){return ResponseEntity.status(p.status).header("Cache-Control","no-store").body(Map.of("code",p.code,"retryable",p.status==503));}
 @ExceptionHandler(Exception.class)ResponseEntity<Map<String,Object>> unknown(Exception e){return problem(new Problem(503,"DEPENDENCY_UNAVAILABLE"));}
}
