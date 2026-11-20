package com.ias.ticket;
import com.ias.trust.Problem;
import org.junit.jupiter.api.*;
import org.h2.tools.RunScript;
import org.springframework.mock.web.MockHttpServletRequest;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** SQL projection and explicitly injected authorization facts, not trusted-network evidence. */
class FinanceQuoteReferencesTest {
 ApprovalBoundaryTest f;FinanceQuoteReferences api;MockHttpServletRequest request;String type="EMPLOYEE",tenant="7",user="42";boolean cap=true,enabled=true,department=true,scope=true,admin=true,member=true,mismatch=false,unavailable=false;
 @BeforeEach void setup()throws Exception{
  f=new ApprovalBoundaryTest();f.prepare();
  for(String m:List.of("V12__finance_quote_fencing_candidate.sql","V13__finance_owner_coordination.sql"))RunScript.execute(f.ddlConnection,new InputStreamReader(getClass().getResourceAsStream("/db/migration/"+m),StandardCharsets.UTF_8));
  f.db.execute("ALTER TABLE ticket_record ADD operation_id CHAR(36)");f.db.execute("CREATE TABLE ticket_create_operation(operation_id CHAR(36),state VARCHAR(20))");f.db.update("UPDATE ticket_record SET operation_id=?",f.operation);f.db.update("INSERT INTO ticket_create_operation VALUES(?,'COMPLETED')",f.operation);
  api=new FinanceQuoteReferences(f.tickets,(edge,path,body,r)->{
   if(unavailable)throw new Problem(503,"DEPENDENCY_UNAVAILABLE");
   if(edge.equals("ticket-identity"))return Map.of("tenantId",tenant,"userId",user,"identityType",type,"enabled",enabled,"departmentEnabled",department,"companyIds",scope?List.of("9"):List.of(),"permissions",cap?List.of("finance.quote.read"):List.of());
   assertEquals("ticket-asset",edge);assertEquals("/internal/asset/finance-membership",path);assertEquals("9",body.get("companyId"));return Map.of("tenantId",mismatch?"8":tenant,"userId",user,"companyId","9","active",member,"administrator",admin);
  });request=new MockHttpServletRequest();request.addHeader("Cookie","IAS-SESSION-DEV=test-session");
 }
 @AfterEach void close()throws Exception{f.ddlConnection.close();}
 void seed(long generation,String state,boolean result){
  String freeze=UUID.randomUUID().toString(),quote=UUID.randomUUID().toString();
  f.db.update("INSERT INTO ticket_finance_quote_fence VALUES(?,7,42,9,?,?,?,?,0,?,?,?,?,'approval,stock',UTC_TIMESTAMP(3))",freeze,f.ticket,f.ticket,f.round,UUID.randomUUID().toString(),quote,UUID.randomUUID().toString(),"a".repeat(64),generation);
  f.db.update("INSERT INTO ticket_finance_coordination(freeze_id,state,source_state,result_digest,next_attempt_at,updated_at) VALUES(?,'RELEASED',?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",freeze,state,"COMMITTED".equals(state)?"b".repeat(64):null);
  if(result)f.db.update("INSERT INTO ticket_finance_quote_source_result VALUES(?,?,UTC_TIMESTAMP(3))",freeze,"b".repeat(64));
 }
 @SuppressWarnings("unchecked") List<Map<String,String>> items(Map<String,Object> value){return (List<Map<String,String>>)value.get("items");}
 @Test void onlyMatchingCommittedFactsAreDiscoveredWithoutPriceOrOriginalActor(){seed(1,"COMMITTED",true);seed(2,"IN_PROGRESS",false);seed(3,"ABORTED",false);seed(4,"COMMITTED",false);var out=api.list(f.ticket,request);assertEquals(1,items(out).size());assertEquals(Set.of("groupId","quoteId","quoteVersion","roundId","generation"),items(out).getFirst().keySet());assertEquals("1",items(out).getFirst().get("quoteVersion"));assertNull(out.get("nextBeforeGeneration"));}
 @Test void deterministicTwentyItemCursorDoesNotSkipOrDuplicate(){for(int n=1;n<=23;n++)seed(n,"COMMITTED",true);var first=api.list(f.ticket,request);assertEquals(20,items(first).size());assertEquals("23",items(first).getFirst().get("generation"));assertEquals("4",first.get("nextBeforeGeneration"));request.setParameter("beforeGeneration","4");var second=api.list(f.ticket,request);assertEquals(List.of("3","2","1"),items(second).stream().map(x->x.get("generation")).toList());assertNull(second.get("nextBeforeGeneration"));}
 @Test void ordinaryTicketReadDoesNotGrantFinanceProjection(){cap=false;assertEquals(403,assertThrows(Problem.class,()->api.list(f.ticket,request)).status);}
 @Test void currentEmployeeTenantCompanyAndDepartmentAreRequired(){tenant="8";assertEquals(404,assertThrows(Problem.class,()->api.list(f.ticket,request)).status);tenant="7";scope=false;assertEquals(404,assertThrows(Problem.class,()->api.list(f.ticket,request)).status);scope=true;department=false;assertEquals(404,assertThrows(Problem.class,()->api.list(f.ticket,request)).status);}
 @Test void customerMustRemainCurrentEnterpriseAdministrator(){type="CUSTOMER";seed(1,"COMMITTED",true);assertEquals(1,items(api.list(f.ticket,request)).size());admin=false;assertEquals(403,assertThrows(Problem.class,()->api.list(f.ticket,request)).status);admin=true;member=false;assertEquals(403,assertThrows(Problem.class,()->api.list(f.ticket,request)).status);member=true;mismatch=true;assertEquals(403,assertThrows(Problem.class,()->api.list(f.ticket,request)).status);}
 @Test void missingDisabledIdentityAndDependencyOutageNeverReturnRows(){request.removeHeader("Cookie");assertEquals(401,assertThrows(Problem.class,()->api.list(f.ticket,request)).status);request.addHeader("Cookie","IAS-SESSION-DEV=test-session");enabled=false;assertEquals(401,assertThrows(Problem.class,()->api.list(f.ticket,request)).status);enabled=true;unavailable=true;assertEquals(503,assertThrows(Problem.class,()->api.list(f.ticket,request)).status);}
 @Test void onlyCanonicalSingleCursorParameterIsAccepted(){for(String before:List.of("0","-1","01","abc","9223372036854775808")){request.setParameter("beforeGeneration",before);assertEquals(400,assertThrows(Problem.class,()->api.list(f.ticket,request)).status);}request.removeParameter("beforeGeneration");request.setParameter("companyId","9");assertEquals(400,assertThrows(Problem.class,()->api.list(f.ticket,request)).status);}
 @Test void projectionRemainsPureAndNoStoreIsExplicit(){seed(1,"COMMITTED",true);var before=f.db.queryForMap("SELECT row_version,status FROM ticket_record WHERE id=?",f.ticket);api.list(f.ticket,request);api.list(f.ticket,request);assertEquals(before,f.db.queryForMap("SELECT row_version,status FROM ticket_record WHERE id=?",f.ticket));assertEquals(0,f.count("ticket_audit"));var response=new org.springframework.mock.web.MockHttpServletResponse();api.noStore(response);assertEquals("no-store",response.getHeader("Cache-Control"));}
}
