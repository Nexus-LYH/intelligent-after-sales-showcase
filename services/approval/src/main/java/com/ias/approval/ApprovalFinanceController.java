package com.ias.approval;

import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.time.*;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Authenticated W owner protocol only. Browser input cannot manufacture source facts. */
@RestController
public final class ApprovalFinanceController {
    @FunctionalInterface interface Proof {Map<String,Object> get(Map<String,Object> binding,HttpServletRequest request);}
    private final DataSource source;private final Proof proof;
    @Autowired public ApprovalFinanceController(DataSource source){this(source,(b,r)->Chain.call("approval-ticket","/internal/ticket/finance-quotes/proof",b,Chain.trace(r),Chain.deadline(r)));}
    ApprovalFinanceController(DataSource source,Proof proof){this.source=source;this.proof=proof;}
    static final Set<String> FIELDS=Set.of("tenantId","actorUserId","companyId","faultRelationId","ticketId","roundId","groupId","expectedGroupVersion","quoteId","operationId","commandDigest","freezeId","generation","ticketFenceDigest");
    @PostMapping("/internal/approval/finance-fences/prepare")
    Map<String,Object> prepare(@RequestBody Map<String,Object> body,HttpServletRequest request){
        edge(request);var b=binding(body);
        var first=proof(body,request);verifyPrepared(first,request,"IN_PROGRESS",null);
        var prepared=proof(body,request);verifyPrepared(prepared,request,"IN_PROGRESS",null);
        var owner=new ApprovalFinanceGeneration(source,ignored->verifyPrepared(prepared,request,"IN_PROGRESS",null));
        var result=owner.fence(b);var out=new TreeMap<String,Object>(body);out.put("owner","approval");out.put("state",result.state());out.put("globalFreezeAck",false);
        if(result.ack()!=null)out.putAll(result.ack());
        else{out.put("ackDigest",null);out.put("localSnapshot",result.localSnapshot());out.put("coverageDigest",digest(result.localSnapshot()));out.put("blockers",result.blockers());
            var time=new JdbcTemplate(source).queryForObject("SELECT recorded_at FROM approval_finance_fence WHERE tenant_id=? AND freeze_id=?",(r,n)->r.getObject(1,LocalDateTime.class).toInstant(ZoneOffset.UTC).toString(),b.tenantId(),b.freezeId().toString());out.put("recordedAt",time);}
        out.put("checkedAt",Instant.now().toString());return out;
    }
    @PostMapping("/internal/approval/finance-fences/reconcile")
    Map<String,Object> reconcile(@RequestBody Map<String,Object> body,HttpServletRequest request){
        edge(request);var b=binding(body);var first=proof(body,request);String state=text(first,"sourceState");
        if(!Set.of("COMMITTED","ABORTED").contains(state))throw new Problem(409,"SOURCE_NOT_TERMINAL");String result=(String)first.get("resultDigest");
        var owner=new ApprovalFinanceGeneration(source,ignored->verifyPrepared(first,request,state,result));
        var out=new TreeMap<String,Object>(owner.reconcile(b,state,result));out.put("checkedAt",Instant.now().toString());return out;
    }
    private void verifyPrepared(Map<String,Object>value,HttpServletRequest r,String state,String result){Chain.fresh(value,Chain.deadline(r));if(!state.equals(value.get("sourceState"))||!Objects.equals(result,value.get("resultDigest")))throw new Problem(409,"SOURCE_PROOF_CHANGED");}
    private Map<String,Object> proof(Map<String,Object>b,HttpServletRequest r){
        var value=proof.get(b,r);if(value==null)throw new Problem(503,"SOURCE_PROOF_UNAVAILABLE");Chain.fresh(value,Chain.deadline(r));
        for(String key:FIELDS)if(!Objects.equals(b.get(key),value.get(key)))throw new Problem(503,"SOURCE_PROOF_BINDING_MISMATCH");
        if(!(value.get("sourceState") instanceof String state)||!(value.get("coordinationState") instanceof String coord))throw new Problem(503,"INVALID_SOURCE_PROOF");
        if("IN_PROGRESS".equals(state)){if(!Set.of("FENCING","FROZEN").contains(coord)||value.get("resultDigest")!=null)throw new Problem(503,"INVALID_SOURCE_PROOF");}
        else if(Set.of("COMMITTED","ABORTED").contains(state)){if(!Set.of("RECONCILING","RELEASED").contains(coord))throw new Problem(503,"INVALID_SOURCE_PROOF");if("COMMITTED".equals(state)&&(!(value.get("resultDigest") instanceof String digest)||!digest.matches("[0-9a-f]{64}"))||"ABORTED".equals(state)&&value.get("resultDigest")!=null)throw new Problem(503,"INVALID_SOURCE_PROOF");}
        else throw new Problem(503,"INVALID_SOURCE_PROOF");return value;
    }
    static ApprovalFinanceGeneration.Binding binding(Map<String,Object>b){
        if(!b.keySet().equals(FIELDS))throw new Problem(400,"VALIDATION_ERROR");for(Object v:b.values())if(!(v instanceof String))throw new Problem(400,"VALIDATION_ERROR");
        return new ApprovalFinanceGeneration.Binding(StockExecutionContext.id(b.get("tenantId")),StockExecutionContext.id(b.get("companyId")),uuid(b,"ticketId"),uuid(b,"roundId"),uuid(b,"groupId"),decimal(b,"expectedGroupVersion"),uuid(b,"quoteId"),uuid(b,"operationId"),hex(b.get("commandDigest")),uuid(b,"freezeId"),positive(b,"generation"),StockExecutionContext.id(b.get("actorUserId")),uuid(b,"faultRelationId"),hex(b.get("ticketFenceDigest")));
    }
    private static String positive(Map<String,Object>b,String key){String s=decimal(b,key);if(s.equals("0"))throw new Problem(400,"VALIDATION_ERROR");return s;}
    private static String decimal(Map<String,Object>b,String key){String s=text(b,key);try{long value=Long.parseLong(s);if(value<0||!Long.toString(value).equals(s))throw new Exception();return s;}catch(Exception e){throw new Problem(400,"VALIDATION_ERROR");}}
    private static UUID uuid(Map<String,Object>b,String key){return UUID.fromString(StockExecutionContext.uuid(b.get(key)));}
    private static String text(Map<String,Object>b,String key){return StockExecutionContext.text(b,key);}
    private static String hex(Object value){if(!(value instanceof String s)||!s.matches("[0-9a-f]{64}"))throw new Problem(400,"INVALID_DIGEST");return s;}
    private static String digest(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Chain.JSON.writeValueAsBytes(value)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static void edge(HttpServletRequest r){if(!Chain.active()||!r.isSecure()||!"ticket-approval".equals(r.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");}
    @ExceptionHandler(Problem.class)org.springframework.http.ResponseEntity<Map<String,Object>> problem(Problem p){return org.springframework.http.ResponseEntity.status(p.status).header("Cache-Control","no-store").body(Map.of("code",p.code,"retryable",p.status==503));}
    @ExceptionHandler(ApprovalFailure.class)org.springframework.http.ResponseEntity<Map<String,Object>> problem(ApprovalFailure p){return org.springframework.http.ResponseEntity.status(p.status()).header("Cache-Control","no-store").body(Map.of("code",p.getMessage(),"retryable",p.status()==503));}
}
