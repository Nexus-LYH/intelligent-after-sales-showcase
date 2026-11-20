package com.ias.stock;

import com.ias.trust.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PartFeeNotificationContextTest {
 @Test void exactUseFactAndCurrentScopeRequired()throws Exception{
  var f=new FinancePartExecutionStoreTest();f.setup();String event=FinancePartExecutionStoreTest.uuid();var receipt=f.store.execute(f.command(event));var body=new LinkedHashMap<String,Object>();body.put("eventId",event);body.put("tenantId","1");body.put("eventType","inventory.consumed");body.put("companyId","21");body.put("ticketId",f.ticket);body.put("roundId",f.round);body.put("feeGroupId",f.group);body.put("generation","3");body.put("executionReceiptId",receipt.get("executionReceiptId"));body.put("lineId",f.line);body.put("warehouseId","11");body.put("partId","101");body.put("quantity","2");body.put("amountCents","1200");body.put("purpose","DELIVERY");var request=new MockHttpServletRequest();request.setSecure(true);request.setAttribute("ias.chain.edge","notification-stock");
  try(var chain=mockStatic(Chain.class,CALLS_REAL_METHODS)){chain.when(Chain::active).thenReturn(true);chain.when(Chain::role).thenReturn("stock");var controller=new PartFeeNotificationContextController(f.db);var fact=controller.context(body,request);assertEquals(true,fact.get("currentScope"));assertEquals(receipt.get("executionReceiptId"),fact.get("executionReceiptId"));var wrong=new LinkedHashMap<>(body);wrong.put("amountCents","1201");assertEquals(409,assertThrows(Problem.class,()->controller.context(wrong,request)).status);f.sql("UPDATE stock_finance_generation SET generation=4");assertEquals(409,assertThrows(Problem.class,()->controller.context(body,request)).status);f.sql("UPDATE stock_finance_generation SET generation=3");f.sql("UPDATE stock_execution_operation SET action_name='ISSUE'");assertEquals(409,assertThrows(Problem.class,()->controller.context(body,request)).status);request.setAttribute("ias.chain.edge","ticket-stock");assertEquals(403,assertThrows(Problem.class,()->controller.context(body,request)).status);}
 }
}
