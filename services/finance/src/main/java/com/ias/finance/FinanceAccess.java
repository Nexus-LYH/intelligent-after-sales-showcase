package com.ias.finance;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import static com.ias.finance.FeeGroup.*;
import static com.ias.finance.JdbcFeeGroups.*;

/** Owner facts are verified online, never deserialized from a public request. */
public interface FinanceAccess {
 record Principal(Actor actor,String type){}
 record Scope(Identity owner,UUID ticket,UUID round){}
 Principal principal(HttpServletRequest request,boolean write);
 default Principal reviewPrincipal(HttpServletRequest request){return principal(request,false);}
 default Principal feePrincipal(HttpServletRequest request){return principal(request,false);}
 default Access feeBind(HttpServletRequest request,Principal principal){return bind(request,principal);}
 default Principal moneyPrincipal(HttpServletRequest request,boolean write){return principal(request,write);}
 default Identity moneyOwner(javax.sql.DataSource source,HttpServletRequest request,Principal p,UUID group)throws java.sql.SQLException{return new JdbcFeeGroups(source,bind(request,p)).owner(p.actor(),group);}
 Scope scope(HttpServletRequest request,Principal principal,UUID ticket);
 PublishedRule rule(HttpServletRequest request);
 Access bind(HttpServletRequest request,Principal principal);
 default JdbcMoneyLedger.Authority ledger(HttpServletRequest request,Principal principal){throw new com.ias.trust.Problem(503,"FINANCE_LEDGER_AUTHORITY_PENDING");}
 default MoneyReviewStore.Authorizer review(HttpServletRequest request,Principal principal){throw new com.ias.trust.Problem(503,"FINANCE_REVIEW_AUTHORITY_PENDING");}
 default java.util.Map<String,Object> correctionContext(HttpServletRequest request,Principal principal,Identity owner,MoneyReviewStore.Review review){throw new com.ias.trust.Problem(503,"FINANCE_CORRECTION_EVIDENCE_PENDING");}
}
