package com.ias.stock;

import com.ias.trust.Problem;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class StockLedgerQueriesTest {
    @Test
    void repositoryCanStartWithClassBasedExceptionTranslation() {
        try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor.class,
                    () -> {
                        var processor = new org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor();
                        processor.setProxyTargetClass(true);
                        return processor;
                    });
            context.registerBean(JdbcTemplate.class, () -> db);
            context.registerBean(StockLedgerQueries.class);
            context.refresh();
            assertNotNull(context.getBean(StockLedgerQueries.class));
        }
    }
    private JdbcTemplate db;
    private StockLedgerQueries queries;
    private final String order = "10000000-0000-0000-0000-000000000001";
    private final String inbound = "20000000-0000-0000-0000-000000000001";
    private final String issue = "20000000-0000-0000-0000-000000000002";
    private final StockAccess.Principal actor = new StockAccess.Principal(1, 10, Set.of(11L));

    @BeforeEach
    void prepare() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try (var connection = source.getConnection()) {
            for (String file : List.of("V1__stock_inbound.sql", "V5__stock_execution.sql")) {
                RunScript.execute(connection, new InputStreamReader(Objects.requireNonNull(
                        getClass().getResourceAsStream("/db/migration/" + file)), StandardCharsets.UTF_8));
            }
        }
        db = new JdbcTemplate(source);
        queries = new StockLedgerQueries(db);
        db.update("INSERT INTO stock_warehouse VALUES(11,1,'authorized',TRUE),(12,1,'other warehouse',TRUE),(21,2,'foreign',TRUE)");
        db.update("INSERT INTO stock_operation VALUES(1,?,10,'digest',11,9007199254740993,10,10,'2026-11-14 00:00:00')", inbound);
        db.update("INSERT INTO stock_movement VALUES(1,?,11,9007199254740993,10,10,'source','batch','file','2026-11-14 00:00:00')", inbound);
        addExecution(1, 11, order, issue, "ISSUE", 9007199254740993L, 10, -2, -2, 0);
        addExecution(1, 12, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "USE", 101, 10, 0, 0, 1);
        addExecution(2, 21, UUID.randomUUID().toString(), UUID.randomUUID().toString(), "USE", 201, 20, 0, 0, 1);
    }

    private void addExecution(long tenant, long warehouse, String orderId, String operation,
                              String action, long part, long user, long onHand, long reserved, long used) {
        db.update("INSERT INTO stock_execution_order VALUES(?,?,?,0,'{}','ISSUED',NULL,0,'2026-11-14 00:00:00','2026-11-14 00:00:00')",
                tenant, orderId, warehouse);
        db.update("INSERT INTO stock_execution_operation VALUES(?,?,?,?,?,'digest','{}','2026-11-14 00:00:01')",
                tenant, operation, user, orderId, action);
        db.update("INSERT INTO stock_execution_movement VALUES(?,?,?,?,?,?)", tenant, operation, part, onHand, reserved, used);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object> page) {
        return (List<Map<String, Object>>) page.get("items");
    }

    @Test
    void combinesOwnerMovementsInStableOrderWithStringIdsAndSignedDeltas() {
        var page = queries.movements(actor, 11, null, null, 1, 20);
        assertEquals(2L, page.get("total"));
        var issueRow = items(page).getFirst();
        assertEquals(issue, issueRow.get("operationId"));
        assertEquals(order, issueRow.get("orderId"));
        assertEquals("9007199254740993", issueRow.get("partId"));
        assertEquals("10", issueRow.get("actorUserId"));
        assertEquals("-2", issueRow.get("onHandDelta"));
        assertEquals("-2", issueRow.get("reservedDelta"));
        assertEquals("0", issueRow.get("usedDelta"));
        assertEquals("2026-11-14T00:00:01Z", issueRow.get("recordedAt"));
        var inboundRow = items(page).getLast();
        assertEquals("INBOUND", inboundRow.get("source"));
        assertNull(inboundRow.get("orderId"));
        assertEquals("10", inboundRow.get("onHandDelta"));
        assertEquals("0", inboundRow.get("usedDelta"));
    }

    @Test
    void orderAndPartFiltersApplyBeforeCountAndExcludeUnrelatedInbound() {
        var selected = queries.movements(actor, 11, 9007199254740993L, order, 1, 20);
        assertEquals(1L, selected.get("total"));
        assertEquals(issue, items(selected).getFirst().get("operationId"));
        assertEquals(0L, queries.movements(actor, 11, 101L, null, 1, 20).get("total"));
        assertEquals(0L, queries.movements(actor, 11, null, UUID.randomUUID().toString(), 1, 20).get("total"));
        var emptyPage = queries.movements(actor, 11, null, null, 2, 20);
        assertEquals(2L, emptyPage.get("total"));
        assertTrue(items(emptyPage).isEmpty());
    }

    @Test
    void warehouseAndTenantIsolationRemainEnforcedAndDisabledWarehouseFailsClosed() {
        assertEquals(404, assertThrows(Problem.class, () -> queries.movements(actor, 12, null, null, 1, 20)).status);
        var forgedScope = new StockAccess.Principal(1, 10, Set.of(21L));
        assertEquals(404, assertThrows(Problem.class, () -> queries.movements(forgedScope, 21, null, null, 1, 20)).status);
        db.update("UPDATE stock_warehouse SET active=FALSE WHERE id=11");
        assertEquals(404, assertThrows(Problem.class, () -> queries.movements(actor, 11, null, null, 1, 20)).status);
    }

    @Test
    void rejectsMalformedAndForgedQueryBeforeCurrentAuthorityLookup() {
        var controller = new StockLedgerController(queries, new StockAccess.Unavailable());
        for (var parameter : List.of(Map.of("tenantId", "1"), Map.of("partId", "01"),
                Map.of("orderId", "bad"), Map.of("size", "10"), Map.of("page", "100001"))) {
            var request = new MockHttpServletRequest();
            parameter.forEach(request::setParameter);
            assertEquals(400, assertThrows(Problem.class, () -> controller.movements("11", request)).status);
        }
        var duplicate = new MockHttpServletRequest();
        duplicate.addParameter("page", new String[]{"1", "2"});
        assertEquals(400, assertThrows(Problem.class, () -> controller.movements("11", duplicate)).status);
        assertEquals(503, assertThrows(Problem.class, () -> controller.movements("11", new MockHttpServletRequest())).status);
    }
}
