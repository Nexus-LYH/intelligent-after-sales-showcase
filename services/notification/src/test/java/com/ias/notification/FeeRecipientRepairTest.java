package com.ias.notification;

import com.ias.trust.Chain;
import com.ias.trust.Problem;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class FeeRecipientRepairTest {
    FeeNotificationTest fixture;
    DomainEvent event;
    MockHttpServletRequest request;
    boolean expanded;
    boolean revoked;

    @BeforeEach
    void prepare() throws Exception {
        fixture = new FeeNotificationTest();
        fixture.prepare();
        var application = UUID.randomUUID().toString();
        event = new DomainEvent(UUID.randomUUID(), "approval.fee_completed", 1, "1", application, "4",
                Instant.parse("2026-11-16T12:00:00.000Z"), "approval", UUID.randomUUID(), Map.ofEntries(
                Map.entry("ticketId", UUID.randomUUID().toString()), Map.entry("roundId", UUID.randomUUID().toString()),
                Map.entry("feeGroupId", UUID.randomUUID().toString()), Map.entry("generation", "1"),
                Map.entry("completionId", UUID.randomUUID().toString()), Map.entry("applicationId", application),
                Map.entry("quoteId", UUID.randomUUID().toString()), Map.entry("quoteVersion", "1"),
                Map.entry("companyId", "2"), Map.entry("stateCode", "COMPLETED")));
        request = new MockHttpServletRequest();
        request.setSecure(true);
        request.setAttribute("ias.chain.edge", "approval-notification");
        fixture.inbox = new MessageInbox(fixture.db, new NotificationAccess() {
            public Actor actor(HttpServletRequest r, boolean write) { return new Actor(1, 10); }
            public List<Recipient> recipients(DomainEvent e, HttpServletRequest r) {
                if (revoked) throw new Problem(403, "REVOKED");
                return expanded ? List.of(new Recipient(10, "APPLICANT"), new Recipient(11, "REVIEWER"))
                        : List.of(new Recipient(10, "APPLICANT"));
            }
            public boolean visible(Actor actor, DomainEvent e, String kind, HttpServletRequest r) { return !revoked; }
        });
        fixture.inbox.receive(event, request);
        expanded = true;
    }

    @AfterEach
    void close() throws Exception { fixture.close(); }

    @Test
    void fillsOnlyMissingRecipientAndRetainsOriginalConsumptionAndReadAudit() throws Exception {
        String original = fixture.scalar("SELECT id FROM notification_message");
        fixture.inbox.markRead(new NotificationAccess.Actor(1, 10), original, UUID.randomUUID().toString(), request);
        UUID operation = UUID.randomUUID();
        var result = fixture.inbox.repairFeeRecipients(operation, event, List.of("10", "11"), "verified omitted fee reviewer", request);
        assertEquals(1, result.get("restoredMessages"));
        assertEquals(result, fixture.inbox.repairFeeRecipients(operation, event, List.of("11", "10"), "verified omitted fee reviewer", request));
        assertEquals(2, fixture.count("notification_message"));
        assertEquals(2, fixture.count("notification_email_capture"));
        assertEquals(1, fixture.count("event_inbox"));
        assertEquals(1, fixture.count("notification_message_read"));
        assertEquals(1, fixture.count("notification_read_audit"));
        assertEquals(1, fixture.count("notification_fee_recipient_repair"));
        assertEquals(409, assertThrows(Problem.class, () -> fixture.inbox.repairFeeRecipients(operation, event, List.of("11"), "verified omitted fee reviewer", request)).status);
        assertEquals(409, assertThrows(Problem.class, () -> fixture.inbox.repairFeeRecipients(operation, event, List.of("10", "11"), "changed reason", request)).status);
    }

    @Test
    void changedEventAndUnqualifiedOrInvalidTargetsCannotRepair() throws Exception {
        var payload = new HashMap<>(event.payload());
        payload.put("quoteVersion", "2");
        var changed = new DomainEvent(event.eventId(), event.type(), 1, event.tenantId(), event.aggregateId(), event.aggregateVersion(), event.occurredAt(), event.producer(), event.correlationId(), payload);
        assertEquals(409, assertThrows(Problem.class, () -> fixture.inbox.repairFeeRecipients(UUID.randomUUID(), changed, List.of("11"), "repair", request)).status);
        assertEquals(403, assertThrows(Problem.class, () -> fixture.inbox.repairFeeRecipients(UUID.randomUUID(), event, List.of("12"), "repair", request)).status);
        for (var targets : List.of(List.<String>of(), List.of("11", "11"), List.of("0"), List.of("9999999999999999999"))) {
            assertEquals(400, assertThrows(Problem.class, () -> fixture.inbox.repairFeeRecipients(UUID.randomUUID(), event, targets, "repair", request)).status);
        }
        assertEquals(0, fixture.count("notification_fee_recipient_repair"));
    }

    @Test
    void captureFailureRollsBackNewMessagesAndRepairAudit() throws Exception {
        fixture.sql("ALTER TABLE notification_email_capture ADD CONSTRAINT repair_capture_failure CHECK(recipient_user_id<>11)");
        assertThrows(Exception.class, () -> fixture.inbox.repairFeeRecipients(UUID.randomUUID(), event, List.of("11"), "repair", request));
        assertEquals(1, fixture.count("notification_message"));
        assertEquals(1, fixture.count("notification_email_capture"));
        assertEquals(1, fixture.count("event_inbox"));
        assertEquals(0, fixture.count("notification_fee_recipient_repair"));
    }

    @Test
    void concurrentDifferentOperationsNeverDuplicateRecipient() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Map<String,Object>> repair = () -> fixture.inbox.repairFeeRecipients(UUID.randomUUID(), event, List.of("11"), "repair", request);
            var first = pool.submit(repair);
            var second = pool.submit(repair);
            int restored = ((Number) first.get(10, TimeUnit.SECONDS).get("restoredMessages")).intValue()
                    + ((Number) second.get(10, TimeUnit.SECONDS).get("restoredMessages")).intValue();
            assertEquals(1, restored);
            assertEquals(2, fixture.count("notification_message"));
            assertEquals(2, fixture.count("notification_email_capture"));
            assertEquals(2, fixture.count("notification_fee_recipient_repair"));
        }
    }

    @Test
    void replayStillChecksCurrentQualification() throws Exception {
        UUID operation = UUID.randomUUID();
        fixture.inbox.repairFeeRecipients(operation, event, List.of("11"), "repair", request);
        revoked = true;
        assertEquals(403, assertThrows(Problem.class, () -> fixture.inbox.repairFeeRecipients(operation, event, List.of("11"), "repair", request)).status);
        assertEquals(1, fixture.count("notification_fee_recipient_repair"));
    }

    @Test
    void repairRouteIsLimitedToApprovalTlsEdge() {
        String path = "/internal/notification/fee-completions/repair";
        assertTrue(Chain.allowedEdge("approval-notification", "POST", path));
        assertFalse(Chain.allowedEdge("approval-notification", "GET", path));
        assertFalse(Chain.allowedEdge("ticket-notification", "POST", path));
        assertFalse(Chain.allowedEdge("gateway-notification", "POST", path));
        request.setAttribute("ias.chain.edge", "ticket-notification");
        assertEquals(403, assertThrows(Problem.class, () -> fixture.inbox.repairFeeRecipients(UUID.randomUUID(), event, List.of("11"), "repair", request)).status);
    }
}
