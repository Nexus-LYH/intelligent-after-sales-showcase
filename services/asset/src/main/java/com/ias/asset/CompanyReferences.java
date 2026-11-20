package com.ias.asset;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import com.ias.trust.Problem;
import java.time.Instant;
import java.util.*;
@RestController
public class CompanyReferences {
 final JdbcTemplate db;
 public CompanyReferences(JdbcTemplate db){this.db=db;}
 @PostMapping("/internal/asset/company-references") public Map<String,Object> check(@RequestBody Map<String,Object>b){
  if(!b.keySet().equals(Set.of("tenantId","companyIds"))||!(b.get("companyIds") instanceof List<?> ids)||ids.size()>100)throw new Problem(400,"VALIDATION_ERROR");
  long tenant=positive(b.get("tenantId"));for(Object company:ids)if(db.queryForObject("SELECT COUNT(*) FROM asset_company WHERE tenant_id=? AND id=?",Long.class,tenant,positive(company))!=1)throw new Problem(404,"TARGET_NOT_ACCESSIBLE");
  return Map.of("valid",true,"checkedAt",Instant.now().toString());
 }
 long positive(Object v){try{long n=Long.parseLong(v.toString());if(n<1)throw new Exception();return n;}catch(Exception e){throw new Problem(400,"VALIDATION_ERROR");}}
}
