package com.ias.ticket;
import com.ias.trust.Problem;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
/** JDBC projections with injected identity; does not claim trusted-network validation. */
class FeeApprovalContextTest {
 FinanceQuoteReferencesTest fixture;FeeApprovalContext api;boolean scope=true;String actor="77";
 @BeforeEach void setup()throws Exception{fixture=new FinanceQuoteReferencesTest();fixture.setup();fixture.seed(1,"COMMITTED",true);api=new FeeApprovalContext(fixture.f.tickets,(token,r)->Map.of("enabled",true,"identityType","EMPLOYEE","departmentEnabled",true,"permissions",List.of("approval.fees.read"),"tenantId","7","userId",actor,"companyIds",scope?List.of("9"):List.of()));}
 @AfterEach void close()throws Exception{fixture.close();}
 Map<String,Object> read(){var row=fixture.f.db.queryForMap("SELECT group_id,quote_id FROM ticket_finance_quote_fence WHERE generation=1");return api.read("synthetic",fixture.f.ticket,row.get("group_id").toString(),row.get("quote_id").toString(),fixture.request);}
 void ack(String owner){fixture.f.db.update("INSERT INTO ticket_finance_owner_ack SELECT freeze_id,?,'RELEASED',?, '{}',UTC_TIMESTAMP(3) FROM ticket_finance_quote_fence WHERE generation=1",owner,"d".repeat(64));}
 @Test void originalSourceActorIsNeverReplacedByReviewer(){var out=read();assertEquals("42",out.get("actorUserId"));assertEquals("42",out.get("sourceActorUserId"));assertEquals("77",out.get("currentActorUserId"));assertEquals(false,out.get("callerIsCurrentEngineer"));assertEquals(false,out.get("ownerAcksComplete"));actor="42";assertEquals(true,read().get("callerIsCurrentEngineer"));}
 @Test void bothDurableTerminalAcksAndExactSourceResultAreRequired(){ack("approval");assertEquals(false,read().get("ownerAcksComplete"));ack("stock");assertEquals(true,read().get("ownerAcksComplete"));fixture.f.db.update("UPDATE ticket_finance_coordination SET state='RECONCILING'");assertEquals(false,read().get("ownerAcksComplete"));fixture.f.db.update("UPDATE ticket_finance_coordination SET state='RELEASED',result_digest=?","e".repeat(64));assertEquals(false,read().get("ownerAcksComplete"));}
 @Test void newerGenerationInvalidatesOldQuoteWithoutChangingHistory(){assertEquals(true,read().get("currentQuote"));fixture.seed(2,"IN_PROGRESS",false);assertEquals(false,read().get("currentQuote"));assertEquals(2,fixture.f.count("ticket_finance_quote_fence"));}
 @Test void revokedCompanyScopeStopsMinimalProjection(){scope=false;assertEquals(404,assertThrows(Problem.class,this::read).status);}
}
