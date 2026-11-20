package com.ias.stock;

import com.ias.trust.Chain;
import com.ias.trust.Problem;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

/** Current online authorization; no caller-supplied tenant, actor or warehouse scope. */
public final class TrustedStockAccess implements StockAccess {
    @FunctionalInterface interface Remote { Map<String,Object> call(String edge,String path,Map<String,Object> body,String trace,long deadline); }
    private final Remote remote;
    public TrustedStockAccess(){this(Chain::call);}
    TrustedStockAccess(Remote remote){this.remote=remote;}
    private Map<String,Object> facts(HttpServletRequest request,String token,String purpose){
        return facts(request,token,purpose,false);
    }
    private Map<String,Object> facts(HttpServletRequest request,String token,String purpose,boolean write){
        if(token==null||token.isBlank())throw new Problem(401,"SESSION_INVALID");
        var body=new HashMap<String,Object>();body.put("sessionToken",token);body.put("purpose",purpose);
        if(write){String csrf=request.getHeader("X-CSRF-TOKEN");if(csrf==null||csrf.isBlank())throw new Problem(403,"CSRF_INVALID");body.put("csrfToken",csrf);}
        var result=remote.call("stock-identity","/internal/identity/domain-authorizations",body,Chain.trace(request),Chain.deadline(request));
        Chain.fresh(result,Chain.deadline(request));
        if(!Boolean.TRUE.equals(result.get("enabled"))||!"EMPLOYEE".equals(result.get("identityType")))throw new Problem(403,"ACCESS_DENIED");
        id(result.get("tenantId"));id(result.get("userId"));return result;
    }
    static long id(Object value){try{String s=String.valueOf(value);if(!s.matches("[1-9][0-9]{0,18}"))throw new Exception();return Long.parseLong(s);}catch(Exception e){throw new Problem(503,"DEPENDENCY_UNAVAILABLE");}}
    private Principal principal(Map<String,Object> facts){
        var scopes=new HashSet<Long>();Object raw=facts.get("warehouseIds");
        if(!(raw instanceof Collection<?> values))throw new Problem(503,"DEPENDENCY_UNAVAILABLE");
        for(Object value:values)scopes.add(id(value));
        return new Principal(id(facts.get("tenantId")),id(facts.get("userId")),scopes);
    }
    public Principal catalog(HttpServletRequest request,String ticketId,String assignmentId){
        String token=Chain.cookie(request);var facts=facts(request,token,"STOCK_CATALOG");
        var context=remote.call("stock-ticket","/internal/ticket/approval-context",Map.of("sessionToken",token,"ticketId",ticketId,"purpose","CATALOG","assignmentId",assignmentId),Chain.trace(request),Chain.deadline(request));
        Chain.fresh(context,Chain.deadline(request));
        if(!ticketId.equals(context.get("ticketId"))||!assignmentId.equals(context.get("assignmentId"))||!"IN_PROGRESS".equals(context.get("status"))||id(facts.get("tenantId"))!=id(context.get("tenantId"))||id(facts.get("userId"))!=id(context.get("engineerUserId")))throw new Problem(403,"ACCESS_DENIED");
        return principal(facts);
    }
    public Principal balance(HttpServletRequest request,long warehouseId){return principal(facts(request,Chain.cookie(request),"STOCK_READ"));}
    public Principal approval(HttpServletRequest request,String token){return principal(facts(request,token,"STOCK_CATALOG"));}
    public Principal manage(HttpServletRequest request,long warehouseId){var actor=principal(facts(request,Chain.cookie(request),"STOCK_MANAGE",true));if(!actor.warehouseIds().contains(warehouseId))throw new Problem(404,"NOT_FOUND");return actor;}
}
