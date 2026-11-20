package com.ias.identity.auth;

import com.ias.trust.Chain;
import com.ias.trust.Problem;
import org.springframework.stereotype.Component;
import java.util.*;

/** Checks referenced objects at their owners; caller supplies tenant derived from the locked session. */
@Component
public class EmployeeScopeReferences {
 public void verify(long tenant,List<String> companies,List<String> warehouses,String trace,long deadline){
  if(!companies.isEmpty()){
   var facts=Chain.call("identity-asset","/internal/asset/company-references",Map.of("tenantId",Long.toString(tenant),"companyIds",companies),trace,deadline);Chain.fresh(facts,deadline);
   if(!Boolean.TRUE.equals(facts.get("valid")))throw new Problem(503,"COMPANY_SCOPE_UNCONFIRMED");
  }
  if(!warehouses.isEmpty()){
   var facts=Chain.call("identity-stock","/internal/stock/warehouse-references",Map.of("tenantId",Long.toString(tenant),"warehouseIds",warehouses),trace,deadline);Chain.fresh(facts,deadline);
   if(!Long.toString(tenant).equals(facts.get("tenantId"))||!warehouses.equals(facts.get("warehouseIds"))||!Boolean.TRUE.equals(facts.get("valid")))throw new Problem(503,"WAREHOUSE_SCOPE_UNCONFIRMED");
  }
 }
}
