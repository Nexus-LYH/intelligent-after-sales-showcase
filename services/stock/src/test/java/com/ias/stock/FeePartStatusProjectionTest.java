package com.ias.stock;
import com.ias.trust.Problem;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FeePartStatusProjectionTest {
 FinancePartExecutionStoreTest f;boolean scope=true;
 @BeforeEach void setup()throws Exception{f=new FinancePartExecutionStoreTest();f.setup();var a=f.authority();f.store=new FinancePartExecutionStore(f.db,new FinancePartExecution.Authority(){
  public FinancePartExecution.Actor current(boolean w){return a.current(w);}
  public Map<String,Object> permit(FinancePartExecution.Actor actor,FinancePartExecution.Command command){return a.permit(actor,command);}
  public Map<String,Object> completion(long t,long c,String g,String id){var out=new LinkedHashMap<>(a.completion(t,c,g,id));out.put("state","COMPLETED");out.put("supervisorReceiptId",UUID.randomUUID().toString());out.put("financialReviewRequired",false);return out;}
  public FinancePartExecution.OrderFact order(FinancePartExecution.Actor actor,String id,long w){return a.order(actor,id,w);}
  public void projectionScope(Map<String,Object>b){if(!scope)throw new Problem(409,"CURRENT_SCOPE_CHANGED");}
 });}
 Map<String,Object> request(){var b=f.query();b.put("companyId","21");b.put("lines",List.of(Map.of("lineId",f.line,"ticketId",f.ticket,"roundId",f.round,"kind","PART","amountCents","1200")));return b;}
 Map<?,?> item(){return (Map<?,?>)((List<?>)f.store.projection(request()).get("items")).getFirst();}
 @Test void noUseAndNoReceiptProduceAuthoritativeZeroButNotMoneyProof()throws Exception{f.sql("DELETE FROM stock_execution_movement");f.sql("DELETE FROM stock_execution_operation");assertEquals("NOT_EXECUTED",item().get("state"));assertEquals("0",item().get("amountCents"));assertEquals("0",item().get("quantity"));assertEquals(404,assertThrows(Problem.class,()->f.store.query(f.query())).status);assertEquals(0,f.count("stock_finance_part_execution"));assertEquals(0,f.count("stock_finance_part_audit"));}
 @Test void fullUseReturnsRealReceiptWithoutCreatingWrites()throws Exception{f.store.execute(f.command(FinancePartExecutionStoreTest.uuid()));assertEquals("1200",item().get("amountCents"));assertEquals("2",item().get("quantity"));assertNotNull(item().get("executionReceiptId"));assertEquals(1,f.count("stock_finance_part_audit"));}
 @Test void changedScopeCompanyOrGenerationFailsClosed()throws Exception{scope=false;assertEquals(409,assertThrows(Problem.class,()->f.store.projection(request())).status);scope=true;f.actor=new FinancePartExecution.Actor(1,10,Set.of(22L));assertEquals(404,assertThrows(Problem.class,()->f.store.projection(request())).status);f.actor=new FinancePartExecution.Actor(1,10,Set.of(21L));f.sql("UPDATE stock_finance_generation SET generation=4");assertEquals(409,assertThrows(Problem.class,()->f.store.projection(request())).status);assertEquals(0,f.count("stock_finance_part_execution"));}
 @Test void wrongApprovedBindingIsNotInterpretedAsNotExecuted(){var b=request();b.put("lines",List.of(Map.of("lineId",f.line,"ticketId",f.ticket,"roundId",f.round,"kind","PART","amountCents","1199")));assertEquals(503,assertThrows(Problem.class,()->f.store.projection(b)).status);}
}
