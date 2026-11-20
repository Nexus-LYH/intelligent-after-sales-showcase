package com.ias.stock;

import com.ias.trust.Chain;
import com.ias.trust.Problem;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WarehouseReferencesControllerTest {
 @Test void missingWarehousePreserves404AtHttpBoundary()throws Exception{
  var db=mock(JdbcTemplate.class);
  when(db.queryForObject("SELECT COUNT(*) FROM stock_warehouse WHERE tenant_id=? AND id=? AND active=TRUE",Long.class,7L,53L)).thenReturn(0L);
  var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new WarehouseReferencesController(db)).build();
  try(var chain=mockStatic(Chain.class)){
   chain.when(Chain::active).thenReturn(true);chain.when(Chain::role).thenReturn("stock");
   mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/internal/stock/warehouse-references")
    .secure(true).requestAttr("ias.chain.edge","identity-stock").contentType("application/json").content("{\"tenantId\":\"7\",\"warehouseIds\":[\"53\"]}"))
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound())
    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("TARGET_NOT_ACCESSIBLE"));
  }
 }
 @Test void onlyIdentityCanValidateActiveWarehousesInExactTenant(){
  var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1");var db=new JdbcTemplate(source);db.execute("CREATE TABLE stock_warehouse(id BIGINT PRIMARY KEY,tenant_id BIGINT,active BOOLEAN)");db.update("INSERT INTO stock_warehouse VALUES(11,1,TRUE),(12,1,FALSE),(21,2,TRUE)");
  var controller=new WarehouseReferencesController(db);var request=new MockHttpServletRequest();request.setSecure(true);request.setAttribute("ias.chain.edge","identity-stock");
  try(var chain=mockStatic(Chain.class,CALLS_REAL_METHODS)){chain.when(Chain::active).thenReturn(true);chain.when(Chain::role).thenReturn("stock");var valid=controller.check(Map.of("tenantId","1","warehouseIds",List.of("11")),request);assertEquals(true,valid.get("valid"));assertEquals(List.of("11"),valid.get("warehouseIds"));assertEquals(404,assertThrows(Problem.class,()->controller.check(Map.of("tenantId","1","warehouseIds",List.of("21")),request)).status);assertEquals(404,assertThrows(Problem.class,()->controller.check(Map.of("tenantId","1","warehouseIds",List.of("12")),request)).status);assertEquals(400,assertThrows(Problem.class,()->controller.check(Map.of("tenantId","1","warehouseIds",List.of("11","11")),request)).status);request.setAttribute("ias.chain.edge","gateway-stock");assertEquals(403,assertThrows(Problem.class,()->controller.check(Map.of("tenantId","1","warehouseIds",List.of("11")),request)).status);}
 }
}
