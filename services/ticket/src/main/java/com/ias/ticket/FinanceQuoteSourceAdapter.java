package com.ias.ticket;

import com.ias.trust.Chain;
import com.ias.trust.Problem;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

/** Exact durable F-source facts; absence, stale proof and transport failure never release W. */
final class FinanceQuoteSourceAdapter {
    static Map<String,Object> request(FinanceQuoteBoundary.Binding b) {
        var fields=new LinkedHashMap<String,Object>();
        fields.put("tenantId",Long.toString(b.tenantId()));fields.put("actorUserId",Long.toString(b.actorUserId()));
        fields.put("companyId",Long.toString(b.companyId()));fields.put("faultRelationId",b.faultRelationId());
        fields.put("ticketId",b.ticketId());fields.put("roundId",b.roundId());fields.put("groupId",b.groupId());
        fields.put("expectedGroupVersion",Long.toString(b.expectedGroupVersion()));fields.put("quoteId",b.quoteId());
        fields.put("operationId",b.operationId());fields.put("commandDigest",b.commandDigest());return fields;
    }
    static FinanceQuoteBoundary.SourceFact verified(FinanceQuoteBoundary.Binding b,String required,HttpServletRequest r) {
        long deadline=Chain.deadline(r);
        var fact=Chain.call("ticket-finance","/internal/finance/quote-sources/query",request(b),Chain.trace(r),deadline);
        return parse(b,fact,deadline);
    }
    static FinanceQuoteBoundary.SourceFact parse(FinanceQuoteBoundary.Binding b,Map<String,Object> fact,long deadline) {
        Chain.fresh(fact,deadline);
        for(var field:request(b).entrySet())if(!field.getValue().equals(fact.get(field.getKey())))throw new Problem(503,"FINANCE_SOURCE_BINDING_MISMATCH");
        Object state=fact.get("sourceState"),result=fact.get("resultDigest");
        if(!(state instanceof String s)||!Set.of("IN_PROGRESS","COMMITTED","ABORTED").contains(s)||!s.equals(fact.get("state")))throw new Problem(503,"FINANCE_SOURCE_STATE_NOT_CONFIRMED");
        if("COMMITTED".equals(state)?!(result instanceof String digest&&digest.matches("[0-9a-f]{64}")):result!=null)throw new Problem(503,"FINANCE_SOURCE_RESULT_MISMATCH");
        return new FinanceQuoteBoundary.SourceFact(b,(String)state,(String)result);
    }
}
