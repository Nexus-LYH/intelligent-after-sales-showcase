package com.ias.finance;

import com.ias.trust.Problem;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TrustedFinanceReviewAccessTest {
 @Test void moneyReviewerDoesNotNeedQuoteReadAndExpiredSessionCannotFallback(){
  var request=new MockHttpServletRequest();request.addHeader("Cookie","IAS-SESSION-DEV=synthetic");
  var purposes=new ArrayList<String>();var expired=new boolean[]{false};
  var access=new TrustedFinanceAccess((edge,path,body,r)->{
   purposes.add(body.get("purpose").toString());if(expired[0])throw new Problem(401,"SESSION_INVALID");
   if(!"MONEY_READ".equals(body.get("purpose")))throw new Problem(403,"ACCESS_DENIED");
   return Map.of("tenantId","1","userId","10","identityType","EMPLOYEE","enabled",true,"permissions",List.of("finance.money.read"),"checkedAt",Instant.now().toString());
  });
  assertEquals("EMPLOYEE",access.reviewPrincipal(request).type());assertEquals(List.of("MONEY_READ"),purposes);
  expired[0]=true;purposes.clear();assertEquals(401,assertThrows(Problem.class,()->access.reviewPrincipal(request)).status);assertEquals(List.of("MONEY_READ"),purposes);
 }
 @Test void customerFallbackKeepsCustomerTypeAndDoesNotAdmitQuoteOnlyEmployees(){
  var request=new MockHttpServletRequest();request.addHeader("Cookie","IAS-SESSION-DEV=synthetic");var type=new String[]{"CUSTOMER"};
  var access=new TrustedFinanceAccess((edge,path,body,r)->{
   if("MONEY_READ".equals(body.get("purpose")))throw new Problem(403,"ACCESS_DENIED");
   return Map.of("tenantId","1","userId","10","identityType",type[0],"enabled",true,"checkedAt",Instant.now().toString());
  });
  assertEquals("CUSTOMER",access.reviewPrincipal(request).type());type[0]="EMPLOYEE";assertEquals(403,assertThrows(Problem.class,()->access.reviewPrincipal(request)).status);
 }
 @Test void customerConfirmationRequiresCurrentCompanyAdministratorAndFinancialApprovalRequiresScope(){
  var owner=new FeeGroup.Identity(1,2,UUID.randomUUID());var actor=new JdbcFeeGroups.Actor(1,10);var review=UUID.randomUUID();var line=UUID.randomUUID();var proposal=new MoneyReviewStore.Proposal(review,"REDUCTION",1,1,line,UUID.randomUUID(),line,50,"a".repeat(64),null,null,"reason");
  var request=new MockHttpServletRequest();request.addHeader("Cookie","IAS-SESSION-DEV=synthetic");request.addHeader("Origin","http://127.0.0.1:5173");request.addHeader("X-CSRF-TOKEN","csrf");
  var active=new boolean[]{true};var admin=new boolean[]{true};var access=new TrustedFinanceAccess((edge,path,body,r)->{
   var result=new HashMap<String,Object>();if(edge.equals("finance-identity"))result.putAll(Map.of("tenantId","1","userId","10","identityType","CUSTOMER","enabled",active[0],"permissions",List.of("finance.quote.read","finance.quote.confirm")));
   else result.putAll(Map.of("tenantId","1","companyId","2","userId","10","active",true,"administrator",admin[0]));result.put("checkedAt",Instant.now().toString());return result;});
  var principal=new FinanceAccess.Principal(actor,"CUSTOMER");var authority=access.review(request,principal);assertDoesNotThrow(()->authority.authorize(actor,owner,"CUSTOMER_CONFIRMED",proposal));admin[0]=false;assertEquals(403,assertThrows(Problem.class,()->authority.authorize(actor,owner,"CUSTOMER_CONFIRMED",proposal)).status);admin[0]=true;active[0]=false;assertThrows(Problem.class,()->authority.authorize(actor,owner,"CUSTOMER_CONFIRMED",proposal));
  assertEquals(403,assertThrows(Problem.class,()->authority.authorize(actor,owner,"FINANCE_APPROVED",proposal)).status);
 }
}
