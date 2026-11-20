package com.ias.ticket;
import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

/** Customer-visible notification facts: immutable ticket event plus current customer membership. */
@RestController
final class CustomerNotificationContextController {
 private final JdbcTemplate db;
 CustomerNotificationContextController(JdbcTemplate db){this.db=db;}
 @PostMapping("/internal/ticket/customer-notification-context")
 Map<String,Object> context(@RequestBody Map<String,Object> b,HttpServletRequest r){
  edge(r);
  String purpose=Objects.toString(b.get("purpose"),"");
  var required=Set.of("eventId","tenantId","ticketId","eventType","aggregateVersion","purpose");
  var allowed=new HashSet<>(required);if("READ".equals(purpose))allowed.add("sessionToken");
  if(!allowed.containsAll(b.keySet())||!b.keySet().containsAll(required)||!Set.of("DELIVERY","READ").contains(purpose))throw Tickets.bad();
  String event=Tickets.uuid(b.get("eventId")),ticket=Tickets.uuid(b.get("ticketId")),type=Objects.toString(b.get("eventType"),"");
  long tenant=Tickets.id(b.get("tenantId")),version=Tickets.id(b.get("aggregateVersion"));
  if(!Set.of("ticket.accepted","ticket.resolution_submitted","ticket.closed").contains(type))throw new Problem(404,"NOTIFICATION_FACT_NOT_FOUND");
  var rows=db.queryForList("SELECT * FROM ticket_customer_notification_outbox WHERE event_id=? AND tenant_id=? AND ticket_id=? AND event_type=? AND aggregate_version=?",event,tenant,ticket,type,version);
  if(rows.size()!=1)throw new Problem(404,"NOTIFICATION_FACT_NOT_FOUND");
  var row=rows.getFirst();String envelope=row.get("envelope").toString();
  if(!Tickets.hash(envelope).equals(row.get("event_digest")))throw new Problem(503,"NOTIFICATION_FACT_INVALID");
  Map<String,Object> decoded;try{decoded=Chain.JSON.readValue(envelope,Map.class);}catch(Exception e){throw new Problem(503,"NOTIFICATION_FACT_INVALID");}
  if(!(decoded.get("payload") instanceof Map<?,?> payload)||!event.equals(decoded.get("eventId"))||!ticket.equals(payload.get("ticketId"))||!Long.toString(tenant).equals(decoded.get("tenantId"))||!Long.toString(version).equals(decoded.get("aggregateVersion"))||!type.equals(decoded.get("type")))throw new Problem(503,"NOTIFICATION_FACT_INVALID");
  if(Set.of("ticket.resolution_submitted","ticket.closed").contains(type)){String solution=Objects.toString(payload.get("solutionId"),"");if(!solution.equals(Tickets.uuid(solution)))throw new Problem(503,"NOTIFICATION_FACT_INVALID");}
  long company=Tickets.id(row.get("company_id")),recipient=Tickets.id(row.get("recipient_user_id"));
  if("READ".equals(purpose)){
   String token=(String)b.get("sessionToken");if(token==null||token.isBlank())throw new Problem(401,"SESSION_INVALID");
   var identity=Chain.call("ticket-identity","/internal/identity/domain-authorizations",Map.of("sessionToken",token,"purpose","NOTIFICATION_READ"),Chain.trace(r),Chain.deadline(r));Chain.fresh(identity,Chain.deadline(r));
   if(!Boolean.TRUE.equals(identity.get("enabled"))||!"CUSTOMER".equals(identity.get("identityType"))||Tickets.id(identity.get("tenantId"))!=tenant||Tickets.id(identity.get("userId"))!=recipient)throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
   var access=Chain.call("ticket-asset","/internal/asset/repair-access",Map.of("sessionToken",token),Chain.trace(r),Chain.deadline(r));Chain.fresh(access,Chain.deadline(r));
   if(!(access.get("companyIds") instanceof List<?> ids)||!ids.contains(Long.toString(company)))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  }
  var out=new LinkedHashMap<String,Object>();out.put("tenantId",Long.toString(tenant));out.put("eventId",event);out.put("eventType",type);out.put("ticketId",ticket);out.put("companyId",Long.toString(company));out.put("recipientUserId",Long.toString(recipient));out.put("stateCode",row.get("state_code").toString());out.put("aggregateVersion",Long.toString(version));out.put("checkedAt",Instant.now().toString());return out;
 }
 private static void edge(HttpServletRequest r){if(!Chain.active()||!"ticket".equals(Chain.role())||!r.isSecure()||!"notification-ticket".equals(r.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");}
}
