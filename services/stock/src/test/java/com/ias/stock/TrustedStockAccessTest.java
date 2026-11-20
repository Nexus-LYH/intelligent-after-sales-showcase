package com.ias.stock;
import com.ias.trust.Problem;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TrustedStockAccessTest {
    final String ticket=UUID.randomUUID().toString(),assignment=UUID.randomUUID().toString();
    Map<String,Object> identity(){return new HashMap<>(Map.of("tenantId","1","userId","2","identityType","EMPLOYEE","enabled",true,"warehouseIds",List.of("11"),"checkedAt",Instant.now().toString()));}
    Map<String,Object> context(){return new HashMap<>(Map.of("ticketId",ticket,"assignmentId",assignment,"status","IN_PROGRESS","tenantId","1","engineerUserId","2","checkedAt",Instant.now().toString()));}
    MockHttpServletRequest request(){var r=new MockHttpServletRequest();r.addHeader("Cookie","IAS-SESSION-DEV=opaque");return r;}
    @Test void currentContextAndExplicitWarehouseScopesAreRequired(){var access=new TrustedStockAccess((e,p,b,t,d)->e.equals("stock-identity")?identity():context());assertEquals(Set.of(11L),access.catalog(request(),ticket,assignment).warehouseIds());}
    @Test void mismatchedOrStaleContextFailsClosed(){for(String field:List.of("tenantId","engineerUserId","assignmentId","status","checkedAt")){var c=context();c.put(field,field.equals("checkedAt")?Instant.now().minusSeconds(10).toString():"99");var access=new TrustedStockAccess((e,p,b,t,d)->e.equals("stock-identity")?identity():c);assertTrue(assertThrows(Problem.class,()->access.catalog(request(),ticket,assignment)).status>=400);}}
    @Test void noCookieAndDependencyFailureNeverGrantAccess(){assertEquals(401,assertThrows(Problem.class,()->new TrustedStockAccess((e,p,b,t,d)->identity()).balance(new MockHttpServletRequest(),11)).status);var access=new TrustedStockAccess((e,p,b,t,d)->{throw new Problem(503,"DEPENDENCY_UNAVAILABLE");});assertEquals(503,assertThrows(Problem.class,()->access.catalog(request(),ticket,assignment)).status);}
    @Test void manageMustForwardCsrfAndUseExplicitCurrentWarehouse(){
        var access=new TrustedStockAccess((e,p,b,t,d)->{assertEquals("STOCK_MANAGE",b.get("purpose"));assertEquals("current-token",b.get("csrfToken"));return identity();});
        assertEquals(403,assertThrows(Problem.class,()->access.manage(request(),11)).status);
        var r=request();r.addHeader("X-CSRF-TOKEN","current-token");assertEquals(2,access.manage(r,11).userId());
        assertEquals(404,assertThrows(Problem.class,()->access.manage(r,22)).status);
    }
}
