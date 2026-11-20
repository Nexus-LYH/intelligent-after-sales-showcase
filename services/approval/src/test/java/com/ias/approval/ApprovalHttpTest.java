package com.ias.approval;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import tools.jackson.databind.json.JsonMapper;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.ias.approval.PartsApproval.*;

class ApprovalHttpTest {
    @Test void departmentUsesTheExistingAssignmentBigintIdWithoutCodeGuessing() {
        String ticket=UUID.randomUUID().toString(),assignment=UUID.randomUUID().toString();
        var body=new ApprovalService.SubmitBody(UUID.randomUUID(),ticket,assignment,"9007199254740993","1","2",1,"repair",null);
        assertEquals("9007199254740993",body.departmentId());
        for(String invalid:new String[]{"ENGINEERING_1","0","-1","01","9223372036854775808"})
            assertThrows(ApprovalFailure.class,()->new ApprovalService.SubmitBody(UUID.randomUUID(),ticket,assignment,invalid,"1","2",1,"repair",null));
    }
    @Test void missingTrustRejectsEvenForgedBrowserHeadersBeforeAnyDatabaseUse() throws Exception {
        var store=mock(ApprovalStore.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ApprovalController(
                new ApprovalService(store,new ApprovalAccess.Unavailable(),Clock.systemUTC()))).build();
        UUID operation=UUID.randomUUID();
        String body="{\"operationId\":\""+operation+"\",\"ticketId\":\""+UUID.randomUUID()+"\",\"assignmentId\":\""+UUID.randomUUID()+"\",\"departmentId\":\"5\",\"warehouseId\":\"1\",\"partId\":\"2\",\"quantity\":2,\"purpose\":\"repair\"}";
        mvc.perform(post("/api/approval/parts").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",operation)
                .header("X-IAS-Tenant","1").header("X-IAS-Actor","2").header("Authorization","Bearer forged").content(body))
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("APPROVAL_TRUST_NOT_CONFIGURED"));
        mvc.perform(get("/api/approval/parts/"+UUID.randomUUID())).andExpect(status().isServiceUnavailable());
        verifyNoInteractions(store);
    }
    @Test void missingMismatchedOrDuplicateIdempotencyKeyIsRejected() throws Exception {
        var store=mock(ApprovalStore.class);
        var mvc=MockMvcBuilders.standaloneSetup(new ApprovalController(
                new ApprovalService(store,new ApprovalAccess.Unavailable(),Clock.systemUTC()))).build();
        UUID operation=UUID.randomUUID();String body="{\"operationId\":\""+operation+"\",\"expectedVersion\":\"0\",\"outcome\":\"APPROVE\"}";
        String path="/api/approval/parts/"+UUID.randomUUID()+"/decisions";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",UUID.randomUUID()).content(body)).andExpect(status().isBadRequest());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",operation,operation).content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }
    @Test void wireVersionsAreStringsEvenBeyondJavascriptSafeInteger() {
        var command=new Submission(UUID.randomUUID(),"1","2","ticket","assignment","dept","warehouse","part",2,"repair");
        var value=new Application(UUID.randomUUID(),command,State.PENDING,9007199254740993L,Instant.now(),null,null,
                new Revision(UUID.randomUUID(),2,UUID.randomUUID()),null,null);
        var json=JsonMapper.builder().build().readTree(JsonMapper.builder().build().writeValueAsString(ApprovalView.of(value)));
        assertTrue(json.get("version").isString());assertEquals("9007199254740993",json.get("version").asString());
        assertTrue(json.get("revision").get("number").isString());
    }
}
