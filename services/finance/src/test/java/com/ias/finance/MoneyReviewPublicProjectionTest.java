package com.ias.finance;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MoneyReviewPublicProjectionTest {
    @Test
    void staffCanIdentifyTheApplicantWhileCustomerProjectionKeepsPublicFields() throws Exception {
        var fixture = new MoneyReviewStoreTest();
        fixture.setup();
        fixture.store.submit(fixture.f.actor, fixture.f.owner, UUID.randomUUID(), fixture.proposal);
        var access = mock(FinanceAccess.class);
        var request = new MockHttpServletRequest();
        var controller = new MoneyReviewController(fixture.f.source, access);
        for (String type : new String[]{"EMPLOYEE", "CUSTOMER"}) {
            var principal = new FinanceAccess.Principal(fixture.f.actor, type);
            when(access.reviewPrincipal(request)).thenReturn(principal);
            when(access.review(request, principal)).thenReturn((actor, owner, event, proposal) -> {});
            var result = (Map<?, ?>) controller.read(fixture.f.owner.faultGroupId().toString(),
                    fixture.proposal.reviewId().toString(), request);
            assertEquals("REDUCTION", result.get("kind"));
            assertEquals("500", result.get("proposedCents"));
            assertEquals("EMPLOYEE".equals(type), result.containsKey("applicantUserId"));
            assertFalse(result.containsKey("evidenceFileId"));
            assertFalse(result.containsKey("context"));
        }
    }
}
