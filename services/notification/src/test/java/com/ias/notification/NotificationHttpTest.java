package com.ias.notification;
import com.ias.trust.Problem;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import java.util.*;

/** Real MVC serialization/body guards, with no socket or shared service window. */
class NotificationHttpTest {
    MockMvc mvc;MessageInbox inbox;NotificationAccess access;
    String id=UUID.randomUUID().toString(),key=UUID.randomUUID().toString();
    @BeforeEach void setup(){inbox=mock(MessageInbox.class);access=mock(NotificationAccess.class);when(access.actor(any(),anyBoolean())).thenReturn(new NotificationAccess.Actor(1,10));mvc=MockMvcBuilders.standaloneSetup(new NotificationController(inbox,access)).addFilters(new NotificationNoStore()).build();}
    @Test void onlyCommittedOwnerEnvelopeReceivesExactAck()throws Exception {
        var event=MessageInboxTest.event();when(inbox.receive(eq(event),any())).thenReturn(Map.of("eventId",event.eventId().toString(),"ack","COMMITTED"));
        mvc.perform(post("/internal/notification/events").contentType("application/json").content("{\"event\":"+event.encode()+"}"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(content().json("{\"eventId\":\""+event.eventId()+"\",\"ack\":\"COMMITTED\"}",true));
    }
    @Test void callerCannotProvideRecipientsOrActorAuthority()throws Exception {
        var event=MessageInboxTest.event();for(String body:List.of(event.encode(),"{\"event\":"+event.encode()+",\"recipientUserIds\":[\"10\"]}","{\"event\":"+event.encode()+",\"event\":"+event.encode()+"}"))
            mvc.perform(post("/internal/notification/events").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        verifyNoInteractions(inbox);
    }
    @Test void readAcceptsOnlyEmptyBodyAndOneCanonicalOriginalKey()throws Exception {
        when(inbox.markRead(any(),eq(id),eq(key),any())).thenReturn(Map.of("operationId",key,"messageId",id,"state","READ","readAt","2026-10-22T01:00:00Z"));
        mvc.perform(post("/api/notification/messages/"+id+"/read").header("Idempotency-Key",key).contentType("application/json").content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.operationId").value(key));
        mvc.perform(post("/api/notification/messages/"+id+"/read").header("Idempotency-Key",key,key).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/notification/messages/"+id+"/read").contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/notification/messages/"+id+"/read").header("Idempotency-Key",key).contentType("application/json").content("{\"actorUserId\":\"10\"}")).andExpect(status().isBadRequest());
        verify(inbox,times(1)).markRead(any(),any(),any(),any());
    }
    @Test void unavailableTrustCannotServeOrMarkMessages()throws Exception {
        mvc=MockMvcBuilders.standaloneSetup(new NotificationController(inbox,new NotificationAccess.Unavailable())).addFilters(new NotificationNoStore()).build();
        mvc.perform(get("/api/notification/messages")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("NOTIFICATION_TRUST_NOT_CONFIGURED"));
        mvc.perform(post("/api/notification/messages/"+id+"/read").header("Idempotency-Key",key).contentType("application/json").content("{}")).andExpect(status().isServiceUnavailable());verifyNoInteractions(inbox);
    }
    @Test void lostReadResponseIsQueriedWithoutWriting()throws Exception {
        when(inbox.readOperation(any(),eq(key),any())).thenThrow(new Problem(404,"OPERATION_NOT_OBSERVED"));
        mvc.perform(get("/api/notification/read-operations/"+key)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("OPERATION_NOT_OBSERVED")).andExpect(jsonPath("$.retryable").value(false));
        verify(inbox).readOperation(any(),eq(key),any());verifyNoMoreInteractions(inbox);
    }
    @Test void queryAndBodyLimitsFailClosed()throws Exception {
        for(String query:List.of("?tenantId=2","?page=1&page=2","?size=10","?page=0"))mvc.perform(get("/api/notification/messages"+query)).andExpect(status().isBadRequest());
        mvc.perform(post("/internal/notification/events").contentType("application/json").content(" ".repeat(65537))).andExpect(status().isContentTooLarge()).andExpect(header().string("Cache-Control","no-store"));verifyNoInteractions(inbox);
    }
    @Test void storageFailureDoesNotAcknowledgeCommitOrExposeSql()throws Exception {
        var event=MessageInboxTest.event();when(inbox.receive(any(),any())).thenThrow(new java.sql.SQLException("private JDBC detail"));
        mvc.perform(post("/internal/notification/events").contentType("application/json").content("{\"event\":"+event.encode()+"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE")).andExpect(jsonPath("$.ack").doesNotExist());
    }
    @Test void frozenFinanceTerminalPayloadHasDedicatedStrictIngress()throws Exception{
        var terminal=new MoneyFinalizedEvent(MoneyFinalizedEvent.COLLECTION,MoneyFinalizedEventTest.payload("COLLECTION_RECORDED",1,100,null));var event=terminal.domainEvent();when(inbox.receive(eq(event),any())).thenReturn(Map.of("eventId",event.eventId().toString(),"ack","COMMITTED"));
        String json=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of("eventType",terminal.eventType(),"payload",terminal.payload()));
        mvc.perform(post("/internal/notification/finance-events").contentType("application/json").content(json)).andExpect(status().isOk()).andExpect(jsonPath("$.ack").value("COMMITTED"));
        mvc.perform(post("/internal/notification/finance-events").contentType("application/json").content(json.substring(0,json.length()-1)+",\"recipientUserIds\":[]} ")).andExpect(status().isBadRequest());
        verify(inbox,times(1)).receive(eq(event),any());
    }

    @Test void feeRepairAcceptsOnlyExplicitCanonicalPlan()throws Exception{
        var event=MessageInboxTest.event();
        when(inbox.repairFeeRecipients(eq(UUID.fromString(key)),eq(event),eq(List.of("11")),eq("verified omitted reviewer"),any())).thenReturn(Map.of("operationId",key,"eventId",event.eventId().toString(),"restoredMessages",1));
        var body=new LinkedHashMap<String,Object>();body.put("operationId",key);body.put("event",tools.jackson.databind.json.JsonMapper.builder().build().readValue(event.encode(),Map.class));body.put("recipientUserIds",List.of("11"));body.put("reason","verified omitted reviewer");
        String json=tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(body);
        mvc.perform(post("/internal/notification/fee-completions/repair").contentType("application/json").content(json)).andExpect(status().isOk()).andExpect(jsonPath("$.restoredMessages").value(1));
        body.put("recipientUserIds",List.of(11));
        mvc.perform(post("/internal/notification/fee-completions/repair").contentType("application/json").content(tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(body))).andExpect(status().isBadRequest());
        mvc.perform(post("/internal/notification/fee-completions/repair?force=true").contentType("application/json").content(json)).andExpect(status().isBadRequest());
        verify(inbox,times(1)).repairFeeRecipients(any(),any(),any(),any(),any());
    }
}
