package com.ias.gateway;
import org.junit.jupiter.api.Test;
import com.ias.trust.Chain;
import static org.junit.jupiter.api.Assertions.*;
class MembershipRoleGatewayBoundaryTest {
 @Test void employeeAccessIsReadOnlyAndIdentityScoped(){String p="/api/identity/users/31/access";assertTrue(GatewayApplication.allowed("GET",p));assertFalse(GatewayApplication.allowed("POST",p));assertFalse(GatewayApplication.allowed("PATCH",p));assertTrue(Chain.allowedEdge("gateway-identity","GET",p));assertFalse(Chain.allowedEdge("gateway-identity","POST",p));assertFalse(Chain.allowedEdge("gateway-asset","GET",p));}
 @Test void employeeStatusUsesExactWriteAndRecoveryRoutes(){String p="/api/identity/users/31/status",q="/api/identity/users/31/status-operations/00000000-0000-4000-8000-000000000001";assertTrue(GatewayApplication.allowed("POST",p));assertFalse(GatewayApplication.allowed("GET",p));assertTrue(GatewayApplication.allowed("GET",q));assertFalse(GatewayApplication.allowed("POST",q));assertTrue(Chain.allowedEdge("gateway-identity","POST",p));assertFalse(Chain.allowedEdge("gateway-identity","GET",p));}
 @Test void employeeCreationUsesExactRoutes(){String p="/api/identity/employees",q="/api/identity/employee-create-operations/00000000-0000-4000-8000-000000000001";assertTrue(GatewayApplication.allowed("POST",p));assertFalse(GatewayApplication.allowed("GET",p));assertTrue(GatewayApplication.allowed("GET",q));assertFalse(GatewayApplication.allowed("POST",q));assertTrue(Chain.allowedEdge("gateway-identity","POST",p));assertFalse(Chain.allowedEdge("gateway-identity","GET",p));}
 @Test void employeeScopeUsesExactWriteAndRecoveryRoutes(){String p="/api/identity/users/31/scope",q="/api/identity/users/31/scope-operations/00000000-0000-4000-8000-000000000001";assertTrue(GatewayApplication.allowed("POST",p));assertFalse(GatewayApplication.allowed("GET",p));assertTrue(GatewayApplication.allowed("GET",q));assertFalse(GatewayApplication.allowed("POST",q));assertTrue(Chain.allowedEdge("gateway-identity","POST",p));assertFalse(Chain.allowedEdge("gateway-identity","GET",p));}
 @Test void quoteCandidateEdgesRemainPrivateAndMethodExact(){
  for(String path:new String[]{"/internal/ticket/finance-quotes/prepare","/internal/ticket/finance-quotes/source-commit"}){
   assertTrue(Chain.allowedEdge("finance-ticket","POST",path));
   assertFalse(Chain.allowedEdge("finance-ticket","GET",path));
   assertFalse(Chain.allowedEdge("stock-ticket","POST",path));
   assertFalse(Chain.allowedEdge("gateway-ticket","POST",path));
   assertFalse(GatewayApplication.allowed("POST",path));
  }
  assertFalse(Chain.allowedEdge("finance-ticket","POST","/internal/ticket/finance-quotes/complete"));
 }
 @Test void explicitRoleRoutesDoNotOpenAdjacentAdministrationOrInternalPaths(){
  String path="/api/asset/customer-companies/31/members/51/role";
  assertTrue(GatewayApplication.allowed("GET",path));
  assertTrue(GatewayApplication.allowed("POST",path));
  assertFalse(GatewayApplication.allowed("PATCH",path));
  assertFalse(GatewayApplication.allowed("DELETE",path));
  assertFalse(GatewayApplication.allowed("POST",path+"/grant"));
  String operation="/api/asset/customer-companies/31/membership-role-operations/00000000-0000-4000-8000-000000000001";
  assertTrue(GatewayApplication.allowed("GET",operation));
  assertFalse(GatewayApplication.allowed("POST",operation));
  assertFalse(GatewayApplication.allowed("GET","/internal/asset/finance-membership"));
 }
}
