package com.ias.stock;

import com.ias.trust.Problem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Read-only acceptance against existing local owner facts using the actual runtime principal. */
@EnabledIfEnvironmentVariable(named="IAS_LEDGER_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:3307/ias_stock_test.*")
class StockLedgerMysqlReadTest {
    @Test
    @SuppressWarnings("unchecked")
    void readsRealOwnerMovementsWithRuntimeGrantsAndCurrentWarehouseScope() {
        var source = new DriverManagerDataSource(System.getenv("IAS_LEDGER_MYSQL_URL"),
                System.getenv("IAS_LEDGER_MYSQL_USER"), System.getenv("IAS_LEDGER_MYSQL_PASSWORD"));
        var db = new JdbcTemplate(source);
        assertEquals("ias_stock_test", db.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(3307, db.queryForObject("SELECT @@port", Integer.class));
        long tenant = Long.parseLong(System.getenv("IAS_LEDGER_TENANT"));
        long warehouse = Long.parseLong(System.getenv("IAS_LEDGER_WAREHOUSE"));
        var actor = new StockAccess.Principal(tenant, 3831, Set.of(warehouse));
        var queries = new StockLedgerQueries(db);
        String order = System.getenv("IAS_LEDGER_ORDER");
        var page = queries.movements(actor, warehouse, null, order, 1, 20);
        var items = (List<Map<String,Object>>) page.get("items");
        assertTrue((Long) page.get("total") > 0);
        assertFalse(items.isEmpty());
        for (var row : items) {
            assertEquals(order, row.get("orderId"));
            assertInstanceOf(String.class, row.get("operationId"));
            assertInstanceOf(String.class, row.get("actorUserId"));
            assertInstanceOf(String.class, row.get("onHandDelta"));
            assertNotNull(row.get("recordedAt"));
        }
        var combined = queries.movements(actor, warehouse, null, null, 1, 20);
        assertTrue((Long) combined.get("total") >= (Long) page.get("total"));
        var noScope = new StockAccess.Principal(tenant, 3831, Set.of());
        assertEquals(404, assertThrows(Problem.class,
                () -> queries.movements(noScope, warehouse, null, order, 1, 20)).status);
    }
}
