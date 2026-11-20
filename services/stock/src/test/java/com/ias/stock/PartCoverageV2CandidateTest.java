package com.ias.stock;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PartCoverageV2CandidateTest {
 private static final String LINE="11111111-1111-4111-8111-111111111111", TICKET="22222222-2222-4222-8222-222222222222", ROUND="33333333-3333-4333-8333-333333333333", APPROVAL="44444444-4444-4444-8444-444444444444";
 private static final PartCoverageV2Candidate.Expected EXPECTED = new PartCoverageV2Candidate.Expected(LINE,TICKET,ROUND,APPROVAL,1,7,8,2,"PIECE",900);
 private Map<String,Object> line() { return new HashMap<>(Map.of("id", LINE,"ticketId", TICKET,"roundId", ROUND,"kind", "PART","amountCents", "900", "partBinding", new HashMap<>(Map.of("approvalId", APPROVAL,"approvalVersion", "1","warehouseId", "7","partId", "8","quantity", "2","unitCode", "PIECE","allocationRule", "FULL_LINE_SINGLE_USE_V1")))); }
 @SuppressWarnings("unchecked") private Map<String,Object> binding(Map<String,Object> line) { return (Map<String,Object>) line.get("partBinding"); }
 @Test void acceptsExactBindingOnly() { assertTrue(PartCoverageV2Candidate.matches(line(), EXPECTED)); }
 @Test void rejectsChangedOrMissingPhysicalAndMoneyFacts() {
  for (var mutation : Map.of("approvalId",LINE,"approvalVersion","2","warehouseId","9","partId","9","quantity","1","unitCode","box","allocationRule","OTHER").entrySet()) { var candidate=line(); binding(candidate).put(mutation.getKey(),mutation.getValue()); assertFalse(PartCoverageV2Candidate.matches(candidate,EXPECTED),mutation.getKey()); }
  var amount=line(); amount.put("amountCents","899"); assertFalse(PartCoverageV2Candidate.matches(amount,EXPECTED));
  var missing=line(); binding(missing).remove("quantity"); assertFalse(PartCoverageV2Candidate.matches(missing,EXPECTED));
  var extra=line(); binding(extra).put("untrusted",true); assertFalse(PartCoverageV2Candidate.matches(extra,EXPECTED));
 }
 @Test void rejectsNonCanonicalValuesAndLegacyLine() {
  var decimal=line(); binding(decimal).put("quantity","02"); assertFalse(PartCoverageV2Candidate.matches(decimal,EXPECTED));
  var zeroVersion=line(); binding(zeroVersion).put("approvalVersion","0"); var zeroExpected=new PartCoverageV2Candidate.Expected(LINE,TICKET,ROUND,APPROVAL,0,7,8,2,"PIECE",900); assertFalse(PartCoverageV2Candidate.matches(zeroVersion,zeroExpected));
  var chinese=line(); binding(chinese).put("unitCode","件"); var chineseExpected=new PartCoverageV2Candidate.Expected(LINE,TICKET,ROUND,APPROVAL,1,7,8,2,"件",900); assertTrue(PartCoverageV2Candidate.matches(chinese,chineseExpected));
  var padded=line(); binding(padded).put("unitCode"," 件 "); var paddedExpected=new PartCoverageV2Candidate.Expected(LINE,TICKET,ROUND,APPROVAL,1,7,8,2," 件 ",900); assertFalse(PartCoverageV2Candidate.matches(padded,paddedExpected));
  var overflow=line(); binding(overflow).put("quantity","9223372036854775808"); assertFalse(PartCoverageV2Candidate.matches(overflow,EXPECTED));
  var legacy=line(); legacy.remove("partBinding"); assertFalse(PartCoverageV2Candidate.matches(legacy,EXPECTED));
  var invalid=line(); invalid.put("ticketId","not-a-uuid"); assertFalse(PartCoverageV2Candidate.matches(invalid,EXPECTED));
 }
}
