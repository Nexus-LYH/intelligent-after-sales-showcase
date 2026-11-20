package com.ias.trust;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ServiceProbeTest {
 @Test void onlyTicketMayRequestStockCleanupProof(){String path="/internal/stock/parts-cleanup-proof";assertTrue(Chain.allowedEdge("ticket-stock","POST",path));assertFalse(Chain.allowedEdge("ticket-stock","GET",path));for(String edge:new String[]{"gateway-stock","approval-stock","notification-stock","finance-stock"})assertFalse(Chain.allowedEdge(edge,"POST",path));}
 @Test void onlyFinanceCanReadOriginalPartBinding(){String p="/internal/approval/finance-part-binding";assertTrue(Chain.allowedEdge("finance-approval","POST",p));assertFalse(Chain.allowedEdge("finance-approval","GET",p));for(String edge:new String[]{"gateway-approval","stock-approval","ticket-approval","notification-approval"})assertFalse(Chain.allowedEdge(edge,"POST",p));}
 @Test void catalogProofIsPrivateToApproval(){String path="/internal/stock/part-catalog-proof";assertTrue(Chain.allowedEdge("approval-stock","POST",path));assertFalse(Chain.allowedEdge("approval-stock","GET",path));for(String edge:new String[]{"gateway-stock","ticket-stock","finance-stock","notification-stock"})assertFalse(Chain.allowedEdge(edge,"POST",path));}
 @Test void onlyTicketMayPostPermanentPartRefusal(){
  String path="/internal/stock/finance-part-executions/refuse";
  assertTrue(Chain.allowedEdge("ticket-stock","POST",path));assertFalse(Chain.allowedEdge("ticket-stock","GET",path));
  for(String edge:new String[]{"gateway-stock","approval-stock","notification-stock","finance-stock"})assertFalse(Chain.allowedEdge(edge,"POST",path));
 }
 @Test void partPermitProofIsOnlyAvailableToStockOnPost(){
  String path="/internal/ticket/part-fee-permits/proof";
  assertTrue(Chain.allowedEdge("stock-ticket","POST",path));assertFalse(Chain.allowedEdge("stock-ticket","GET",path));
  for(String edge:new String[]{"gateway-ticket","finance-ticket","approval-ticket","notification-ticket"})assertFalse(Chain.allowedEdge(edge,"POST",path));
 }
 @Test void probeHasFixedRoleSpecificEdgesAndPorts(){assertEquals("stock-ticket",ServiceProbeCli.edge("ticket"));assertEquals("ticket-finance",ServiceProbeCli.edge("finance"));assertEquals(8443,ServiceProbeCli.port("ticket"));assertThrows(IllegalArgumentException.class,()->ServiceProbeCli.edge("gateway"));}
 @Test void authenticatedExactEdgeReturnsRoleAndInvalidTokensFail() throws Exception {
  String secret="a".repeat(43);
  try(var chain=mockStatic(Chain.class)){
   chain.when(Chain::active).thenReturn(true);chain.when(Chain::role).thenReturn("stock");
   chain.when(()->Chain.secret("ticket-stock")).thenReturn(secret);
   chain.when(()->Chain.allowedEdge(anyString(),anyString(),anyString())).thenCallRealMethod();
   chain.when(()->Chain.single(any(),anyString())).thenCallRealMethod();
   var request=request("ticket-stock","Bearer "+secret,true);var response=new MockHttpServletResponse();var next=new MockFilterChain();
   new EdgeFilter("stock").doFilter(request,response,next);
   assertEquals(200,response.getStatus());assertEquals("stock",Chain.JSON.readTree(response.getContentAsString()).get("role").asText());assertNull(next.getRequest());
   var bad=new MockHttpServletResponse();new EdgeFilter("stock").doFilter(request("ticket-stock","Bearer wrong",true),bad,new MockFilterChain());assertEquals(403,bad.getStatus());
   var insecure=new MockHttpServletResponse();new EdgeFilter("stock").doFilter(request("ticket-stock","Bearer "+secret,false),insecure,new MockFilterChain());assertEquals(403,insecure.getStatus());
   var wrongEdge=new MockHttpServletResponse();new EdgeFilter("stock").doFilter(request("gateway-stock","Bearer "+secret,true),wrongEdge,new MockFilterChain());assertEquals(403,wrongEdge.getStatus());
  }
 }
 private MockHttpServletRequest request(String edge,String bearer,boolean secure){
  var request=new MockHttpServletRequest("POST","/internal/service-probe");request.setSecure(secure);
  request.addHeader("X-IAS-Edge",edge);request.addHeader("Authorization",bearer);request.addHeader("X-IAS-Deadline",Long.toString(System.currentTimeMillis()+5000));request.addHeader("X-IAS-Trace","test");return request;
 }
}
