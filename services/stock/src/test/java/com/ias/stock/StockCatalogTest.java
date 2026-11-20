package com.ias.stock;

import com.ias.trust.Problem;
import org.h2.jdbcx.JdbcDataSource;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StockCatalogTest {
    JdbcTemplate db; StockCatalog catalog;
    @BeforeEach void prepare() throws Exception {
        var source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        try(var connection=source.getConnection()) {
            RunScript.execute(connection,new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V1__stock_inbound.sql")), StandardCharsets.UTF_8));
        }
        db=new JdbcTemplate(source); catalog=new StockCatalog(db);
        db.execute("INSERT INTO stock_warehouse VALUES(11,1,'visible',TRUE),(12,1,'disabled',FALSE),(21,2,'other tenant',TRUE)");
        db.execute("INSERT INTO stock_part VALUES(101,1,'A','part','model','pcs',TRUE,0),(102,1,'B','disabled','model','pcs',FALSE,0),(201,2,'A','other tenant','model','pcs',TRUE,0)");
        db.execute("INSERT INTO stock_balance VALUES(1,11,101,10,3,0),(2,21,201,999,0,0)");
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> items(Map<String,Object> page){return (List<Map<String,Object>>)page.get("items");}
    @Test void stockCatalogueFiltersWarehouseScopeAndCountBeforePagination(){
        var actor=new StockAccess.Principal(1,10,Set.of(11L,12L,21L));var page=catalog.warehouseCatalogue(actor,1,20);assertEquals(1L,page.get("total"));assertEquals(List.of(Map.of("id","11","name","visible")),items(page));assertEquals(0L,catalog.warehouseCatalogue(new StockAccess.Principal(1,10,Set.of()),1,20).get("total"));
    }
    @Test void stockPartsContainMetadataEvenWithoutAnyBalanceRow(){
        db.execute("INSERT INTO stock_part VALUES(103,1,'C','no balance','M3','box',TRUE,0)");var page=catalog.warehouseParts(new StockAccess.Principal(1,10,Set.of(11L)),11,1,20);assertEquals(2L,page.get("total"));assertTrue(items(page).stream().anyMatch(p->p.get("id").equals("103")&&p.get("unit").equals("box")));assertEquals(404,assertThrows(Problem.class,()->catalog.warehouseParts(new StockAccess.Principal(1,10,Set.of()),11,1,20)).status);
    }
    @Test void engineerSelectorsOnlyExposeEnabledOwnTenantCatalogueWithoutBalances(){
        var warehouses=catalog.warehouses(1,1,20);assertEquals(1L,warehouses.get("total"));
        assertEquals(Map.of("id","11","name","visible"),items(warehouses).getFirst());
        var parts=catalog.parts(1,11,1,20);assertEquals(1L,parts.get("total"));
        assertEquals(Set.of("id","code","name","model","unit"),items(parts).getFirst().keySet());
        assertEquals("101",items(parts).getFirst().get("id"));
    }
    @Test void inaccessibleOrDisabledWarehouseAndPartFailClosed(){
        var actor=new StockAccess.Principal(1,10,Set.of());
        for(long warehouse:List.of(12L,21L,999L)) assertEquals(404,assertThrows(Problem.class,()->catalog.parts(1,warehouse,1,20)).status);
        for(long part:List.of(102L,201L,999L)) assertEquals(404,assertThrows(Problem.class,()->catalog.approval(actor,11,part)).status);
        assertEquals("101",catalog.approval(actor,11,101).get("partId"));
    }
    @Test void balanceRequiresExplicitCurrentWarehouseScope(){
        assertEquals(404,assertThrows(Problem.class,()->catalog.balances(new StockAccess.Principal(1,10,Set.of()),11,null,1,20)).status);
        var page=catalog.balances(new StockAccess.Principal(1,10,Set.of(11L)),11,null,1,20);
        assertEquals("7",items(page).getFirst().get("available"));
        assertEquals(404,assertThrows(Problem.class,()->catalog.balances(new StockAccess.Principal(1,10,Set.of(21L)),21,null,1,20)).status);
    }
    @Test void unconfiguredAuthorityCannotBeReplacedByClientIdentityHeaders(){
        var controller=new StockController(catalog,new StockAccess.Unavailable());var request=new MockHttpServletRequest();
        request.setParameter("ticketId",UUID.randomUUID().toString());request.setParameter("assignmentId",UUID.randomUUID().toString());
        request.addHeader("X-Tenant-Id","1");request.addHeader("X-User-Id","10");
        assertEquals(503,assertThrows(Problem.class,()->controller.warehouses(request)).status);
    }
    @Test void duplicateUnknownAndOversizedQueryParametersAreRejected(){
        var controller=new StockController(catalog,new StockAccess.Unavailable());
        var duplicate=new MockHttpServletRequest();duplicate.addParameter("page",new String[]{"1","2"});
        assertEquals(400,assertThrows(Problem.class,()->controller.warehouses(duplicate)).status);
        var forged=new MockHttpServletRequest();forged.setParameter("tenantId","1");
        assertEquals(400,assertThrows(Problem.class,()->controller.warehouses(forged)).status);
        var large=new MockHttpServletRequest();large.setParameter("page","100001");
        assertEquals(400,assertThrows(Problem.class,()->StockController.paging(large)).status);
    }
    @Test void noStoreAppliesToSuccessAndAuthorizationFailures() throws Exception {
        var filter=new StockNoStore();
        for(int status:List.of(200,401,403,503)) {
            var response=new MockHttpServletResponse();filter.doFilter(new MockHttpServletRequest(),response,(r,s)->((jakarta.servlet.http.HttpServletResponse)s).setStatus(status));
            assertEquals(status,response.getStatus());assertEquals("no-store",response.getHeader("Cache-Control"));
        }
    }
}
