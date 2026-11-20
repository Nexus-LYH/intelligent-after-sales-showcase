package com.ias.ticket;

import com.ias.trust.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class FinanceLaborExecutionHttpTest {
 FinanceLaborExecutionStoreTest f;MockMvc http;boolean write=true,scope=true,available=true;String user="42";
 @BeforeEach void setup()throws Exception{
  f=new FinanceLaborExecutionStoreTest();f.setup();
  var api=new FinanceLaborExecutionController(f.source,(edge,path,b,r)->{
   if(!available)throw new Problem(503,"DEPENDENCY_UNAVAILABLE");
   if(edge.equals("ticket-identity")){
    String purpose=b.get("purpose").toString();if(purpose.equals("FEE_SUBMIT")&&(!write||!"test-csrf".equals(b.get("csrfToken"))))throw new Problem(403,"ACCESS_DENIED");
    return Map.of("enabled",true,"identityType","EMPLOYEE","departmentEnabled",true,"tenantId","7","userId",user,"departmentId","5","companyIds",scope?List.of("9"):List.of(),"permissions",write?List.of("approval.fees.read","approval.fees.submit","ticket.engineer.read","ticket.record"):List.of("approval.fees.read"),"checkedAt",Instant.now().toString());
   }
   assertEquals("ticket-approval",edge);assertEquals("/internal/approval/fee-completions/query",path);assertEquals(Set.of("tenantId","companyId","groupId","completionId"),b.keySet());var out=new LinkedHashMap<>(f.fact);out.put("checkedAt",Instant.now().toString());return out;
  });http=MockMvcBuilders.standaloneSetup(api).build();
 }
 @AfterEach void close()throws Exception{f.close();}
 String path(){return "/api/ticket/tickets/"+f.ticket+"/fee-executions";}
 String body(){return Chain.JSON.writeValueAsString(f.original);}
 org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request(){return post(path()).header("Cookie","IAS-SESSION-DEV=synthetic").header("Origin","http://127.0.0.1:5173").header("X-CSRF-TOKEN","test-csrf").header("Idempotency-Key",f.original.get("operationId")).contentType("application/json").content(body());}
 @Test void postAndOriginalGetThenReadOnlyReplayKeepOneFact()throws Exception{var result=http.perform(request()).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.state").value("EXECUTED")).andReturn().getResponse().getContentAsString();write=false;assertEquals(result,http.perform(request()).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());http.perform(get("/api/ticket/tickets/"+f.ticket+"/fee-execution-operations/"+f.original.get("operationId")).header("Cookie","IAS-SESSION-DEV=synthetic")).andExpect(status().isOk());assertEquals(1,f.count("ticket_fee_labor_execution"));}
 @Test void noSessionBadCsrfAndMissingWriteCapabilityCannotCreate()throws Exception{http.perform(request().with(r->{r.removeHeader("Cookie");return r;})).andExpect(status().isUnauthorized());http.perform(request().with(r->{r.removeHeader("X-CSRF-TOKEN");r.addHeader("X-CSRF-TOKEN","bad");return r;})).andExpect(status().isForbidden());write=false;http.perform(request()).andExpect(status().isForbidden());f.empty();}
 @Test void unknownDependencyAndRevokedScopeKeepNoPartialFacts()throws Exception{available=false;http.perform(request()).andExpect(status().isServiceUnavailable());available=true;scope=false;http.perform(request()).andExpect(status().isNotFound());f.empty();}
 @Test void unknownOriginalOperationAndWrongActorAreNotRecoverable()throws Exception{http.perform(get("/api/ticket/tickets/"+f.ticket+"/fee-execution-operations/"+UUID.randomUUID()).header("Cookie","IAS-SESSION-DEV=synthetic")).andExpect(status().isNotFound());http.perform(request()).andExpect(status().isOk());user="77";http.perform(request()).andExpect(status().isForbidden());assertEquals(1,f.count("ticket_fee_labor_operation"));}
 @Test void strictBodyQueryAndFakeInternalHeadersAreRejected()throws Exception{http.perform(request().param("extra","1")).andExpect(status().isBadRequest());http.perform(request().content(body().replace("\"amountCents\":\"900\"","\"amountCents\":\"900\",\"amountCents\":\"900\""))).andExpect(status().isBadRequest());http.perform(post("/internal/ticket/fee-executions/query").header("X-User-Id","42").header("X-Internal-Edge","finance-ticket").contentType("application/json").content(Chain.JSON.writeValueAsString(f.query()))).andExpect(status().isForbidden());f.empty();}
}
