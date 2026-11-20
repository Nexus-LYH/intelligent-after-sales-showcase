package com.ias.gateway;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StockLedgerGatewayBoundaryTest {
    @Test
    void exposesOnlyTheWarehouseScopedReadRoute() {
        String path = "/api/stock/warehouses/9007199254740993/movements";
        assertTrue(GatewayApplication.allowed("GET", path));
        for (String method : new String[]{"POST", "PUT", "DELETE", "PATCH"}) {
            assertFalse(GatewayApplication.allowed(method, path));
        }
        for (String invalid : new String[]{"/api/stock/movements",
                "/api/stock/warehouses/0/movements", "/api/stock/warehouses/01/movements",
                "/api/stock/warehouses/1/movements/extra", "/api/stock/warehouses/1/movements%2f"}) {
            assertFalse(GatewayApplication.allowed("GET", invalid));
        }
    }
}
