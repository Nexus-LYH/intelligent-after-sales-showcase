package com.ias.stock;
import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

/** Exact catalog facts for an already scoped approval; never exposes warehouse balances. */
@RestController
public final class PartCatalogProofController {
 private final JdbcTemplate db;
 public PartCatalogProofController(JdbcTemplate db){this.db=db;}
 @PostMapping("/internal/stock/part-catalog-proof")
 public Map<String,Object> proof(@RequestBody String raw,HttpServletRequest r){
  if(!Chain.active()||!"stock".equals(Chain.role())||!r.isSecure()||!"approval-stock".equals(r.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");
  if(!r.getParameterMap().isEmpty())throw StockController.bad();var b=FinancePartExecutionController.body(raw);
  if(!b.keySet().equals(Set.of("tenantId","warehouseId","partId")))throw StockController.bad();
  long tenant=StockController.id(b.get("tenantId")),warehouse=StockController.id(b.get("warehouseId")),part=StockController.id(b.get("partId"));
  var rows=db.queryForList("SELECT p.unit FROM stock_part p JOIN stock_warehouse w ON w.tenant_id=p.tenant_id WHERE p.tenant_id=? AND p.id=? AND w.id=? AND p.active=TRUE AND w.active=TRUE",tenant,part,warehouse);
  if(rows.size()!=1)throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  return Map.of("tenantId",Long.toString(tenant),"warehouseId",Long.toString(warehouse),"partId",Long.toString(part),"unitCode",rows.getFirst().get("unit"),"warehouseActive",true,"partActive",true,"checkedAt",Instant.now().toString());
 }
 @ExceptionHandler(Problem.class) ResponseEntity<Map<String,Object>> problem(Problem p){return ResponseEntity.status(p.status).header("Cache-Control","no-store").body(Map.of("code",p.code,"retryable",p.status==503));}
 @ExceptionHandler(Exception.class) ResponseEntity<Map<String,Object>> unavailable(Exception e){return problem(new Problem(503,"PART_CATALOG_PROOF_UNAVAILABLE"));}
}
