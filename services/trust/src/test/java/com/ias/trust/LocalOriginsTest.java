package com.ias.trust;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class LocalOriginsTest {
 @Test void onlyNotificationListReadHasExtendedBoundedTimeout(){assertEquals(8000,Chain.readTimeout("gateway-notification","GET","/api/notification/messages?page=1&size=20"));assertEquals(8000,Chain.readTimeout("gateway-notification","GET","/api/notification/messages"));assertEquals(2000,Chain.readTimeout("gateway-notification","POST","/api/notification/messages"));assertEquals(2000,Chain.readTimeout("gateway-notification","GET","/api/notification/messages/123"));assertEquals(2000,Chain.readTimeout("gateway-ticket","GET","/api/notification/messages"));}
 @Test void warehouseReferenceOnlyAcceptsIdentityToStockPost(){String p="/internal/stock/warehouse-references";assertTrue(Chain.allowedEdge("identity-stock","POST",p));assertFalse(Chain.allowedEdge("identity-stock","GET",p));assertFalse(Chain.allowedEdge("gateway-stock","POST",p));assertFalse(Chain.allowedEdge("ticket-stock","POST",p));}
 @Test void ownerResultRouteAcceptsOnlyTicketStockPost(){String path="/internal/stock/finance-part-executions/owner-result";assertTrue(Chain.allowedEdge("ticket-stock","POST",path));assertFalse(Chain.allowedEdge("ticket-stock","GET",path));assertFalse(Chain.allowedEdge("finance-stock","POST",path));assertFalse(Chain.allowedEdge("gateway-stock","POST",path));}
 @Test void fixedLocalOriginsAreAcceptedAndOthersAreRejected() {
  for (String origin : new String[]{"http://127.0.0.1:4173", "http://127.0.0.1:4174", "http://127.0.0.1:5173"}) {
   var request = new MockHttpServletRequest();request.addHeader("Origin", origin);
   assertDoesNotThrow(() -> Chain.source(request));
  }
  for (String origin : new String[]{"http://localhost:4173", "http://127.0.0.1:4175", "https://example.invalid"}) {
   var request = new MockHttpServletRequest();request.addHeader("Origin", origin);
   var failure = assertThrows(Problem.class, () -> Chain.source(request));assertEquals(403, failure.status);
  }
  var duplicate = new MockHttpServletRequest();duplicate.addHeader("Origin", "http://127.0.0.1:4173");duplicate.addHeader("Origin", "http://127.0.0.1:4174");
  assertEquals(403, assertThrows(Problem.class, () -> Chain.source(duplicate)).status);
 }
}
