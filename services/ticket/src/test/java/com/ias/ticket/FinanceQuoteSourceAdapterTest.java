package com.ias.ticket;
import com.ias.trust.*;
import org.junit.jupiter.api.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceQuoteSourceAdapterTest {
 final FinanceQuoteBoundary.Binding binding=new FinanceQuoteBoundary.Binding(7,8,9,uuid(),uuid(),uuid(),uuid(),0,uuid(),uuid(),"a".repeat(64));
 static String uuid(){return UUID.randomUUID().toString();}
 Map<String,Object> fact(String state){var f=new LinkedHashMap<>(FinanceQuoteSourceAdapter.request(binding));f.put("sourceState",state);f.put("state",state);f.put("resultDigest","COMMITTED".equals(state)?"b".repeat(64):null);f.put("checkedAt",Instant.now().toString());return f;}
 FinanceQuoteBoundary.SourceFact parse(Map<String,Object> f){return FinanceQuoteSourceAdapter.parse(binding,f,System.currentTimeMillis()+9000);}
 @Test void allSourceStatesRemainFactsNotGlobalAuthorization(){for(String state:List.of("IN_PROGRESS","COMMITTED","ABORTED"))assertEquals(state,parse(fact(state)).state());}
 @TestFactory Collection<DynamicTest> everyBindingFieldIsExact(){return FinanceQuoteSourceAdapter.request(binding).keySet().stream().map(k->DynamicTest.dynamicTest(k,()->{var f=fact("IN_PROGRESS");f.put(k,"changed");assertEquals("FINANCE_SOURCE_BINDING_MISMATCH",assertThrows(Problem.class,()->parse(f)).code);})).toList();}
 @Test void staleFutureAndMissingProofCannotRelease(){for(Object at:List.of(Instant.now().minusSeconds(3).toString(),Instant.now().plusSeconds(3).toString(),"invalid")){var f=fact("IN_PROGRESS");f.put("checkedAt",at);assertEquals(503,assertThrows(Problem.class,()->parse(f)).status);}}
 @Test void numericIdsAndLegacyPreparingAreNotSilentlyAccepted(){var f=fact("IN_PROGRESS");f.put("tenantId",7L);assertThrows(Problem.class,()->parse(f));assertEquals("FINANCE_SOURCE_STATE_NOT_CONFIRMED",assertThrows(Problem.class,()->parse(fact("PREPARING"))).code);}
 @Test void resultDigestBelongsOnlyToCommitted(){for(String state:List.of("IN_PROGRESS","ABORTED")){var f=fact(state);f.put("resultDigest","c".repeat(64));assertThrows(Problem.class,()->parse(f));}var committed=fact("COMMITTED");committed.put("resultDigest",null);assertThrows(Problem.class,()->parse(committed));}
 @Test void aliasStateCannotContradictSource(){var f=fact("IN_PROGRESS");f.put("state","ABORTED");assertThrows(Problem.class,()->parse(f));}
 @Test void trustEdgeAllowsOnlyExactPureQuery(){assertTrue(Chain.allowedEdge("ticket-finance","POST","/internal/finance/quote-sources/query"));assertFalse(Chain.allowedEdge("ticket-finance","GET","/internal/finance/quote-sources/query"));assertFalse(Chain.allowedEdge("ticket-finance","POST","/api/finance/groups/x/quote-sources/y/abort"));assertFalse(Chain.allowedEdge("gateway-finance","POST","/internal/finance/quote-sources/query"));}
}
