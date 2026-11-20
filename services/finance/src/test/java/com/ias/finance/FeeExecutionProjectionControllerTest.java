package com.ias.finance;
import jakarta.servlet.http.HttpServletRequest;
import org.h2.tools.RunScript;
import org.junit.jupiter.api.*;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ias.finance.FeeGroup.*;
import static com.ias.finance.JdbcFeeGroups.*;

class FeeExecutionProjectionControllerTest {
 @Test void boundQuoteQuantitySurvivesNestedCompletionLinesBeforeAnyExecution()throws Exception{
  var original=f.store.quote(f.actor,f.owner,quote);
  var binding=new PartBinding(UUID.randomUUID(),1,11,101,4,"pcs","FULL_LINE_SINGLE_USE_V1");
  var details=new QuoteDetails(original.details().chargesCustomer(),original.details().pricingDescription(),original.details().kinds(),Map.of(part,binding));
  try(var connection=f.source.getConnection()){
   try(var update=connection.prepareStatement("UPDATE finance_quote_version SET quote_json=? WHERE application_id=?")){
    update.setString(1,FinanceController.JSON.writeValueAsString(new QuoteVersion(original.command(),details,original.basis())));
    update.setString(2,quote.toString());assertEquals(1,update.executeUpdate());
   }
   Map<String,Object> fact;
   try(var query=connection.prepareStatement("SELECT fact_json FROM finance_approval_completion_mirror WHERE completion_id=?")){
    query.setString(1,completion.toString());try(var rows=query.executeQuery()){
     assertTrue(rows.next());fact=FinanceController.JSON.readValue(rows.getString(1),Map.class);
    }
   }
   fact.put("lines",List.of(Map.of("id",part.toString(),"partBinding",binding.canonical())));
   try(var update=connection.prepareStatement("UPDATE finance_approval_completion_mirror SET fact_json=? WHERE completion_id=?")){
    update.setString(1,FinanceController.JSON.writeValueAsString(fact));update.setString(2,completion.toString());assertEquals(1,update.executeUpdate());
   }
  }
  var controller=new FeeExecutionProjectionController(f.source,access,peer(Map.of()));
  var result=controller.projection(f.owner.faultGroupId().toString(),completion.toString(),request);
  var lines=(List<Map<String,Object>>)result.get("lines");
  var partLine=lines.stream().filter(line->line.get("lineId").equals(part.toString())).findFirst().orElseThrow();
  assertEquals("4",partLine.get("approvedQuantity"));assertEquals("0",partLine.get("executedQuantity"));assertEquals("4",partLine.get("remainingQuantity"));
  assertFalse(partLine.containsKey("executionReceiptId"));assertEquals("NOT_EXECUTED",result.get("executionState"));
 }
 JdbcFeeGroupsTest f;UUID completion,labor,part,quote,laborTicket,laborRound,partTicket,partRound;FinanceAccess access;HttpServletRequest request;
 @BeforeEach void setup()throws Exception{f=new JdbcFeeGroupsTest();f.setup();try(var c=f.source.getConnection()){RunScript.execute(c,new InputStreamReader(getClass().getResourceAsStream("/db/migration/V7__approval_completion_mirror.sql"),StandardCharsets.UTF_8));}labor=UUID.randomUUID();part=UUID.randomUUID();quote=UUID.randomUUID();laborTicket=UUID.randomUUID();laborRound=UUID.randomUUID();partTicket=UUID.randomUUID();partRound=UUID.randomUUID();var cmd=new Submit(UUID.randomUUID(),quote,0,Set.of(),List.of(new PlanLine(labor,laborTicket,laborRound,1000,false),new PlanLine(part,partTicket,partRound,2000,false)),new PublishedRule("PUBLISHED_V1",999999));f.store.submit(f.actor,f.owner,cmd,new QuoteDetails(false,"projection",Map.of(labor,"LABOR",part,"PART")));completion=UUID.randomUUID();var fact=new LinkedHashMap<String,Object>();fact.putAll(Map.of("tenantId","1","companyId","2","groupId",f.owner.faultGroupId().toString(),"completionId",completion.toString(),"actorUserId","90","state","COMPLETED","generation","3","applicationId",UUID.randomUUID().toString(),"financeReceiptId",UUID.randomUUID().toString()));fact.put("lines",List.of(Map.of("id",part.toString(),"quantity","4")));var verified=new TrustedFeeCompletion.Verified(UUID.randomUUID(),completion,f.store.quote(f.actor,f.owner,quote),fact);new FeeCompletionMirror(f.source).record(f.owner,verified);access=new FinanceAccess(){public Principal principal(HttpServletRequest r,boolean w){return new Principal(f.actor,"EMPLOYEE");}public Scope scope(HttpServletRequest r,Principal p,UUID t){throw new UnsupportedOperationException();}public PublishedRule rule(HttpServletRequest r){throw new UnsupportedOperationException();}public Access bind(HttpServletRequest r,Principal p){return f.authority;}};request=mock(HttpServletRequest.class);when(request.getHeaders("Cookie")).thenReturn(Collections.enumeration(List.of("IAS-SESSION-DEV=test")));}
 @Test void serverCombinesExactTicketAndStockFactsAndComputesRemaining()throws Exception{var c=new FeeExecutionProjectionController(f.source,access,(edge,path,body,r)->{var out=new LinkedHashMap<String,Object>(body);out.remove("sessionToken");out.put("tenantId","1");out.put("companyId","2");out.put("state","PROJECTION");var requested=(List<Map<String,Object>>)body.get("lines");var id=requested.get(0).get("lineId").toString();boolean isLabor=id.equals(labor.toString());var item=new LinkedHashMap<String,Object>();item.put("state","EXECUTED");item.put("lineId",id);item.put("ticketId",(isLabor?laborTicket:partTicket).toString());item.put("roundId",(isLabor?laborRound:partRound).toString());item.put("kind",isLabor?"LABOR":"PART");item.put("amountCents",isLabor?"600":"1500");if(!isLabor)item.put("quantity","3");item.put("executionReceiptId",UUID.randomUUID().toString());out.put("items",List.of(item));return out;});var out=c.projection(f.owner.faultGroupId().toString(),completion.toString(),request);assertEquals("2100",out.get("executedAmountCents"));assertEquals("900",out.get("remainingAmountCents"));assertEquals("PARTIALLY_EXECUTED",out.get("executionState"));var lines=(List<Map<String,Object>>)out.get("lines");assertEquals(Set.of("ticket","stock"),lines.stream().map(x->x.get("ownerSource").toString()).collect(java.util.stream.Collectors.toSet()));assertTrue(lines.stream().anyMatch(x->"1".equals(x.get("remainingQuantity"))));}
 @Test void owner404OrMismatchFailsClosed()throws Exception{var c=new FeeExecutionProjectionController(f.source,access,(e,p,b,r)->{throw new com.ias.trust.Problem(404,"MISSING");});assertEquals(503,assertThrows(com.ias.trust.Problem.class,()->c.projection(f.owner.faultGroupId().toString(),completion.toString(),request)).status);}
 @Test void alteredOwnerGenerationFailsClosed()throws Exception{var c=new FeeExecutionProjectionController(f.source,access,(e,p,b,r)->{var out=new LinkedHashMap<String,Object>(b);out.remove("sessionToken");out.put("tenantId","1");out.put("companyId","2");out.put("generation","4");out.put("state","EXECUTED");out.put("items",List.of());return out;});assertEquals(503,assertThrows(com.ias.trust.Problem.class,()->c.projection(f.owner.faultGroupId().toString(),completion.toString(),request)).status);}
 @SuppressWarnings("unchecked") private FeeExecutionProjectionController.Peer peer(Map<String,String> states){return (edge,path,body,r)->{var out=new LinkedHashMap<String,Object>(body);out.remove("sessionToken");out.put("tenantId","1");out.put("companyId","2");out.put("state","PROJECTION");var items=new ArrayList<Map<String,Object>>();for(Object raw:(List<?>)body.get("lines")){var line=(Map<?,?>)raw;String id=line.get("lineId").toString(),kind=line.get("kind").toString(),state=states.getOrDefault(id,"NOT_EXECUTED");var item=new LinkedHashMap<String,Object>();item.put("lineId",id);item.put("ticketId",line.get("ticketId"));item.put("roundId",line.get("roundId"));item.put("kind",kind);item.put("state",state);if("NOT_EXECUTED".equals(state)){item.put("amountCents","0");if("PART".equals(kind))item.put("quantity","0");}else{item.put("amountCents",line.get("amountCents"));item.put("executionReceiptId",UUID.randomUUID().toString());if("PART".equals(kind))item.put("quantity","4");}items.add(item);}out.put("items",items);return out;};}
 @Test void firstAllEmptyIsAuthoritativeNotExecutedZero()throws Exception{var c=new FeeExecutionProjectionController(f.source,access,peer(Map.of()));var out=c.projection(f.owner.faultGroupId().toString(),completion.toString(),request);assertEquals("NOT_EXECUTED",out.get("executionState"));assertEquals("0",out.get("executedAmountCents"));assertEquals("3000",out.get("remainingAmountCents"));var lines=(List<Map<String,Object>>)out.get("lines");assertTrue(lines.stream().allMatch(x->"0".equals(x.get("executedAmountCents"))));}
 @Test void fullyExecutedLinesProduceFullyExecutedState()throws Exception{var c=new FeeExecutionProjectionController(f.source,access,peer(Map.of(labor.toString(),"EXECUTED",part.toString(),"EXECUTED")));var out=c.projection(f.owner.faultGroupId().toString(),completion.toString(),request);assertEquals("FULLY_EXECUTED",out.get("executionState"));assertEquals("3000",out.get("executedAmountCents"));assertEquals("0",out.get("remainingAmountCents"));}
 @Test void missingOrDuplicateOwnerItemsFailClosed()throws Exception{var base=peer(Map.of());var missing=new FeeExecutionProjectionController(f.source,access,(e,p,b,r)->{var out=new LinkedHashMap<String,Object>(base.call(e,p,b,r));var items=new ArrayList<Map<String,Object>>((List<Map<String,Object>>)out.get("items"));items.remove(items.size()-1);out.put("items",items);return out;});assertEquals(503,assertThrows(com.ias.trust.Problem.class,()->missing.projection(f.owner.faultGroupId().toString(),completion.toString(),request)).status);var duplicate=new FeeExecutionProjectionController(f.source,access,(e,p,b,r)->{var out=new LinkedHashMap<String,Object>(base.call(e,p,b,r));var items=new ArrayList<Map<String,Object>>((List<Map<String,Object>>)out.get("items"));items.add(new LinkedHashMap<>(items.get(0)));out.put("items",items);return out;});assertEquals(503,assertThrows(com.ias.trust.Problem.class,()->duplicate.projection(f.owner.faultGroupId().toString(),completion.toString(),request)).status);}
}

