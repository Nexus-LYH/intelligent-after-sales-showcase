package com.ias.ticket;

import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * W-only durable candidate. Source-intent verification, P/S owner generation barriers and
 * global ACK orchestration are separate prerequisites. Never infer clear from an empty scan.
 */
@RestController
public class FinanceQuoteBoundary {
    record Binding(long tenantId,long actorUserId,long companyId,String faultRelationId,String ticketId,
                   String roundId,String groupId,long expectedGroupVersion,String quoteId,
                   String operationId,String commandDigest) {}
    record SourceFact(Binding binding,String state,String resultDigest) {}
    @FunctionalInterface interface IdentityFacts {Map<String,Object> current(String token,String csrf,HttpServletRequest request);}
    @FunctionalInterface interface SourceFacts {SourceFact verified(Binding binding,String requiredState,HttpServletRequest request);}
    private final Tickets t;private final IdentityFacts identities;private final SourceFacts sources;private FinanceFreezeCoordinator coordinator;
    public FinanceQuoteBoundary(Tickets t){this(t,new FinanceFreezeCoordinator(t));}
    @org.springframework.beans.factory.annotation.Autowired
    public FinanceQuoteBoundary(Tickets t,FinanceFreezeCoordinator coordinator){this(t,(token,csrf,r)->{
        var f=Chain.call("ticket-identity","/internal/identity/domain-authorizations",Map.of("sessionToken",token,"purpose","FINANCE_WRITE","csrfToken",csrf),Chain.trace(r),Chain.deadline(r));Chain.fresh(f,Chain.deadline(r));return f;
    },FinanceQuoteSourceAdapter::verified);this.coordinator=coordinator;}
    FinanceQuoteBoundary(Tickets t,IdentityFacts identities,SourceFacts sources){this.t=t;this.identities=identities;this.sources=sources;}
    void trusted(HttpServletRequest request){ApprovalBoundaryController.trusted(request,Set.of("finance-ticket"));}
    static void guard(Tickets t,String ticket){
        if(t.db.queryForObject("SELECT COUNT(*) FROM ticket_finance_quote_fence f LEFT JOIN ticket_finance_coordination c ON c.freeze_id=f.freeze_id WHERE f.ticket_id=? AND (c.state IS NULL OR c.state<>'RELEASED')",Long.class,ticket)>0)throw new Problem(409,"FINANCE_QUOTE_FENCING");
    }
    /** Legacy producer has neither a durable source intent nor a reliable completion ACK. */
    Map<String,Object> quote(Map<String,Object> body,HttpServletRequest request){throw new Problem(503,"FINANCE_EXECUTION_FREEZE_NOT_CONFIRMED");}
    private Map<String,Object> current(Map<String,Object> body,HttpServletRequest request){
        String token=text(body,"sessionToken"),csrf=text(body,"csrfToken");var f=identities.current(token,csrf,request);
        if(!"EMPLOYEE".equals(f.get("identityType"))||!Boolean.TRUE.equals(f.get("enabled"))||!Boolean.TRUE.equals(f.get("departmentEnabled"))||!((List<?>)f.getOrDefault("permissions",List.of())).contains("finance.quote.write"))throw new Problem(403,"ACCESS_DENIED");
        positive(f.get("tenantId"));positive(f.get("userId"));return f;
    }
    private static long positive(Object value){if(!(value instanceof String s)||!s.matches("[1-9][0-9]{0,18}"))throw Tickets.bad();try{return Long.parseLong(s);}catch(Exception e){throw Tickets.bad();}}
    private static long version(Object value){if(!(value instanceof String s)||!s.matches("0|[1-9][0-9]{0,18}"))throw Tickets.bad();try{return Long.parseLong(s);}catch(Exception e){throw Tickets.bad();}}
    private static String text(Map<String,Object> b,String key){if(!(b.get(key) instanceof String s)||s.isBlank())throw Tickets.bad();return s;}
    private static String digest(Object value){if(!(value instanceof String s)||!s.matches("[0-9a-f]{64}"))throw Tickets.bad();return s;}
    private void source(Binding binding,String state,String result,HttpServletRequest r){
        var fact=sources.verified(binding,state,r);
        if(fact==null||!binding.equals(fact.binding()))throw new Problem(503,"FINANCE_SOURCE_BINDING_MISMATCH");
        if("ABORTED".equals(fact.state()))throw new Problem(409,"FINANCE_SOURCE_ABORTED");
        if(!state.equals(fact.state()))throw new Problem(503,"FINANCE_SOURCE_STATE_NOT_CONFIRMED");
        if(result!=null&&!result.equals(fact.resultDigest()))throw new Problem(409,"FINANCE_SOURCE_RESULT_MISMATCH");
    }
    @PostMapping("/internal/ticket/finance-quotes/prepare")
    Map<String,Object> prepare(@RequestBody Map<String,Object> b,HttpServletRequest r){
        trusted(r);if(!b.keySet().equals(Set.of("sessionToken","csrfToken","ticketId","roundId","groupId","expectedGroupVersion","quoteId","operationId","commandDigest")))throw Tickets.bad();
        String ticket=Tickets.uuid(b.get("ticketId")),round=Tickets.uuid(b.get("roundId")),group=Tickets.uuid(b.get("groupId")),quote=Tickets.uuid(b.get("quoteId")),operation=Tickets.uuid(b.get("operationId")),hash=digest(b.get("commandDigest"));long expected=version(b.get("expectedGroupVersion"));
        var first=current(b,r);long tenant=positive(first.get("tenantId")),actor=positive(first.get("userId"));
        var prepared=t.tx.execute(tx->{
            t.lockActor(tenant,actor);var rows=t.db.queryForList("SELECT * FROM ticket_record WHERE id=? AND tenant_id=? FOR UPDATE",ticket,tenant);if(rows.isEmpty())throw new Problem(404,"TARGET_NOT_ACCESSIBLE");var row=rows.getFirst();var f=current(b,r);
            if(positive(f.get("tenantId"))!=tenant||positive(f.get("userId"))!=actor)throw new Problem(401,"SESSION_INVALID");
            long company=Tickets.id(row.get("company_id"));if(!((List<?>)f.getOrDefault("companyIds",List.of())).contains(Long.toString(company)))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
            if(!"IN_PROGRESS".equals(row.get("status"))||row.get("current_cancel_request_id")!=null||!round.equals(row.get("round_id")))throw new Problem(409,"QUOTE_CONTEXT_CHANGED");
            String canonicalGroup=UUID.nameUUIDFromBytes(("finance:"+tenant+":"+company+":"+ticket).getBytes(StandardCharsets.UTF_8)).toString();if(!canonicalGroup.equals(group))throw new Problem(409,"FAULT_GROUP_RELATION_CHANGED");
            var binding=new Binding(tenant,actor,company,ticket,ticket,round,group,expected,quote,operation,hash);
            var prior=t.db.queryForList("SELECT * FROM ticket_finance_quote_fence WHERE tenant_id=? AND ticket_id=? AND operation_id=?",tenant,ticket,operation);
            if(!prior.isEmpty()&&!binding.equals(binding(prior.getFirst())))throw new Problem(409,"IDEMPOTENCY_CONFLICT");
            // A verified durable source intent is mandatory, including original-UUID retries.
            source(binding,"IN_PROGRESS",null,r);
            if(!prior.isEmpty())return view(prior.getFirst());
            // Existing permits are drained under the owner fence, never inferred empty or timed out.
            if(coordinator==null&&t.db.queryForObject("SELECT COUNT(*) FROM ticket_stock_execution p LEFT JOIN ticket_stock_execution_result x ON x.operation_id=p.operation_id WHERE p.ticket_id=? AND x.operation_id IS NULL",Long.class,ticket)>0)throw new Problem(409,"STOCK_EXECUTION_PENDING");
            FeePartPermitBarrier.guard(t,ticket);guard(t,ticket);
            Long highest=t.db.queryForObject("SELECT COALESCE(MAX(generation),0) FROM ticket_finance_quote_fence WHERE tenant_id=? AND fault_relation_id=?",Long.class,tenant,ticket);long generation=Math.incrementExact(highest);long rowVersion=Tickets.id(row.get("row_version"));if(rowVersion==Long.MAX_VALUE)throw new Problem(409,"VERSION_CONFLICT");
            String freeze=UUID.nameUUIDFromBytes(("finance-freeze:"+tenant+":"+ticket+":"+operation).getBytes(StandardCharsets.UTF_8)).toString();
            t.db.update("INSERT INTO ticket_finance_quote_fence(freeze_id,tenant_id,actor_user_id,company_id,fault_relation_id,ticket_id,round_id,group_id,expected_group_version,quote_id,operation_id,command_digest,generation,required_owners,recorded_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'[\"approval\",\"stock\"]',UTC_TIMESTAMP(3))",freeze,tenant,actor,company,ticket,ticket,round,group,expected,quote,operation,hash,generation);
            t.db.update("INSERT INTO ticket_finance_coordination(freeze_id,state,source_state,next_attempt_at,updated_at) VALUES(?,'FENCING','IN_PROGRESS',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",freeze);
            t.db.update("UPDATE ticket_record SET row_version=row_version+1 WHERE id=?",ticket);String audit=UUID.nameUUIDFromBytes(("finance-fence:"+freeze).getBytes(StandardCharsets.UTF_8)).toString();t.db.update("INSERT INTO ticket_audit(operation_id,tenant_id,actor_user_id,action,occurred_at) VALUES(?,?,?,'FINANCE_QUOTE_FENCING',UTC_TIMESTAMP(3))",audit,tenant,actor);
            return view(t.db.queryForMap("SELECT * FROM ticket_finance_quote_fence WHERE freeze_id=?",freeze));
        });
        return coordinator==null?prepared:coordinator.advance(prepared.get("freezeId").toString(),FinanceFreezeCoordinator.context(r));
    }
    static Binding binding(Map<String,Object> row){return new Binding(Tickets.id(row.get("tenant_id")),Tickets.id(row.get("actor_user_id")),Tickets.id(row.get("company_id")),row.get("fault_relation_id").toString(),row.get("ticket_id").toString(),row.get("round_id").toString(),row.get("group_id").toString(),Tickets.idOrZero(row.get("expected_group_version")),row.get("quote_id").toString(),row.get("operation_id").toString(),row.get("command_digest").toString());}
    static Map<String,Object> view(Map<String,Object> row){var b=binding(row);var out=new LinkedHashMap<String,Object>();out.put("tenantId",Long.toString(b.tenantId()));out.put("actorUserId",Long.toString(b.actorUserId()));out.put("companyId",Long.toString(b.companyId()));out.put("faultRelationId",b.faultRelationId());out.put("ticketId",b.ticketId());out.put("roundId",b.roundId());out.put("groupId",b.groupId());out.put("expectedGroupVersion",Long.toString(b.expectedGroupVersion()));out.put("quoteId",b.quoteId());out.put("operationId",b.operationId());out.put("commandDigest",b.commandDigest());out.put("freezeId",row.get("freeze_id").toString());out.put("generation",row.get("generation").toString());out.put("requiredOwners",List.of("approval","stock"));out.put("coordinationState","FENCING");out.put("quoteWritable",false);out.put("executionBarrierClear",false);out.put("recordedAt",Tickets.utc(row.get("recorded_at")));out.put("checkedAt",Instant.now().toString());return out;}
    @PostMapping("/internal/ticket/finance-quotes/source-commit")
    Map<String,Object> sourceCommit(@RequestBody Map<String,Object>b,HttpServletRequest r){
        trusted(r);if(!b.keySet().equals(Set.of("tenantId","ticketId","operationId","commandDigest","resultDigest")))throw Tickets.bad();long tenant=positive(b.get("tenantId"));String ticket=Tickets.uuid(b.get("ticketId")),op=Tickets.uuid(b.get("operationId")),hash=digest(b.get("commandDigest")),result=digest(b.get("resultDigest"));
        var found=t.db.queryForList("SELECT * FROM ticket_finance_quote_fence WHERE tenant_id=? AND ticket_id=? AND operation_id=?",tenant,ticket,op);if(found.isEmpty())throw new Problem(404,"OPERATION_NOT_FOUND");var old=found.getFirst();var binding=binding(old);if(!hash.equals(binding.commandDigest()))throw new Problem(409,"IDEMPOTENCY_CONFLICT");
        var recorded=t.tx.execute(tx->{t.lockActor(tenant,binding.actorUserId());t.db.queryForMap("SELECT id FROM ticket_record WHERE id=? AND tenant_id=? FOR UPDATE",ticket,tenant);
            source(binding,"COMMITTED",result,r);
            String freeze=old.get("freeze_id").toString();var prior=t.db.queryForList("SELECT result_digest FROM ticket_finance_quote_source_result WHERE freeze_id=?",freeze);if(!prior.isEmpty()){if(!result.equals(prior.getFirst().get("result_digest")))throw new Problem(409,"IDEMPOTENCY_CONFLICT");return sourceReceipt(freeze,op,hash,result);}
            t.db.update("INSERT INTO ticket_finance_quote_source_result VALUES(?,?,UTC_TIMESTAMP(3))",freeze,result);String audit=UUID.nameUUIDFromBytes(("finance-source-commit:"+freeze).getBytes(StandardCharsets.UTF_8)).toString();t.db.update("INSERT INTO ticket_audit(operation_id,tenant_id,actor_user_id,action,occurred_at) VALUES(?,?,0,'FINANCE_QUOTE_SOURCE_COMMIT_RECORDED',UTC_TIMESTAMP(3))",audit,tenant);t.db.update("UPDATE ticket_record SET row_version=row_version+1 WHERE id=?",ticket);return sourceReceipt(freeze,op,hash,result);
        });
        return coordinator==null?recorded:coordinator.advance(old.get("freeze_id").toString(),FinanceFreezeCoordinator.context(r));
    }
    private static Map<String,Object> sourceReceipt(String freeze,String operation,String command,String result){return Map.of("freezeId",freeze,"operationId",operation,"commandDigest",command,"resultDigest",result,"sourceState","SOURCE_COMMIT_RECORDED","coordinationState","FENCING","globalFreezeAck",false,"checkedAt",Instant.now().toString());}
    @ExceptionHandler(Problem.class)ResponseEntity<Map<String,Object>> problem(Problem p){return ResponseEntity.status(p.status).header("Cache-Control","no-store").body(Map.of("code",p.code,"retryable",p.status==503));}
}
