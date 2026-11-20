package com.ias.gateway;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssetMemberReadGatewayTest {
    @Test
    void memberListUsesTheSameReadPermissionAsCompanyDetails() {
        assertEquals("ASSET_READ", GatewayApplication.assetOperation("GET", "/api/asset/customer-companies/246/members"));
        assertEquals("ASSET_READ", GatewayApplication.assetOperation("GET", "/api/asset/customer-companies/246"));
        assertTrue(GatewayApplication.allowed("GET", "/api/asset/customer-companies/246/members"));
    }

    @Test
    void enrollmentAndRoleOperationsKeepTheirExistingAuthorization() {
        String members = "/api/asset/customer-companies/246/members";
        assertEquals("PROVISION", GatewayApplication.assetOperation("POST", members));
        assertEquals("PROVISION_READ", GatewayApplication.assetOperation("GET", members + "/51/role"));
        assertEquals("PROVISION", GatewayApplication.assetOperation("POST", members + "/51/role"));
        assertEquals("PROVISION_READ", GatewayApplication.assetOperation("GET", "/api/asset/customer-enrollments/00000000-0000-4000-8000-000000000001"));
        assertEquals("PROVISION", GatewayApplication.assetOperation("POST", "/api/asset/customer-enrollments/00000000-0000-4000-8000-000000000001/resume"));
    }
}
