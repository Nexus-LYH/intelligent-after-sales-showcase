package com.ias.stock;
import org.junit.jupiter.api.*;
import java.util.*;
import com.ias.trust.Problem;
import static com.ias.stock.FinancePartExecution.*;
import static org.junit.jupiter.api.Assertions.*;

class FinancePartCoverageV2Test {
 FinancePartExecutionStoreTest f;Map<String,Object> fact,binding;Command command;
 @BeforeEach void setup()throws Exception{
  f=new FinancePartExecutionStoreTest();f.setup();command=f.command(FinancePartExecutionStoreTest.uuid());fact=new LinkedHashMap<>(f.authority().completion(1,21,f.group,f.completion));
  binding=new TreeMap<>(Map.of("approvalId",f.order,"approvalVersion","1","warehouseId","11","partId","101","quantity","2","unitCode","pcs","allocationRule","FULL_LINE_SINGLE_USE_V1"));
  fact.put("coverageVersion","2");refresh();
 }
 void refresh(){var row=new TreeMap<String,Object>(Map.of("id",f.line,"ticketId",f.ticket,"roundId",f.round,"kind","PART","amountCents","1200","partBinding",new TreeMap<>(binding)));var lines=List.of(row);fact.put("lines",lines);fact.put("coverageDigest",sha(lines));fact.put("checkedAt",java.time.Instant.now().toString());}
 FinancePartExecutionStore store(){var base=f.authority();return new FinancePartExecutionStore(f.db,new Authority(){public Actor current(boolean w){return base.current(w);}public Map<String,Object> completion(long t,long c,String g,String id){return fact;}public OrderFact order(Actor a,String id,long w){return base.order(a,id,w);}public Map<String,Object> permit(Actor a,Command c){return base.permit(a,c);}});}
 @Test void exactApprovedPartBindingExecutes()throws Exception{assertEquals("EXECUTED",store().execute(command).get("state"));}
 @Test void projectionAcceptsBoundCoverageAndRejectsAlteredDigest(){assertEquals(1,FinancePartExecutionStore.projectionCoverage(fact).size());var rows=(List<Map<String,Object>>)fact.get("lines");((Map<String,Object>)rows.getFirst().get("partBinding")).put("quantity","3");assertEquals("PART_EXECUTION_COMPLETION_INVALID",assertThrows(Problem.class,()->FinancePartExecutionStore.projectionCoverage(fact)).code);}
 @Test void changedQuantityOrOrderCannotBorrowCoverage(){binding.put("quantity","3");refresh();assertEquals("PART_EXECUTION_BINDING_MISMATCH",assertThrows(Problem.class,()->store().execute(command)).code);binding.put("quantity","2");binding.put("approvalId",UUID.randomUUID().toString());refresh();assertEquals("PART_EXECUTION_BINDING_MISMATCH",assertThrows(Problem.class,()->store().execute(command)).code);}
 @Test void approvalVersionAndUnitMatchLocalImmutableOrder()throws Exception{binding.put("approvalVersion","2");refresh();assertEquals("PART_EXECUTION_APPROVAL_VERSION_MISMATCH",assertThrows(Problem.class,()->store().execute(command)).code);binding.put("approvalVersion","1");binding.put("unitCode","box");refresh();assertEquals("PART_EXECUTION_UNIT_MISMATCH",assertThrows(Problem.class,()->store().execute(command)).code);assertEquals(0,f.count("stock_finance_part_execution"));}
}
