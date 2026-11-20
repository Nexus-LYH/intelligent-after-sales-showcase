package com.ias.approval;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import java.util.UUID;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:approval-config", "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.hikari.connection-init-sql="},webEnvironment=SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
class ApprovalConfigurationTest {
    @Autowired MockMvc mvc;
    @Test void feeJsonRejectsBrowserBasisDuplicateFieldsAndNumericVersion()throws Exception{
        UUID op=UUID.randomUUID();String path="/api/approval/fees/"+UUID.randomUUID()+"/decisions";
        String body="{\"operationId\":\""+op+"\",\"expectedVersion\":\"1\",\"decision\":\"APPROVE\",\"reason\":null}";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",op).content(body.replace("\"expectedVersion\":\"1\"","\"expectedVersion\":1"))).andExpect(status().isBadRequest());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",op).content(body.replace("}",",\"basis\":{}}"))).andExpect(status().isBadRequest());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",op).content(body.replace("}",",\"decision\":\"REJECT\"}"))).andExpect(status().isBadRequest());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
    }
    @Test void actualBootConfigurationRejectsUnknownIdentityFieldsAndNumericVersionCoercion() throws Exception {
        UUID operation=UUID.randomUUID();String path="/api/approval/parts/"+UUID.randomUUID()+"/decisions";
        String normal="{\"operationId\":\""+operation+"\",\"expectedVersion\":\"0\",\"outcome\":\"APPROVE\"}";
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",operation).content(normal))
                .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control","no-store"));
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",operation)
                .content(normal.replace("\"expectedVersion\":\"0\"","\"expectedVersion\":0"))).andExpect(status().isBadRequest());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",operation)
                .content(normal.replace("}",",\"tenantId\":\"1\"}"))).andExpect(status().isBadRequest());
        mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",operation)
                .content(normal.replace("}",",\"expectedVersion\":\"1\"}"))).andExpect(status().isBadRequest());
    }
    @Test void bodyIsBoundedEvenBeforeTrustAdapterAndRejectsFractionalQuantity() throws Exception {
        UUID operation=UUID.randomUUID();
        mvc.perform(post("/api/approval/parts").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",operation)
                .content("x".repeat(65537))).andExpect(status().isPayloadTooLarge());
        String body="{\"operationId\":\""+operation+"\",\"ticketId\":\""+UUID.randomUUID()+"\",\"assignmentId\":\""+UUID.randomUUID()+"\",\"departmentId\":\"5\",\"warehouseId\":\"1\",\"partId\":\"2\",\"quantity\":1.5,\"purpose\":\"repair\"}";
        mvc.perform(post("/api/approval/parts").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",operation)
                .content(body)).andExpect(status().isBadRequest());
        mvc.perform(post("/api/approval/parts").contentType(MediaType.APPLICATION_JSON).header("Idempotency-Key",operation)
                .content(body.replace("1.5","2"))).andExpect(status().isServiceUnavailable());
    }
}
