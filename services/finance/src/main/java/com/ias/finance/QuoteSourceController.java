package com.ias.finance;

import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import static com.ias.finance.FinanceController.*;
import static com.ias.finance.JdbcFeeGroups.*;

@RestController
public final class QuoteSourceController {
    @FunctionalInterface interface Trust {void check(HttpServletRequest r);}
    final DataSource source;final FinanceAccess access;final Trust trust;
    @Autowired public QuoteSourceController(DataSource source,FinanceAccess access){this(source,access,r->{if(!Chain.active()||!r.isSecure()||!"ticket-finance".equals(r.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");});}
    QuoteSourceController(DataSource source,FinanceAccess access,Trust trust){this.source=source;this.access=access;this.trust=trust;}
    @ModelAttribute public void noStore(jakarta.servlet.http.HttpServletResponse r){r.setHeader("Cache-Control","no-store");}
    @GetMapping("/api/finance/groups/{group}/quote-sources/{operation}") public Object read(@PathVariable String group,@PathVariable String operation,HttpServletRequest r)throws Exception{
        var p=access.principal(r,false);var owner=new JdbcFeeGroups(source,access.bind(r,p)).owner(p.actor(),id(group));var fact=new JdbcQuoteSources(source,access.bind(r,p)).read(p.actor(),owner,id(operation));return view(fact);
    }
    @PostMapping("/api/finance/groups/{group}/quote-sources/{operation}/abort") public Object abort(@PathVariable String group,@PathVariable String operation,@RequestBody String raw,HttpServletRequest r)throws Exception{
        var b=body(raw,Set.of("operationId","quoteId","expectedGroupVersion","commandDigest","reason"));UUID op=key(b,r);if(!op.equals(id(operation)))throw new Problem(400,"INVALID_IDEMPOTENCY_KEY");var p=access.principal(r,true);var owner=new JdbcFeeGroups(source,access.bind(r,p)).owner(p.actor(),id(group));return view(new JdbcQuoteSources(source,access.bind(r,p)).abort(p.actor(),owner,op,id(b.get("quoteId")),number(b.get("expectedGroupVersion")),text(b.get("commandDigest")),text(b.get("reason"))));
    }
    @PostMapping("/internal/finance/quote-sources/query") public Object query(@RequestBody String raw,HttpServletRequest r)throws Exception{
        trust.check(r);var b=body(raw,Set.of("tenantId","actorUserId","companyId","faultRelationId","ticketId","roundId","groupId","expectedGroupVersion","quoteId","operationId","commandDigest"));
        var owner=new FeeGroup.Identity(number(b.get("tenantId")),number(b.get("companyId")),id(b.get("groupId")));var actor=new Actor(owner.tenantId(),number(b.get("actorUserId")));var binding=new JdbcQuoteSources.Binding(actor,owner,id(b.get("operationId")),id(b.get("quoteId")),number(b.get("expectedGroupVersion")),text(b.get("commandDigest")));
        var fact=new JdbcQuoteSources(source,null).verified(binding,id(b.get("ticketId")),id(b.get("roundId")),id(b.get("faultRelationId")));var out=view(fact);for(String field:List.of("faultRelationId","ticketId","roundId"))out.put(field,b.get(field));return out;
    }
    static Map<String,Object> view(JdbcQuoteSources.Source source){var out=new LinkedHashMap<>(JdbcQuoteSources.view(source));out.put("checkedAt",Instant.now().toString());return out;}
}
