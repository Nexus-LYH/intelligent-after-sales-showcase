package com.ias.finance;

import com.ias.trust.Chain;
import com.ias.trust.Problem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Verify every assessed line with its actual execution owner. */
final class AssessmentOwnerEvidence {
 @FunctionalInterface interface Owner {Map<String,Object> query(String edge,String path,Map<String,Object> request);}
 record Verified(Map<UUID,UUID> receipts,UUID receiptId,Instant checkedAt){}
 static Verified collect(JdbcFeeGroups.QuoteVersion quote,Map<UUID,Long> amounts,Map<String,Object> common,Map<String,Object> expected,long deadline,Owner owner){
  var quoteLines=new HashMap<UUID,FeeGroup.PlanLine>();for(var line:quote.command().additions())quoteLines.put(line.id(),line);
  var ticket=new ArrayList<Map<String,Object>>();var stock=new ArrayList<Map<String,Object>>();
  for(var entry:new TreeMap<>(amounts).entrySet()){
   var line=quoteLines.get(entry.getKey());String kind=quote.details().kinds().get(entry.getKey());
   if(line==null||entry.getValue()==null||entry.getValue()<=0||entry.getValue()>line.amountCents())throw invalid();
   var requested=Map.<String,Object>of("lineId",entry.getKey().toString(),"amountCents",Long.toString(entry.getValue()));
   if("PART".equals(kind))stock.add(requested);else if(Set.of("LABOR","OTHER").contains(kind))ticket.add(requested);else throw invalid();
  }
  if(ticket.isEmpty()&&stock.isEmpty())throw invalid();var receipts=new HashMap<UUID,UUID>();var uniqueReceipts=new HashSet<UUID>();var proofIds=new ArrayList<String>();var proofs=new ArrayList<Map<String,Object>>();
  for(int index=0;index<2;index++){
   boolean part=index==1;var requested=part?stock:ticket;if(requested.isEmpty())continue;
   var request=new LinkedHashMap<>(common);request.put("lines",requested);
   var proof=owner.query(part?"finance-stock":"finance-ticket",part?"/internal/stock/finance-part-executions/query":"/internal/ticket/fee-executions/query",request);
   if(proof==null)throw invalid();for(var e:expected.entrySet())if(!Objects.equals(e.getValue(),proof.get(e.getKey())))throw invalid();
   if(!(proof.get("items") instanceof List<?> items)||items.size()!=requested.size())throw invalid();
   var wanted=new HashSet<UUID>();for(var line:requested)wanted.add(TrustedFinanceAccess.ownerUuid(line.get("lineId")));
   for(Object raw:items){
    if(!(raw instanceof Map<?,?> item))throw invalid();UUID id=TrustedFinanceAccess.ownerUuid(item.get("lineId"));if(!wanted.remove(id)||receipts.containsKey(id))throw invalid();
    var line=quoteLines.get(id);String kind=quote.details().kinds().get(id);if(!Long.toString(amounts.get(id)).equals(item.get("amountCents"))||!kind.equals(item.get("kind")))throw invalid();
    if(part){
     if(!item.keySet().equals(Set.of("lineId","kind","amountCents","quantity","partId","warehouseId","orderId","useOperationId","executionReceiptId")))throw invalid();
     var b=quote.details().partBindings().get(id);if(b==null||!b.approvalId().toString().equals(item.get("orderId"))||!Long.toString(b.partId()).equals(item.get("partId"))||!Long.toString(b.warehouseId()).equals(item.get("warehouseId"))||!Long.toString(b.quantity()).equals(item.get("quantity"))||!line.ticketId().toString().equals(proof.get("ticketId"))||!line.roundId().toString().equals(proof.get("roundId")))throw invalid();
     TrustedFinanceAccess.ownerUuid(item.get("useOperationId"));
    }else{
     if(!item.keySet().equals(Set.of("lineId","ticketId","roundId","kind","amountCents","processingRecordId","executionReceiptId"))||!line.ticketId().toString().equals(item.get("ticketId"))||!line.roundId().toString().equals(item.get("roundId")))throw invalid();
     TrustedFinanceAccess.ownerUuid(item.get("processingRecordId"));
    }
    UUID receipt=TrustedFinanceAccess.ownerUuid(item.get("executionReceiptId"));if(!uniqueReceipts.add(receipt))throw invalid();receipts.put(id,receipt);
   }
   if(!wanted.isEmpty())throw invalid();proofIds.add(TrustedFinanceAccess.ownerUuid(proof.get("receiptId")).toString());proofs.add(proof);
  }
  if(!receipts.keySet().equals(amounts.keySet()))throw invalid();for(var proof:proofs)Chain.fresh(proof,deadline);Collections.sort(proofIds);
  return new Verified(Map.copyOf(receipts),UUID.nameUUIDFromBytes(String.join(":",proofIds).getBytes(StandardCharsets.UTF_8)),Instant.now());
 }
 private static Problem invalid(){return new Problem(503,"FINANCE_EXECUTION_FACT_MISMATCH");}
}
