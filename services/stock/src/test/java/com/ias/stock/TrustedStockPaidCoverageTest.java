package com.ias.stock;
import com.ias.trust.Problem;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.time.Instant;
import java.util.*;
import static com.ias.stock.StockExecutions.*;
import static org.junit.jupiter.api.Assertions.*;

class TrustedStockPaidCoverageTest {
 private final UUID approvalId=UUID.randomUUID(),ticket=UUID.randomUUID(),commandId=UUID.randomUUID(),order=UUID.randomUUID();
 private Approval approval(String mode,long qty){return new Approval(approvalId,1,1,11,ticket.toString(),UUID.randomUUID().toString(),UUID.randomUUID().toString(),2,Instant.now(),mode,new TreeMap<>(Map.of(99L,qty)));}
 private Command command(StockExecutions.Action action,long qty){return new Command(commandId,order,action,0,action==StockExecutions.Action.ISSUE?new TreeMap<>():new TreeMap<>(Map.of(99L,qty)));}
 @Test void paidCoverageRequiresExactOwnerFactAndFreshDigest(){
  var req=new MockHttpServletRequest();RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));
  try{
   var calls=new ArrayList<Map<String,Object>>();
   var access=new TrustedStockExecutions(null,null,(edge,path,body,r)->{assertEquals("stock-approval",edge);assertEquals("/internal/approval/paid-coverage",path);assertSame(req,r);calls.add(new LinkedHashMap<>(body));return new LinkedHashMap<>(Map.of("tenantId","1","approvalId",approvalId.toString(),"ticketId",ticket.toString(),"warehouseId","11","partId","99","quantity","1","coverageDigest","a".repeat(64)));});
   access.requirePaidCoverage(new Actor(1,2),command(StockExecutions.Action.ISSUE,1),approval("CUSTOMER_PAID",1));
   assertEquals(1,calls.size());assertEquals("1",calls.getFirst().get("tenantId"));assertEquals("99",calls.getFirst().get("partId"));assertEquals("1",calls.getFirst().get("quantity"));
   var mismatch=new TrustedStockExecutions(null,null,(e,p,b,r)->new LinkedHashMap<>(Map.of("tenantId","1","approvalId",approvalId.toString(),"ticketId",ticket.toString(),"warehouseId","11","partId","99","quantity","1","coverageDigest","x".repeat(64))));
   assertEquals(503,assertThrows(Problem.class,()->mismatch.requirePaidCoverage(new Actor(1,2),command(StockExecutions.Action.ISSUE,1),approval("CUSTOMER_PAID",1))).status);
   assertEquals(503,assertThrows(Problem.class,()->access.requirePaidCoverage(new Actor(1,2),command(StockExecutions.Action.USE,2),approval("CUSTOMER_PAID",1))).status);
   assertEquals(503,assertThrows(Problem.class,()->access.requirePaidCoverage(new Actor(1,2),command(StockExecutions.Action.ISSUE,1),approval("FREE",1))).status);
  }finally{RequestContextHolder.resetRequestAttributes();}
 }
}
