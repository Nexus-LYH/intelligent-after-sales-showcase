package com.ias.identity.auth;

import com.ias.trust.Chain;
import com.ias.trust.Problem;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeeRecipientEdgeTest {
    @Test
    void onlyApprovalAndNotificationTlsEdgesReachRequestValidation() {
        try (var chain = mockStatic(Chain.class)) {
            chain.when(Chain::active).thenReturn(true);
            var controller = new FeeRecipientsController(null);
            for (String edge : new String[]{"approval-identity", "notification-identity", "ticket-identity", "gateway-identity"}) {
                var request = new MockHttpServletRequest();
                request.setSecure(true);
                request.setAttribute("ias.chain.edge", edge);
                var failure = assertThrows(Problem.class, () -> controller.recipients(Map.of(), request));
                assertEquals(edge.equals("approval-identity") || edge.equals("notification-identity") ? 400 : 403, failure.status);
            }
            var insecure = new MockHttpServletRequest();
            insecure.setAttribute("ias.chain.edge", "notification-identity");
            assertEquals(403, assertThrows(Problem.class, () -> controller.recipients(Map.of(), insecure)).status);
        }
    }
}
