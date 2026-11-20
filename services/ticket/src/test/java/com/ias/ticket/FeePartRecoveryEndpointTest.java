package com.ias.ticket;

import com.ias.trust.Problem;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class FeePartRecoveryEndpointTest {
 FeePartPermitStoreTest fixture; FinancePartBridge bridge;String state;boolean revoked;int peerCalls,identityCalls;
 @BeforeEach void setup() throws Exception {
  fixture=new FeePartPermitStoreTest();fixture.setup();fixture.store.prepare(fixture.actor,fixture.command("","binding"));state="UNKNOWN";
  bridge=new FinancePartBridge(fixture.fixture.fixture.tickets,fixture.fixture.fixture.access,(method,path,body,headers,actor)->{
   peerCalls++;assertEquals("POST",method);assertEquals("/internal/stock/finance-part-executions/owner-result",path);
   var result=new HashMap<String,Object>(body);result.put("state",state);if(state.equals("COMMITTED"))result.put("resultDigest","e".repeat(64));return result;
  },(ticket,request,write)->{identityCalls++;if(revoked&&identityCalls>1)throw new Problem(404,"TARGET_NOT_ACCESSIBLE");return fixture.actor;});
 }
 MockHttpServletRequest request(){var request=new MockHttpServletRequest();request.addHeader("Idempotency-Key",fixture.operation);return request;}
 @Test void unknownDoesNotReleaseAndCommittedRecoveryIsIdempotent(){
  assertEquals("UNKNOWN",bridge.reconcile(fixture.fixture.fixture.ticket,fixture.operation,request()).get("state"));
  assertEquals(0,fixture.fixture.count("ticket_fee_part_permit_result"));
  state="COMMITTED";assertEquals("COMMITTED",bridge.reconcile(fixture.fixture.fixture.ticket,fixture.operation,request()).get("state"));
  assertEquals("COMMITTED",bridge.reconcile(fixture.fixture.fixture.ticket,fixture.operation,request()).get("state"));
  assertEquals(1,fixture.fixture.count("ticket_fee_part_permit_result"));
 }
 @Test void revokedBeforeRecordingKeepsPermitOpen(){
  state="COMMITTED";revoked=true;assertEquals(404,assertThrows(Problem.class,()->bridge.reconcile(fixture.fixture.fixture.ticket,fixture.operation,request())).status);
  assertEquals(1,peerCalls);assertEquals(0,fixture.fixture.count("ticket_fee_part_permit_result"));
 }
 @Test void foreignTicketOrChangedKeyNeverCallsOwner(){
  assertEquals(404,assertThrows(Problem.class,()->bridge.reconcile(UUID.randomUUID().toString(),fixture.operation,request())).status);
  var wrong=new MockHttpServletRequest();wrong.addHeader("Idempotency-Key",UUID.randomUUID().toString());
  assertEquals(400,assertThrows(Problem.class,()->bridge.reconcile(fixture.fixture.fixture.ticket,fixture.operation,wrong)).status);assertEquals(0,peerCalls);
 }
}
