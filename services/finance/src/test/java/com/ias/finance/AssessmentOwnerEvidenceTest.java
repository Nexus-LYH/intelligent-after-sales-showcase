package com.ias.finance;

import com.ias.trust.Problem;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AssessmentOwnerEvidenceTest {
 final UUID labor=UUID.randomUUID(),part=UUID.randomUUID(),ticket=UUID.randomUUID(),round=UUID.randomUUID(),approval=UUID.randomUUID(),quoteId=UUID.randomUUID();
 final JdbcFeeGroups.PartBinding binding=new JdbcFeeGroups.PartBinding(approval,1,5,6,2,"pcs","FULL_LINE_SINGLE_USE_V1");
 JdbcFeeGroups.QuoteVersion quote(){var owner=new FeeGroup.Identity(1,2,UUID.randomUUID());var rule=new FeeGroup.PublishedRule("TEST_V1",200000);var submit=new FeeGroup.Submit(UUID.randomUUID(),quoteId,0,Set.of(),List.of(new FeeGroup.PlanLine(labor,ticket,round,100,false),new FeeGroup.PlanLine(part,ticket,round,500,false)),rule);var basis=new FeeGroup.Basis(owner,quoteId,1,600,0,0,600,rule,false,null);return new JdbcFeeGroups.QuoteVersion(submit,new JdbcFeeGroups.QuoteDetails(true,"test quote",Map.of(labor,"LABOR",part,"PART"),Map.of(part,binding)),basis);}
 final Map<String,Object> expected=Map.of("tenantId","1","actorUserId","9","companyId","2","groupId",UUID.randomUUID().toString(),"state","EXECUTED");
 Map<String,Object> proof(boolean stock){var p=new HashMap<>(expected);p.put("receiptId",UUID.randomUUID().toString());p.put("checkedAt",Instant.now().toString());if(stock){p.put("ticketId",ticket.toString());p.put("roundId",round.toString());p.put("items",List.of(Map.of("lineId",part.toString(),"kind","PART","amountCents","500","quantity","2","partId","6","warehouseId","5","orderId",approval.toString(),"useOperationId",UUID.randomUUID().toString(),"executionReceiptId",UUID.randomUUID().toString())));}else p.put("items",List.of(Map.of("lineId",labor.toString(),"ticketId",ticket.toString(),"roundId",round.toString(),"kind","LABOR","amountCents","100","processingRecordId",UUID.randomUUID().toString(),"executionReceiptId",UUID.randomUUID().toString())));return p;}
 AssessmentOwnerEvidence.Verified run(AssessmentOwnerEvidence.Owner owner){return AssessmentOwnerEvidence.collect(quote(),Map.of(labor,100L,part,500L),Map.of("sessionToken","fixture"),expected,System.currentTimeMillis()+10000,owner);}
 @Test void mixedQuoteChecksBothDistinctOwners(){var edges=new HashSet<String>();var value=run((edge,path,body)->{edges.add(edge);assertEquals(1,((List<?>)body.get("lines")).size());return proof(edge.equals("finance-stock"));});assertEquals(Set.of("finance-ticket","finance-stock"),edges);assertEquals(Set.of(labor,part),value.receipts().keySet());}
 @Test void wrongStockBindingOrMissingOwnerFactFailsClosed(){assertThrows(Problem.class,()->run((edge,path,body)->{var p=proof(edge.equals("finance-stock"));if(edge.equals("finance-stock")){var item=new HashMap<>((Map<String,Object>)((List<?>)p.get("items")).getFirst());item.put("orderId",UUID.randomUUID().toString());p.put("items",List.of(item));}return p;}));assertThrows(Problem.class,()->run((edge,path,body)->edge.equals("finance-stock")?null:proof(false)));}
 @Test void changedOwnerOrDuplicateReceiptDoesNotPass(){assertThrows(Problem.class,()->run((edge,path,body)->{var p=proof(edge.equals("finance-stock"));if(edge.equals("finance-stock"))p.put("companyId","999");return p;}));assertThrows(Problem.class,()->run((edge,path,body)->{var p=proof(edge.equals("finance-stock"));if(edge.equals("finance-ticket"))p.put("items",List.of());return p;}));}
 @Test void wrongTicketStaleProofAndReusedExecutionReceiptFail(){
  assertThrows(Problem.class,()->run((edge,path,body)->{var p=proof(edge.equals("finance-stock"));if(edge.equals("finance-stock"))p.put("ticketId",UUID.randomUUID().toString());return p;}));
  assertThrows(Problem.class,()->run((edge,path,body)->{var p=proof(edge.equals("finance-stock"));if(edge.equals("finance-stock"))p.put("checkedAt",Instant.now().minusSeconds(10).toString());return p;}));
  String duplicate=UUID.randomUUID().toString();assertThrows(Problem.class,()->run((edge,path,body)->{var p=proof(edge.equals("finance-stock"));var item=new HashMap<>((Map<String,Object>)((List<?>)p.get("items")).getFirst());item.put("executionReceiptId",duplicate);p.put("items",List.of(item));return p;}));
 }
}
