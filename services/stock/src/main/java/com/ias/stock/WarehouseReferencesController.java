package com.ias.stock;

import com.ias.trust.Chain;
import com.ias.trust.Problem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

/** Private identity-owner check: no caller may infer a warehouse's tenant from an ID. */
@RestController
public final class WarehouseReferencesController {
 private final JdbcTemplate db;
 public WarehouseReferencesController(JdbcTemplate db){this.db=db;}
 @PostMapping("/internal/stock/warehouse-references")
 public Map<String,Object> check(@RequestBody Map<String,Object> body,HttpServletRequest request){
  if(!Chain.active()||!"stock".equals(Chain.role())||!request.isSecure()||!"identity-stock".equals(request.getAttribute("ias.chain.edge")))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");
  if(!request.getParameterMap().isEmpty()||!body.keySet().equals(Set.of("tenantId","warehouseIds"))||!(body.get("warehouseIds") instanceof List<?> ids)||ids.size()>100)throw bad();
  long tenant=positive(body.get("tenantId"));var sorted=new TreeSet<Long>();for(Object id:ids)if(!sorted.add(positive(id)))throw bad();
  for(long id:sorted)if(!Objects.equals(db.queryForObject("SELECT COUNT(*) FROM stock_warehouse WHERE tenant_id=? AND id=? AND active=TRUE",Long.class,tenant,id),1L))throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  return Map.of("tenantId",Long.toString(tenant),"warehouseIds",sorted.stream().map(Object::toString).toList(),"valid",true,"checkedAt",Instant.now().toString());
 }
 private static long positive(Object value){if(!(value instanceof String s)||!s.matches("[1-9][0-9]{0,18}"))throw bad();try{return Long.parseLong(s);}catch(NumberFormatException e){throw bad();}}
 private static Problem bad(){return new Problem(400,"VALIDATION_ERROR");}
 @ExceptionHandler(Problem.class)
 public org.springframework.http.ResponseEntity<Map<String,Object>> problem(Problem failure){
  return org.springframework.http.ResponseEntity.status(failure.status)
   .header("Cache-Control","no-store")
   .body(Map.of("code",failure.code,"retryable",failure.status==503));
 }
}
