package com.ias.ticket;

import com.ias.trust.Problem;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MixedFeeCoverageTest {
 @Test void acceptsBoundPartAlongsideLaborButRejectsTampering() {
  String ticket=UUID.randomUUID().toString(),round=UUID.randomUUID().toString();
  var labor=Map.<String,Object>of("id",UUID.randomUUID().toString(),"ticketId",ticket,"roundId",round,"kind","LABOR","amountCents","1200");
  var binding=new TreeMap<String,Object>(Map.of("approvalId",UUID.randomUUID().toString(),"approvalVersion","1","warehouseId","11","partId","101","quantity","1","unitCode","pcs","allocationRule","FULL_LINE_SINGLE_USE_V1"));
  var part=new TreeMap<String,Object>(Map.of("id",UUID.randomUUID().toString(),"ticketId",ticket,"roundId",round,"kind","PART","amountCents","500","partBinding",binding));
  var lines=new ArrayList<Map<String,Object>>(List.of(labor,part));lines.sort(Comparator.comparing(line->line.get("id").toString()));
  var fact=new HashMap<String,Object>();fact.put("coverageVersion","2");fact.put("lines",lines);fact.put("coverageDigest",FinanceLaborExecution.sha(lines));
  assertEquals(2,FinanceLaborExecution.coverage(fact).size());
  binding.put("quantity","2");assertThrows(Problem.class,()->FinanceLaborExecution.coverage(fact));
  fact.put("coverageDigest",FinanceLaborExecution.sha(lines));binding.put("allocationRule","INVALID");fact.put("coverageDigest",FinanceLaborExecution.sha(lines));assertThrows(Problem.class,()->FinanceLaborExecution.coverage(fact));
 }
}
