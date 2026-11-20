package com.ias.ticket;
import com.ias.trust.Problem;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FeeExecutionStatusProjectionTest {
 FinanceLaborExecutionStoreTest f;
 @BeforeEach void setup()throws Exception{f=new FinanceLaborExecutionStoreTest();f.setup();}
 @AfterEach void close()throws Exception{f.close();}
 Map<String,Object> request(){var b=new LinkedHashMap<>(f.query());b.put("companyId","9");b.put("lines",List.of(Map.of("lineId",f.line,"ticketId",f.ticket,"roundId",f.round,"kind","LABOR","amountCents","1000")));return b;}
 Map<?,?> item(){return (Map<?,?>)((List<?>)f.store.projection(request()).get("items")).getFirst();}
 @Test void beforeFirstExecutionAuthoritativeZeroIsPureAndMoneyQueryStill404(){assertEquals("NOT_EXECUTED",item().get("state"));assertEquals("0",item().get("amountCents"));f.empty();assertEquals(404,assertThrows(Problem.class,()->f.store.query(f.query())).status);}
 @Test void partialAmountUsesActualReceiptNotRequestedLimit(){f.execute();assertEquals("900",item().get("amountCents"));assertEquals("EXECUTED",item().get("state"));assertNotNull(item().get("executionReceiptId"));var strict=new LinkedHashMap<>(f.query());strict.put("lines",List.of(Map.of("lineId",f.line,"amountCents","1000")));assertEquals(409,assertThrows(Problem.class,()->f.store.query(strict)).status);assertEquals(1,f.count("ticket_fee_labor_audit"));assertEquals(2,f.version());}
 @Test void fullAmountIsRetained(){f.original.put("amountCents","1000");f.execute();assertEquals("1000",item().get("amountCents"));}
 @Test void removedScopeStaleRoundGenerationAndBadApprovedBindingNeverBecomeZero(){f.allowed.set(false);assertEquals(403,assertThrows(Problem.class,()->f.store.projection(request())).status);f.allowed.set(true);f.db.update("UPDATE ticket_record SET round_id=?",UUID.randomUUID().toString());assertEquals(409,assertThrows(Problem.class,()->f.store.projection(request())).status);f.db.update("UPDATE ticket_record SET round_id=?",f.round);f.seed(2,UUID.randomUUID().toString(),UUID.randomUUID().toString(),"RELEASED");assertEquals(409,assertThrows(Problem.class,()->f.store.projection(request())).status);f.empty();}
 @Test void mismatchedApprovedAmountAndMissingAckFailClosed(){var b=request();b.put("lines",List.of(Map.of("lineId",f.line,"ticketId",f.ticket,"roundId",f.round,"kind","LABOR","amountCents","999")));assertEquals(503,assertThrows(Problem.class,()->f.store.projection(b)).status);f.db.update("DELETE FROM ticket_finance_owner_ack WHERE owner_name='stock'");assertEquals(503,assertThrows(Problem.class,()->f.store.projection(request())).status);f.empty();}
 @Test void duplicateLinesAndUnknownFieldsAreRejected(){var line=Map.<String,Object>of("lineId",f.line,"ticketId",f.ticket,"roundId",f.round,"kind","LABOR","amountCents","1000");var dup=request();dup.put("lines",List.of(line,line));assertEquals(400,assertThrows(Problem.class,()->f.store.projection(dup)).status);var extra=request();extra.put("extra","1");assertEquals(400,assertThrows(Problem.class,()->f.store.projection(extra)).status);}
 @Test void partContextIsExplicitlyNotAnExecutionOwner(){var lines=List.of(Map.of("id",f.line,"ticketId",f.ticket,"roundId",f.round,"kind","PART","amountCents","1000"));f.fact.put("lines",lines);f.fact.put("coverageDigest",FinanceLaborExecution.sha(lines));var b=request();b.put("lines",List.of(Map.of("lineId",f.line,"ticketId",f.ticket,"roundId",f.round,"kind","PART","amountCents","1000")));var item=(Map<?,?>)((List<?>)f.store.projection(b).get("items")).getFirst();assertEquals("OWNER_REQUIRED",item.get("state"));assertNull(item.get("amountCents"));f.empty();}
}
