package com.ias.finance;
import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import static com.ias.finance.FinanceController.*;
@RestController
public final class FeeCompletionMirrorController {
 final DataSource source;final TrustedFinanceAccess access;final QuoteSourceController.Trust trust;final FeeCompletionMirror mirror;
 @Autowired public FeeCompletionMirrorController(DataSource source){this(source,new TrustedFinanceAccess(source),r->{if(!Chain.active()||!r.isSecure()||!"ticket-finance".equals(r.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");});}
 FeeCompletionMirrorController(DataSource source,TrustedFinanceAccess access,QuoteSourceController.Trust trust){this.source=source;this.access=access;this.trust=trust;this.mirror=new FeeCompletionMirror(source);}
 @ModelAttribute public void noStore(jakarta.servlet.http.HttpServletResponse r){r.setHeader("Cache-Control","no-store");}
 @PostMapping("/internal/finance/fee-completions/record") public Object record(@RequestBody String raw,HttpServletRequest r)throws Exception{trust.check(r);var b=body(raw,Set.of("tenantId","companyId","groupId","completionId"));long tenant=number(b.get("tenantId")),company=number(b.get("companyId"));if(tenant<=0||company<=0)throw new Problem(400,"INVALID_NUMBER");var owner=new FeeGroup.Identity(tenant,company,id(b.get("groupId")));var verified=TrustedFeeCompletion.obtain(source,access,r,owner,id(b.get("completionId")));var out=new LinkedHashMap<>(mirror.record(owner,verified));out.put("checkedAt",Instant.now().toString());return out;}
}
