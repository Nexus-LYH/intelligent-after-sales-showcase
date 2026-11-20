package com.ias.ticket;

import com.ias.trust.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Strict wire/binding validation for the isolated V16 candidate. No owner fact is inferred. */
public final class FinanceLaborExecution {
 private FinanceLaborExecution(){}
 static final Set<String> FIELDS=Set.of("operationId","expectedVersion","roundId","assignmentId","groupId","completionId","quoteId","quoteVersion","generation","lineId","processingRecordId","amountCents");
 public record Command(String ticketId,Map<String,Object> body){
  public Command{ticketId=uuid(ticketId);if(!body.keySet().equals(FIELDS))throw bad();var b=new TreeMap<String,Object>(body);
   for(String k:List.of("operationId","roundId","assignmentId","groupId","completionId","quoteId","lineId","processingRecordId"))b.put(k,uuid(b.get(k)));
   for(String k:List.of("expectedVersion","quoteVersion","generation","amountCents"))number(b.get(k),k.equals("expectedVersion"));body=Collections.unmodifiableMap(b);
  }
  String value(String key){return body.get(key).toString();}
  long count(String key){return number(body.get(key),key.equals("expectedVersion"));}
  String digest(){return sha(Map.of("ticketId",ticketId,"command",body));}
 }
 public record Actor(long tenant,long user,Set<Long> companies,Long department){public Actor{if(tenant<=0||user<=0)throw bad();companies=Set.copyOf(companies);}}
 static String uuid(Object o){if(!(o instanceof String s))throw bad();try{if(!UUID.fromString(s).toString().equals(s))throw bad();return s;}catch(IllegalArgumentException e){throw bad();}}
 static long number(Object o,boolean zero){if(!(o instanceof String s)||!s.matches(zero?"0|[1-9][0-9]{0,18}":"[1-9][0-9]{0,18}"))throw bad();try{return Long.parseLong(s);}catch(NumberFormatException e){throw bad();}}
 static String hex(Object o){if(!(o instanceof String s)||!s.matches("[a-f0-9]{64}"))throw bad();return s;}
 static Object canonical(Object value){if(value instanceof Map<?,?> m){var out=new TreeMap<String,Object>();m.forEach((k,v)->out.put(k.toString(),canonical(v)));return out;}if(value instanceof List<?> l)return l.stream().map(FinanceLaborExecution::canonical).toList();return value;}
 static String sha(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Chain.JSON.writeValueAsString(canonical(value)).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
 static void match(Map<String,Object> fact,Map<String,Object> expected){for(var e:expected.entrySet())if(!Objects.equals(fact.get(e.getKey()),e.getValue()))throw new Problem(503,"FEE_EXECUTION_FACT_MISMATCH");}
 static void fresh(Map<String,Object> fact){try{Instant at=Instant.parse(fact.get("checkedAt").toString()),now=Instant.now();if(at.isBefore(now.minusSeconds(2))||at.isAfter(now.plusMillis(250)))throw new IllegalArgumentException();}catch(Exception e){throw new Problem(503,"FEE_EXECUTION_FACT_STALE");}}
 @SuppressWarnings("unchecked") static List<Map<String,Object>> coverage(Map<String,Object> fact){
  if(!(fact.get("lines") instanceof List<?> raw)||raw.isEmpty()||raw.size()>50)throw invalid();var sorted=new TreeMap<String,Map<String,Object>>();
  try{Object version=fact.get("coverageVersion");if(version!=null&&!"2".equals(version))throw invalid();var orders=new HashSet<String>();
   for(Object o:raw){if(!(o instanceof Map<?,?> m))throw invalid();boolean bound="2".equals(version)&&"PART".equals(m.get("kind"));if(!m.keySet().equals(bound?Set.of("id","ticketId","roundId","kind","amountCents","partBinding"):Set.of("id","ticketId","roundId","kind","amountCents")))throw invalid();var line=new TreeMap<String,Object>((Map<String,Object>)m);String id=uuid(line.get("id"));uuid(line.get("ticketId"));uuid(line.get("roundId"));number(line.get("amountCents"),false);
    if(bound){if(!(line.get("partBinding") instanceof Map<?,?> b)||!b.keySet().equals(Set.of("approvalId","approvalVersion","warehouseId","partId","quantity","unitCode","allocationRule")))throw invalid();if(!orders.add(uuid(b.get("approvalId"))))throw invalid();for(String k:List.of("approvalVersion","warehouseId","partId","quantity"))number(b.get(k),false);if(!(b.get("unitCode") instanceof String unit)||unit.isBlank()||!unit.equals(unit.strip())||unit.length()>20||unit.chars().anyMatch(Character::isISOControl)||!"FULL_LINE_SINGLE_USE_V1".equals(b.get("allocationRule")))throw invalid();line.put("partBinding",new TreeMap<>((Map<String,Object>)b));}
    if(!Set.of("LABOR","OTHER","PART").contains(line.get("kind"))||sorted.put(id,line)!=null)throw invalid();}if(!sha(List.copyOf(sorted.values())).equals(fact.get("coverageDigest")))throw invalid();return List.copyOf(sorted.values());}catch(Problem e){throw invalid();}
 }
 static Problem invalid(){return new Problem(503,"FEE_EXECUTION_COMPLETION_INVALID");}
 static Problem bad(){return new Problem(400,"INVALID_REQUEST");}
}
