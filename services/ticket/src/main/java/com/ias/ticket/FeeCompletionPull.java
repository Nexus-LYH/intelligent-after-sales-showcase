package com.ias.ticket;
import com.ias.trust.*;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** W pulls immutable P completions and reconciles F's high-water mark without an end-user session. */
@Component
public final class FeeCompletionPull {
 @FunctionalInterface interface Peer {Map<String,Object> call(String edge,String path,Map<String,Object> request);}
 private final Tickets t;private final Peer peer;
 @org.springframework.beans.factory.annotation.Autowired
 public FeeCompletionPull(Tickets t){this(t,(edge,path,b)->{var result=Chain.call(edge,path,b,UUID.randomUUID().toString(),System.currentTimeMillis()+10000);Chain.fresh(result,System.currentTimeMillis()+10000);return result;});}
 FeeCompletionPull(Tickets t,Peer peer){this.t=t;this.peer=peer;}
 private static Timestamp now(){return Timestamp.from(Instant.now());}
 private static String text(Map<String,Object> b,String key){if(!(b.get(key) instanceof String s)||s.isBlank())throw new Problem(503,"FEE_COMPLETION_BINDING_MISSING");return s;}
 private static void match(Map<String,Object> b,Map<String,Object> expected){for(var e:expected.entrySet())if(!Objects.equals(b.get(e.getKey()),e.getValue()))throw new Problem(503,"FEE_COMPLETION_BINDING_MISMATCH");}
 @Scheduled(fixedDelay=2000,initialDelay=8000)
 public void poll(){if(!Chain.active()||!"ticket".equals(Chain.role()))return;try{pollOnce();}catch(RuntimeException ignored){/* no secrets or private request bodies in logs; durable retries remain due */}}
 void pollOnce(){
  t.db.update("INSERT INTO ticket_fee_group_pull(tenant_id,company_id,group_id,next_attempt_at) SELECT DISTINCT tenant_id,company_id,group_id,UTC_TIMESTAMP(3) FROM ticket_finance_quote_fence ON DUPLICATE KEY UPDATE next_attempt_at=next_attempt_at");
  for(var group:t.db.queryForList("SELECT * FROM ticket_fee_group_pull WHERE next_attempt_at<=UTC_TIMESTAMP(3) AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(3)) ORDER BY next_attempt_at,tenant_id,group_id LIMIT 8"))reconcile(group);
 }
 void reconcile(Map<String,Object> row){
  String lease=UUID.randomUUID().toString(),tenant=row.get("tenant_id").toString(),company=row.get("company_id").toString(),group=row.get("group_id").toString();
  if(t.db.update("UPDATE ticket_fee_group_pull SET lease_id=?,lease_until=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 30 SECOND),attempts=attempts+1 WHERE tenant_id=? AND group_id=? AND (lease_until IS NULL OR lease_until<UTC_TIMESTAMP(3))",lease,tenant,group)==0)return;
  String failure=null;
  try{
   Map<String,Object> binding=Map.of("tenantId",tenant,"companyId",company,"groupId",group);
   var facts=peer.call("ticket-approval","/internal/approval/fee-completions/group",binding);match(facts,binding);
   if(!(facts.get("completions") instanceof List<?> completions)||completions.size()>1000)throw new Problem(503,"FEE_COMPLETION_SCOPE_LIMIT");
   var seen=new HashSet<String>();
   for(Object value:completions){
    if(!(value instanceof Map<?,?> raw))throw new Problem(503,"FEE_COMPLETION_INVALID");
    @SuppressWarnings("unchecked") var completion=(Map<String,Object>)raw;match(completion,binding);
    String id=Tickets.uuid(completion.get("completionId"));if(!seen.add(id)||!"COMPLETED".equals(completion.get("state")))throw new Problem(503,"FEE_COMPLETION_INVALID");
    if(t.db.queryForObject("SELECT COUNT(*) FROM ticket_fee_completion_ack WHERE tenant_id=? AND group_id=? AND completion_id=?",Long.class,tenant,group,id)>0)continue;
    var request=new LinkedHashMap<String,Object>(binding);request.put("completionId",id);
    var acknowledged=peer.call("ticket-finance","/internal/finance/fee-completions/record",request);match(acknowledged,request);
    if(!text(completion,"quoteId").equals(acknowledged.get("quoteId"))||!text(completion,"quoteVersion").equals(acknowledged.get("quoteVersion")))throw new Problem(503,"FEE_COMPLETION_RECORD_MISMATCH");
    Tickets.uuid(acknowledged.get("recordingOperationId"));Tickets.id(acknowledged.get("groupVersion"));
    var immutable=new TreeMap<String,Object>(acknowledged);immutable.remove("checkedAt");String digest=FinanceFreezeCoordinator.sha(immutable);
    t.tx.execute(tx->{
     var old=t.db.queryForList("SELECT ack_digest FROM ticket_fee_completion_ack WHERE tenant_id=? AND group_id=? AND completion_id=?",tenant,group,id);
     if(!old.isEmpty()){if(!digest.equals(old.getFirst().get("ack_digest")))throw new Problem(409,"FEE_COMPLETION_RECORD_CONFLICT");return null;}
     t.db.update("INSERT INTO ticket_fee_completion_ack VALUES(?,?,?,?,?,?,?,?,?)",tenant,company,group,id,text(completion,"quoteId"),text(completion,"quoteVersion"),digest,Chain.JSON.writeValueAsString(immutable),now());return null;
    });
   }
  }catch(RuntimeException e){failure=e instanceof Problem p?p.code:"DEPENDENCY_UNAVAILABLE";}
  finally{t.db.update("UPDATE ticket_fee_group_pull SET lease_id=NULL,lease_until=NULL,next_attempt_at=DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 2 SECOND),last_error_code=? WHERE tenant_id=? AND group_id=? AND lease_id=?",failure,tenant,group,lease);}
 }
}
