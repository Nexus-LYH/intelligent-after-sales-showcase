package com.ias.stock;

import com.ias.trust.Problem;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;

/** All identities and scopes originate at current trusted adapters, never JSON query authority. */
public interface StockAccess {
    record Principal(long tenantId,long userId,Set<Long> warehouseIds) {
        public Principal {if(tenantId<=0||userId<=0)throw new Problem(403,"ACCESS_DENIED");warehouseIds=Set.copyOf(warehouseIds);}
    }
    Principal catalog(HttpServletRequest request,String ticketId,String assignmentId);
    Principal balance(HttpServletRequest request,long warehouseId);
    Principal approval(HttpServletRequest request,String sessionToken);
    default Principal manage(HttpServletRequest request,long warehouseId){throw new Problem(503,"STOCK_TRUST_NOT_CONFIGURED");}
    final class Unavailable implements StockAccess {
        private Problem unavailable(){return new Problem(503,"STOCK_TRUST_NOT_CONFIGURED");}
        public Principal catalog(HttpServletRequest r,String t,String a){throw unavailable();}
        public Principal balance(HttpServletRequest r,long w){throw unavailable();}
        public Principal approval(HttpServletRequest r,String token){throw unavailable();}
    }
}
