package com.ias.stock;

import com.ias.trust.Problem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Strict immutable binding for a charged PART line. Reservation or issue is never an execution fact. */
public final class FinancePartExecution {
 private FinancePartExecution(){}
 static final Set<String> COMMAND_FIELDS=Set.of("operationId","groupId","completionId","quoteId","quoteVersion","generation","lineId","orderId","useOperationId","partId","quantity","amountCents","warehouseId","companyId");
 public record Command(Map<String,Object> body){
  public Command{if(body==null||!body.keySet().equals(COMMAND_FIELDS))throw bad();var b=new TreeMap<String,Object>(body);
   for(String k:List.of("operationId","groupId","completionId","quoteId","lineId","orderId","useOperationId"))b.put(k,uuid(b.get(k)));
   for(String k:List.of("quoteVersion","generation","partId","quantity","amountCents","warehouseId","companyId"))number(b.get(k),false);body=Collections.unmodifiableMap(b);
  }
  String value(String key){return body.get(key).toString();}
  long count(String key){return number(body.get(key),false);}
  String digest(){return sha(body);}
 }
 public record Actor(long tenantId,long userId,Set<Long> companyIds){public Actor{if(tenantId<=0||userId<=0)throw bad();companyIds=Set.copyOf(companyIds);}}
 public record OrderFact(long tenantId,long warehouseId,String ticketId,String roundId,String orderId,long engineerUserId,SortedMap<Long,Long> approvedQuantities){
  public OrderFact{if(tenantId<=0||warehouseId<=0||engineerUserId<=0)throw invalid("STOCK_ORDER_FACT_INVALID");ticketId=uuid(ticketId);roundId=uuid(roundId);orderId=uuid(orderId);approvedQuantities=Collections.unmodifiableSortedMap(new TreeMap<>(approvedQuantities));}
 }
 public interface Authority {Actor current(boolean write);Map<String,Object> completion(long tenant,long company,String group,String completion);OrderFact order(Actor actor,String orderId,long warehouseId);default Map<String,Object> permit(Actor actor,Command command){throw invalid("PART_EXECUTION_PERMIT_REQUIRED");}default void projectionScope(Map<String,Object> body){throw invalid("PART_EXECUTION_SCOPE_UNAVAILABLE");}}
 static String uuid(Object value){if(!(value instanceof String s))throw bad();try{if(!UUID.fromString(s).toString().equals(s))throw bad();return s;}catch(IllegalArgumentException e){throw bad();}}
 static long number(Object value,boolean zero){if(!(value instanceof String s)||!s.matches(zero?"0|[1-9][0-9]{0,18}":"[1-9][0-9]{0,18}"))throw bad();try{return Long.parseLong(s);}catch(NumberFormatException e){throw bad();}}
 static String hex(Object value){if(!(value instanceof String s)||!s.matches("[a-f0-9]{64}"))throw invalid("PART_EXECUTION_FACT_INVALID");return s;}
 static Object canonical(Object value){if(value instanceof Map<?,?> m){var out=new TreeMap<String,Object>();m.forEach((k,v)->out.put(k.toString(),canonical(v)));return out;}if(value instanceof List<?> l)return l.stream().map(FinancePartExecution::canonical).toList();return value;}
 static String sha(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(com.ias.trust.Chain.JSON.writeValueAsString(canonical(value)).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
 static void fresh(Map<String,Object> fact){try{Instant checked=Instant.parse(fact.get("checkedAt").toString()),now=Instant.now();if(checked.isBefore(now.minusSeconds(2))||checked.isAfter(now.plusMillis(250)))throw new IllegalArgumentException();}catch(Exception e){throw invalid("PART_EXECUTION_FACT_STALE");}}
 @SuppressWarnings("unchecked") static Map<String,Object> partLine(Map<String,Object> fact,Command c){
  try{fresh(fact);if(!(fact.get("lines") instanceof List<?> raw)||raw.isEmpty()||raw.size()>50)throw new Exception();var canonical=new TreeMap<String,Map<String,Object>>();Map<String,Object> selected=null;
   Object version=fact.get("coverageVersion");if(version!=null&&!"2".equals(version))throw new Exception();var orders=new HashSet<String>();
   for(Object value:raw){
    if(!(value instanceof Map<?,?> map))throw new Exception();boolean bound="2".equals(version)&&"PART".equals(map.get("kind"));
    if(!map.keySet().equals(bound?Set.of("id","ticketId","roundId","kind","amountCents","partBinding"):Set.of("id","ticketId","roundId","kind","amountCents")))throw new Exception();
    var line=new TreeMap<String,Object>((Map<String,Object>)map);String id=uuid(line.get("id"));uuid(line.get("ticketId"));uuid(line.get("roundId"));number(line.get("amountCents"),false);
    if(bound){
     if(!(line.get("partBinding") instanceof Map<?,?> binding)||!binding.keySet().equals(Set.of("approvalId","approvalVersion","warehouseId","partId","quantity","unitCode","allocationRule")))throw new Exception();
     if(!orders.add(uuid(binding.get("approvalId"))))throw new Exception();for(String key:List.of("approvalVersion","warehouseId","partId","quantity"))number(binding.get(key),false);
     if(!(binding.get("unitCode") instanceof String unit)||unit.isBlank()||!unit.equals(unit.strip())||unit.length()>20||unit.chars().anyMatch(Character::isISOControl)||!"FULL_LINE_SINGLE_USE_V1".equals(binding.get("allocationRule")))throw new Exception();
     line.put("partBinding",new TreeMap<>((Map<String,Object>)binding));
    }
    if(!Set.of("LABOR","OTHER","PART").contains(line.get("kind"))||canonical.put(id,line)!=null)throw new Exception();if(id.equals(c.value("lineId")))selected=line;
   }
   if(!sha(List.copyOf(canonical.values())).equals(fact.get("coverageDigest"))||selected==null||!"PART".equals(selected.get("kind"))||!c.value("amountCents").equals(selected.get("amountCents").toString()))throw new Exception();
   if("2".equals(version)){var binding=(Map<?,?>)selected.get("partBinding");for(String[] pair:new String[][]{{"orderId","approvalId"},{"warehouseId","warehouseId"},{"partId","partId"},{"quantity","quantity"}})if(!c.value(pair[0]).equals(binding.get(pair[1])))throw invalid("PART_EXECUTION_BINDING_MISMATCH");}
   return selected;
  }catch(Exception e){if(e instanceof Problem p)throw p;throw invalid("PART_EXECUTION_COMPLETION_INVALID");}
 }
 static Problem bad(){return new Problem(400,"INVALID_REQUEST");}
 static Problem invalid(String code){return new Problem(503,code);}
}
