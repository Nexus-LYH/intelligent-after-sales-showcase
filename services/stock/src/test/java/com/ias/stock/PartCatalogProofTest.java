package com.ias.stock;
import com.ias.trust.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PartCatalogProofTest {
 @Test void exactScopedCatalogFactsAndDisabledOrForeignMaterialRejected()throws Exception{
  var f=new FinancePartExecutionStoreTest();f.setup();var controller=new PartCatalogProofController(new JdbcTemplate(f.db));var r=new MockHttpServletRequest();r.setSecure(true);r.setAttribute("ias.chain.edge","approval-stock");String raw=Chain.JSON.writeValueAsString(Map.of("tenantId","1","warehouseId","11","partId","101"));
  try(var chain=mockStatic(Chain.class,CALLS_REAL_METHODS)){
   chain.when(Chain::active).thenReturn(true);chain.when(Chain::role).thenReturn("stock");var proof=controller.proof(raw,r);assertEquals("pcs",proof.get("unitCode"));assertFalse(proof.containsKey("onHand"));
   f.sql("UPDATE stock_part SET active=FALSE");assertEquals(404,assertThrows(Problem.class,()->controller.proof(raw,r)).status);f.sql("UPDATE stock_part SET active=TRUE");
   String foreign=Chain.JSON.writeValueAsString(Map.of("tenantId","2","warehouseId","11","partId","101"));assertEquals(404,assertThrows(Problem.class,()->controller.proof(foreign,r)).status);
   r.setAttribute("ias.chain.edge","finance-stock");assertEquals(403,assertThrows(Problem.class,()->controller.proof(raw,r)).status);
  }
 }
}
