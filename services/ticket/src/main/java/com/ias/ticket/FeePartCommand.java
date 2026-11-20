package com.ias.ticket;

import com.ias.trust.Chain;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Canonical command shared with the stock owner's PART request; not an authorization proof. */
final class FeePartCommand {
 private static final Set<String> FIELDS=Set.of("operationId","groupId","completionId","quoteId","quoteVersion","generation","lineId","orderId","useOperationId","partId","quantity","amountCents","warehouseId","companyId");
 private final SortedMap<String,Object> body;
 FeePartCommand(Map<String,Object> input){
  if(input==null||!input.keySet().equals(FIELDS))throw Tickets.bad();
  var value=new TreeMap<String,Object>(input);
  for(String key:List.of("operationId","groupId","completionId","quoteId","lineId","orderId","useOperationId")){
   if(!(value.get(key) instanceof String))throw Tickets.bad();Tickets.uuid(value.get(key));
  }
  for(String key:List.of("quoteVersion","generation","partId","quantity","amountCents","warehouseId","companyId")){
   Object raw=value.get(key);if(!(raw instanceof String text)||!text.matches("[1-9][0-9]{0,18}"))throw Tickets.bad();Tickets.id(text);
  }
  body=Collections.unmodifiableSortedMap(value);
 }
 Map<String,Object> body(){return body;}
 String digest(){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Chain.JSON.writeValueAsString(body).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
}
