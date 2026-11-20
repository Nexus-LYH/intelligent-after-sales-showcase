package com.ias.asset;

import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import java.time.*;
import java.util.*;

@RestController
public class RepairBindings {
 final JdbcTemplate db;final TransactionTemplate tx;
 public RepairBindings(JdbcTemplate db,TransactionTemplate tx){this.db=db;this.tx=tx;}
 long n(Object v){try{long id=Long.parseLong(v.toString());if(id<1)throw new Exception();return id;}catch(Exception e){throw new Problem(400,"VALIDATION_ERROR");}}
 String uuid(Object v){try{String s=v.toString();if(!UUID.fromString(s).toString().equals(s))throw new Exception();return s;}catch(Exception e){throw new Problem(400,"VALIDATION_ERROR");}}
 Map<String,Object> identity(Map<String,Object>b,HttpServletRequest r,String op){return Chain.identity("asset-identity",(String)b.get("sessionToken"),op,(String)b.get("csrfToken"),Chain.trace(r),Chain.deadline(r));}
 @PostMapping("/internal/asset/repair-access") public Map<String,Object> access(@RequestBody Map<String,Object>b,HttpServletRequest r){
  var a=identity(b,r,Boolean.TRUE.equals(b.get("newBusiness"))?"TICKET_CREATE":"TICKET_READ");long tenant=n(a.get("tenantId")),user=n(a.get("userId"));
  if(b.get("deviceId")!=null){var rows=db.queryForList("SELECT d.*,c.status company_status FROM asset_device d JOIN asset_company c ON c.id=d.company_id AND c.tenant_id=d.tenant_id WHERE d.tenant_id=? AND d.id=? AND EXISTS(SELECT 1 FROM asset_membership m WHERE m.tenant_id=d.tenant_id AND m.company_id=d.company_id AND m.user_id=? AND m.state='ACTIVE')",tenant,n(b.get("deviceId")),user);if(rows.isEmpty())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");var d=rows.getFirst();if(Boolean.TRUE.equals(b.get("newBusiness"))&&(!d.get("status").equals("ENABLED")||!d.get("company_status").equals("ENABLED")))throw new Problem(409,"INVALID_STATE");if(b.get("expectedOwnershipVersion")!=null&&n(b.get("expectedOwnershipVersion"))!=n(d.get("ownership_version")))throw new Problem(409,"OWNERSHIP_VERSION_CONFLICT");}
  return Map.of("companyIds",a.get("identityType").equals("CUSTOMER")?db.queryForList("SELECT company_id FROM asset_membership WHERE tenant_id=? AND user_id=? AND state='ACTIVE' ORDER BY company_id",Long.class,tenant,user).stream().map(Object::toString).toList():List.of(),"checkedAt",Instant.now().toString());
 }
 Map<String,Object> fact(String op,Map<String,Object>b,HttpServletRequest r){return Chain.call("asset-ticket","/internal/ticket/create-facts/"+op,Map.of("bindingId",b.get("bindingId"),"ticketId",b.get("ticketId"),"requestDigest",b.get("requestDigest")),Chain.trace(r),Chain.deadline(r));}
 void matching(Map<String,Object> row,Map<String,Object>b){for(String[] pair:new String[][]{{"operation_id","operationId"},{"ticket_id","ticketId"},{"binding_id","bindingId"},{"request_digest","requestDigest"}})if(!row.get(pair[0]).toString().equals(b.get(pair[1])))throw new Problem(409,"IDEMPOTENCY_CONFLICT");}
 Map<String,Object> view(Map<String,Object>row){Map<String,Object> out=new LinkedHashMap<>();out.put("bindingId",row.get("binding_id"));out.put("state",row.get("state"));out.put("snapshot",row.get("snapshot_json")==null?null:Chain.JSON.readValue(row.get("snapshot_json").toString(),Map.class));out.put("acceptedAt",row.get("accepted_at")==null?null:(row.get("accepted_at") instanceof java.sql.Timestamp ts?ts.toInstant():((LocalDateTime)row.get("accepted_at")).toInstant(ZoneOffset.UTC)).toString());return out;}
 @PostMapping("/internal/asset/repair-bindings/reserve") public Map<String,Object> reserve(@RequestBody Map<String,Object>b,HttpServletRequest r){
  String id=uuid(b.get("bindingId")),op=uuid(b.get("operationId"));var a=identity(b,r,"TICKET_CREATE");long tenant=n(a.get("tenantId")),user=n(a.get("userId")),device=n(b.get("deviceId"));
  var f=fact(op,b,r);if(!Set.of("REGISTERED","RESERVED").contains(f.get("state")))throw new Problem(409,"OPERATION_ABORTED");if(n(f.get("tenantId"))!=tenant||n(f.get("actorUserId"))!=user||n(f.get("deviceId"))!=device)throw new Problem(403,"ACCESS_DENIED");
  return tx.execute(t->{
   var locate=db.queryForList("SELECT company_id FROM asset_device WHERE tenant_id=? AND id=?",tenant,device);if(locate.isEmpty())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");long company=n(locate.getFirst().get("company_id"));
   var c=db.queryForMap("SELECT * FROM asset_company WHERE tenant_id=? AND id=? FOR UPDATE",tenant,company);var d=db.queryForMap("SELECT * FROM asset_device WHERE tenant_id=? AND id=? FOR UPDATE",tenant,device);if(n(d.get("company_id"))!=company)throw new Problem(409,"OWNERSHIP_VERSION_CONFLICT");
   var prior=db.queryForList("SELECT * FROM asset_repair_binding WHERE binding_id=? FOR UPDATE",id);if(!prior.isEmpty()){var row=prior.getFirst();matching(row,b);if(!row.get("state").equals("RELEASED"))return view(row);throw new Problem(409,"OPERATION_ABORTED");}
   if(db.queryForList("SELECT id FROM asset_membership WHERE tenant_id=? AND company_id=? AND user_id=? AND state='ACTIVE' FOR UPDATE",tenant,company,user).isEmpty())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
   if(!c.get("status").equals("ENABLED")||!d.get("status").equals("ENABLED"))throw new Problem(409,"INVALID_STATE");if(n(d.get("ownership_version"))!=n(b.get("expectedOwnershipVersion")))throw new Problem(409,"OWNERSHIP_VERSION_CONFLICT");
   var model=db.queryForMap("SELECT * FROM asset_model WHERE tenant_id=? AND id=?",tenant,d.get("model_id"));var snap=Map.of("companySnapshot",Map.of("id",Long.toString(company),"code",c.get("code"),"name",c.get("name")),"deviceSnapshot",Map.of("id",Long.toString(device),"code",d.get("code"),"name",d.get("name"),"serialNumber",d.get("serial_number"),"modelId",d.get("model_id").toString(),"modelName",model.get("name"),"ownershipVersion",d.get("ownership_version").toString()));
   Chain.fresh(identity(b,r,"TICKET_CREATE"),Chain.deadline(r));db.update("INSERT INTO asset_repair_binding(binding_id,operation_id,ticket_id,tenant_id,actor_user_id,device_id,request_digest,state,snapshot_json,accepted_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'RESERVED',?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id,op,b.get("ticketId"),tenant,user,device,b.get("requestDigest"),Chain.JSON.writeValueAsString(snap));audit(op,tenant,user,"REPAIR_RESERVED");return view(db.queryForMap("SELECT * FROM asset_repair_binding WHERE binding_id=?",id));
  });
 }
 @PostMapping("/internal/asset/repair-bindings/{id}/{action}") public Map<String,Object> transition(@PathVariable String id,@PathVariable String action,@RequestBody Map<String,Object>b,HttpServletRequest r){
  uuid(id);if(!id.equals(b.get("bindingId"))||!Set.of("query","confirm","release").contains(action))throw new Problem(400,"VALIDATION_ERROR");var f=fact(uuid(b.get("operationId")),b,r);
  if(action.equals("query")){var rows=db.queryForList("SELECT * FROM asset_repair_binding WHERE binding_id=?",id);if(rows.isEmpty())return Map.of("bindingId",id,"state","ABSENT");matching(rows.getFirst(),b);return view(rows.getFirst());}
  boolean release=action.equals("release");if(!(release?f.get("state").equals("ABORTED"):Set.of("COMMITTED","COMPLETED").contains(f.get("state"))))throw new Problem(409,"INVALID_STATE");
  long tenant=n(f.get("tenantId")),device=n(f.get("deviceId")),user=n(f.get("actorUserId"));
  return tx.execute(t->{db.queryForList("SELECT id FROM asset_device WHERE tenant_id=? AND id=? FOR UPDATE",tenant,device);var rows=db.queryForList("SELECT * FROM asset_repair_binding WHERE binding_id=? FOR UPDATE",id);
   if(rows.isEmpty()){if(!release)throw new Problem(409,"INVALID_STATE");db.update("INSERT INTO asset_repair_binding(binding_id,operation_id,ticket_id,tenant_id,actor_user_id,device_id,request_digest,state,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'RELEASED',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",id,b.get("operationId"),b.get("ticketId"),tenant,user,device,b.get("requestDigest"));audit(b.get("operationId").toString(),tenant,user,"REPAIR_RELEASED");}
   else{var row=rows.getFirst();matching(row,b);String state=row.get("state").toString(),next=release?"RELEASED":"CONFIRMED";if(state.equals("CONFIRMED")&&release||state.equals("RELEASED")&&!release)throw new Problem(409,"INVALID_STATE");if(!state.equals(next)){db.update("UPDATE asset_repair_binding SET state=?,updated_at=UTC_TIMESTAMP(3) WHERE binding_id=?",next,id);audit(b.get("operationId").toString(),tenant,user,"REPAIR_"+next);}}
   return view(db.queryForMap("SELECT * FROM asset_repair_binding WHERE binding_id=?",id));});
 }
 void audit(String op,long tenant,long user,String action){db.update("INSERT INTO asset_repair_audit(tenant_id,actor_user_id,action,operation_id,occurred_at) VALUES(?,?,?,?,UTC_TIMESTAMP(3))",tenant,user,action,op);}
 /** No HTTP entry. Only a trusted approved transfer fact may call this same device fence. */
 public void applyApprovedTransfer(long tenant,long device,long targetCompany,long expected,ApprovedTransfer approval){if(approval==null||!approval.approved||approval.effectiveAt.isAfter(Instant.now()))throw new Problem(403,"APPROVAL_REQUIRED");tx.executeWithoutResult(t->{long old=n(db.queryForObject("SELECT company_id FROM asset_device WHERE tenant_id=? AND id=?",Long.class,tenant,device));for(long id:new TreeSet<>(List.of(old,targetCompany)))db.queryForMap("SELECT id FROM asset_company WHERE tenant_id=? AND id=? FOR UPDATE",tenant,id);var d=db.queryForMap("SELECT company_id,ownership_version FROM asset_device WHERE tenant_id=? AND id=? FOR UPDATE",tenant,device);if(n(d.get("company_id"))!=old||n(d.get("ownership_version"))!=expected)throw new Problem(409,"OWNERSHIP_VERSION_CONFLICT");if(db.queryForObject("SELECT COUNT(*) FROM asset_repair_binding WHERE tenant_id=? AND device_id=? AND state='RESERVED'",Long.class,tenant,device)>0)throw new Problem(409,"BINDING_PENDING");db.update("UPDATE asset_device SET company_id=?,ownership_version=ownership_version+1,row_version=row_version+1,updated_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND id=?",targetCompany,tenant,device);});}
 public record ApprovedTransfer(boolean approved,Instant effectiveAt){}
}

