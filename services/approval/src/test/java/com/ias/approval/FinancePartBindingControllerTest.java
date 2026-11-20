package com.ias.approval;
import com.ias.trust.*;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinancePartBindingControllerTest {
 String ticket=UUID.randomUUID().toString(),round=UUID.randomUUID().toString(),assignment=UUID.randomUUID().toString(),approval=UUID.randomUUID().toString();Map<String,Object> current,original,body;FinancePartBindingController controller;MockHttpServletRequest request;
 @BeforeEach void setup(){
  current=new HashMap<>(Map.of("tenantId","1","ticketId",ticket,"roundId",round,"companyId","2","assignmentId",assignment,"engineerUserId","10"));
  original=new HashMap<>(Map.of("tenantId","1","ticketId",ticket,"roundId",round,"assignmentId",assignment,"applicantUserId","10","chargeMode","CUSTOMER_PAID","approvalId",approval,"approvalVersion","2","warehouseId","3","lines",List.of(Map.of("partId","4","quantity","5"))));
  controller=new FinancePartBindingController((t,a,p,w)->original,(edge,path,b,r)->{Map<String,Object> result;if(edge.equals("approval-identity"))result=new HashMap<>(Map.of("tenantId","1","userId","20","enabled",true,"identityType","EMPLOYEE","permissions",List.of("finance.quote.read"),"companyIds",List.of("2")));else if(edge.equals("approval-ticket"))result=new HashMap<>(current);else result=new HashMap<>(Map.of("tenantId","1","warehouseId","3","partId","4","unitCode","pcs","warehouseActive",true,"partActive",true));result.put("checkedAt",Instant.now().toString());return result;});
  body=Map.of("sessionToken","synthetic","approvalId",approval,"warehouseId","3","ticketId",ticket,"roundId",round);request=new MockHttpServletRequest();request.setSecure(true);request.setAttribute("ias.chain.edge","finance-approval");
 }
 @Test void exactCurrentPaidOrderReturnsVersionQuantityAndUnit(){try(var chain=mockStatic(Chain.class,CALLS_REAL_METHODS)){chain.when(Chain::active).thenReturn(true);chain.when(Chain::role).thenReturn("approval");var result=controller.binding(body,request);assertEquals("2",result.get("approvalVersion"));assertEquals("pcs",result.get("unitCode"));assertEquals(original.get("lines"),result.get("lines"));}}
 @Test void wrongCompanyRoundAssignmentOrPayerCannotReadBinding(){try(var chain=mockStatic(Chain.class,CALLS_REAL_METHODS)){chain.when(Chain::active).thenReturn(true);chain.when(Chain::role).thenReturn("approval");current.put("companyId","99");assertEquals(404,assertThrows(Problem.class,()->controller.binding(body,request)).status);current.put("companyId","2");current.put("roundId",UUID.randomUUID().toString());assertEquals(404,assertThrows(Problem.class,()->controller.binding(body,request)).status);current.put("roundId",round);original.put("assignmentId",UUID.randomUUID().toString());assertEquals(409,assertThrows(Problem.class,()->controller.binding(body,request)).status);original.put("assignmentId",assignment);original.put("chargeMode","PROVIDER_PAID");assertEquals(409,assertThrows(Problem.class,()->controller.binding(body,request)).status);}}
}
