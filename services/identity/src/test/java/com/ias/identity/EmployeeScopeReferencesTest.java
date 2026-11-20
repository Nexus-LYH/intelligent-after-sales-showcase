package com.ias.identity;

import com.ias.identity.auth.EmployeeScopeReferences;
import com.ias.trust.Chain;
import com.ias.trust.Problem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmployeeScopeReferencesTest {
    private final EmployeeScopeReferences references = new EmployeeScopeReferences();

    @Test void verifiesEachOwnerUsingTheSessionTenantAndExactIds() {
        long deadline = System.currentTimeMillis() + 10_000;
        var companyRequest = Map.<String,Object>of("tenantId", "7", "companyIds", List.of("11"));
        var warehouseRequest = Map.<String,Object>of("tenantId", "7", "warehouseIds", List.of("21"));
        try (var chain = mockStatic(Chain.class)) {
            chain.when(() -> Chain.fresh(anyMap(), anyLong())).thenCallRealMethod();
            chain.when(() -> Chain.remaining(anyLong())).thenCallRealMethod();
            chain.when(() -> Chain.call("identity-asset", "/internal/asset/company-references", companyRequest, "trace", deadline))
                    .thenReturn(Map.of("valid", true, "checkedAt", Instant.now().toString()));
            chain.when(() -> Chain.call("identity-stock", "/internal/stock/warehouse-references", warehouseRequest, "trace", deadline))
                    .thenReturn(Map.of("valid", true, "tenantId", "7", "warehouseIds", List.of("21"), "checkedAt", Instant.now().toString()));
            assertDoesNotThrow(() -> references.verify(7, List.of("11"), List.of("21"), "trace", deadline));
            chain.verify(() -> Chain.call("identity-asset", "/internal/asset/company-references", companyRequest, "trace", deadline));
            chain.verify(() -> Chain.call("identity-stock", "/internal/stock/warehouse-references", warehouseRequest, "trace", deadline));
        }
    }

    @Test void rejectsWrongWarehouseTenantOrIdsAndStaleProof() {
        long deadline = System.currentTimeMillis() + 10_000;
        var request = Map.<String,Object>of("tenantId", "7", "warehouseIds", List.of("21"));
        try (var chain = mockStatic(Chain.class)) {
            chain.when(() -> Chain.fresh(anyMap(), anyLong())).thenCallRealMethod();
            chain.when(() -> Chain.remaining(anyLong())).thenCallRealMethod();
            for (var response : List.of(
                    Map.<String,Object>of("valid", true, "tenantId", "8", "warehouseIds", List.of("21"), "checkedAt", Instant.now().toString()),
                    Map.<String,Object>of("valid", true, "tenantId", "7", "warehouseIds", List.of("22"), "checkedAt", Instant.now().toString()),
                    Map.<String,Object>of("valid", true, "tenantId", "7", "warehouseIds", List.of("21"), "checkedAt", Instant.now().minusSeconds(30).toString()))) {
                chain.when(() -> Chain.call("identity-stock", "/internal/stock/warehouse-references", request, "trace", deadline)).thenReturn(response);
                assertEquals(503, assertThrows(Problem.class, () -> references.verify(7, List.of(), List.of("21"), "trace", deadline)).status);
            }
        }
    }

    @Test void ownerRejectionIsNotConvertedIntoAnAcceptedScope() {
        long deadline = System.currentTimeMillis() + 10_000;
        var request = Map.<String,Object>of("tenantId", "7", "companyIds", List.of("11"));
        try (var chain = mockStatic(Chain.class)) {
            chain.when(() -> Chain.fresh(anyMap(), anyLong())).thenCallRealMethod();
            chain.when(() -> Chain.remaining(anyLong())).thenCallRealMethod();
            chain.when(() -> Chain.call("identity-asset", "/internal/asset/company-references", request, "trace", deadline))
                    .thenThrow(new Problem(404, "TARGET_NOT_ACCESSIBLE"));
            assertEquals(404, assertThrows(Problem.class, () -> references.verify(7, List.of("11"), List.of(), "trace", deadline)).status);
        }
    }
}

