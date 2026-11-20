package com.ias.finance;

import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import javax.sql.DataSource;
import java.util.*;
import static com.ias.finance.FinanceController.*;

/** Read immutable P completion by exact owner/ID, then match the original F quote. */
final class TrustedFeeCompletion {
 record Verified(UUID applicationId,UUID completionId,JdbcFeeGroups.QuoteVersion quote,Map<String,Object> fact){}
 static Verified obtain(DataSource source,TrustedFinanceAccess access,HttpServletRequest r,FeeGroup.Identity owner,UUID completion)throws java.sql.SQLException{
  var fact=access.call("finance-approval","/internal/approval/fee-completions/query",Map.of("tenantId",Long.toString(owner.tenantId()),"companyId",Long.toString(owner.customerCompanyId()),"groupId",owner.faultGroupId().toString(),"completionId",completion.toString()),r);
  if(!Long.toString(owner.tenantId()).equals(fact.get("tenantId"))||!Long.toString(owner.customerCompanyId()).equals(fact.get("companyId"))||!owner.faultGroupId().toString().equals(fact.get("groupId"))||!completion.toString().equals(fact.get("completionId"))||!"COMPLETED".equals(fact.get("state")))throw new Problem(503,"FINANCE_COMPLETION_FACT_MISMATCH");
  UUID quoteId=TrustedFinanceAccess.ownerUuid(fact.get("quoteId")),application=TrustedFinanceAccess.ownerUuid(fact.get("applicationId"));
  try(var db=source.getConnection();var q=JdbcMoneyLedger.query(db,"SELECT quote_json FROM finance_quote_version WHERE tenant_id=? AND fault_group_id=? AND application_id=?",owner.tenantId(),owner.faultGroupId().toString(),quoteId.toString());var rows=q.executeQuery()){
   if(!rows.next())throw new Problem(503,"FINANCE_COMPLETION_ORIGINAL_QUOTE_MISSING");var quote=JSON.readValue(rows.getString(1),JdbcFeeGroups.QuoteVersion.class);
   var lines=quote.command().additions().stream().sorted(Comparator.comparing(l->l.id().toString())).map(l->JdbcFeeGroups.coverageLine(l,quote.details())).toList();
   if(!quote.details().partBindings().isEmpty()&&!"2".equals(fact.get("coverageVersion")))throw new Problem(503,"FINANCE_COMPLETION_COVERAGE_MISMATCH");
   if(!owner.equals(quote.basis().owner())||!Long.toString(quote.basis().groupVersion()).equals(fact.get("quoteVersion"))||!JdbcFeeGroups.quoteDigest(owner,quote.command(),quote.details()).equals(fact.get("quoteDigest"))||!JdbcMoneyLedger.digest(lines).equals(fact.get("coverageDigest"))||!Boolean.valueOf(quote.basis().financialReviewRequired()).equals(fact.get("financialReviewRequired")))throw new Problem(503,"FINANCE_COMPLETION_FACT_MISMATCH");
   TrustedFinanceAccess.number(fact.get("generation"));
   if(quote.command().additions().stream().anyMatch(l->!l.ticketId().toString().equals(fact.get("ticketId"))||!l.roundId().toString().equals(fact.get("roundId"))))throw new Problem(503,"FINANCE_COMPLETION_CONTEXT_MISMATCH");
   String relation=TrustedFinanceAccess.ownerUuid(fact.get("faultRelationId")).toString();
   if(!owner.faultGroupId().equals(UUID.nameUUIDFromBytes(("finance:"+owner.tenantId()+":"+owner.customerCompanyId()+":"+relation).getBytes(java.nio.charset.StandardCharsets.UTF_8))))throw new Problem(503,"FINANCE_COMPLETION_CONTEXT_MISMATCH");
   TrustedFinanceAccess.ownerUuid(fact.get("supervisorReceiptId"));
   if(quote.basis().financialReviewRequired())TrustedFinanceAccess.ownerUuid(fact.get("financeReceiptId"));else if(fact.get("financeReceiptId")!=null)throw new Problem(503,"FINANCE_COMPLETION_DECISIONS_MISMATCH");
   if(!lines.equals(fact.get("lines")))throw new Problem(503,"FINANCE_COMPLETION_COVERAGE_MISMATCH");
   return new Verified(application,completion,quote,Collections.unmodifiableMap(new LinkedHashMap<>(fact)));
  }
 }
}
