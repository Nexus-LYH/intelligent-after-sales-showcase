package com.ias.finance;
import com.ias.trust.Problem;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import java.time.Instant;
import static com.ias.finance.FeeGroup.*;
import static com.ias.finance.JdbcFeeGroups.*;
import static org.junit.jupiter.api.Assertions.*;

class TrustedPartOwnerTest {
 @Test void trustedApprovalBindsCompanyOrderVersionPartQuantityAndUnit(){
  var owner=new Identity(1,2,UUID.randomUUID());UUID id=UUID.randomUUID(),ticket=UUID.randomUUID(),round=UUID.randomUUID(),approval=UUID.randomUUID();var binding=new PartBinding(approval,1,3,4,5,"pcs","FULL_LINE_SINGLE_USE_V1");var details=new QuoteDetails(true,"test",Map.of(id,"PART"),Map.of(id,binding));var command=new Submit(UUID.randomUUID(),UUID.randomUUID(),0,Set.of(),List.of(new PlanLine(id,ticket,round,100,false)),new PublishedRule("v1",200));
  var proof=new HashMap<String,Object>(Map.of("tenantId","1","companyId","2","approvalId",approval.toString(),"approvalVersion","1","warehouseId","3","ticketId",ticket.toString(),"roundId",round.toString(),"unitCode","pcs","chargeMode","CUSTOMER_PAID","lines",List.of(Map.of("partId","4","quantity","5"))));
  var access=new TrustedFinanceAccess((edge,path,body,r)->{assertEquals("finance-approval",edge);assertEquals("/internal/approval/finance-part-binding",path);var result=new HashMap<>(proof);result.put("checkedAt",Instant.now().toString());return result;});var request=new MockHttpServletRequest();request.addHeader("Cookie","IAS-SESSION-DEV=synthetic");var actor=new Actor(1,10);
  assertDoesNotThrow(()->access.partOwners(request,actor,owner,command,details));
  for(String field:List.of("companyId","approvalVersion","warehouseId","ticketId","roundId","unitCode")){Object old=proof.put(field,"other");assertEquals("FINANCE_PART_OWNER_FACT_MISMATCH",assertThrows(Problem.class,()->access.partOwners(request,actor,owner,command,details)).code);proof.put(field,old);}
  proof.put("lines",List.of(Map.of("partId","4","quantity","4")));assertThrows(Problem.class,()->access.partOwners(request,actor,owner,command,details));
 }
 @Test void legacyPaidPartWithoutBindingRemainsClosed(){var id=UUID.randomUUID();assertThrows(Problem.class,()->TrustedFinanceAccess.requirePartShape(new QuoteDetails(true,"test",Map.of(id,"PART"))));}
}
