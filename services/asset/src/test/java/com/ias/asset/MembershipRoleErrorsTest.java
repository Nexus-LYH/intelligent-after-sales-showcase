package com.ias.asset;

import com.ias.trust.Problem;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MembershipRoleErrorsTest {
 @Test void foreignCompanyReferencePreserves404AtHttpBoundary()throws Exception{
  var db=mock(JdbcTemplate.class);
  when(db.queryForObject("SELECT COUNT(*) FROM asset_company WHERE tenant_id=? AND id=?",Long.class,7L,53L)).thenReturn(0L);
  MockMvcBuilders.standaloneSetup(new CompanyReferences(db)).setControllerAdvice(new MembershipRoleErrors()).build()
   .perform(post("/internal/asset/company-references").contentType("application/json").content("{\"tenantId\":\"7\",\"companyIds\":[\"53\"]}"))
   .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TARGET_NOT_ACCESSIBLE"));
 }
 @Test void actualControllerMissingSessionIs401InsteadOfUnhandled500()throws Exception{
  var controller=new MembershipRoleController(mock(JdbcTemplate.class),mock(TransactionTemplate.class));
  MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new MembershipRoleErrors()).build()
   .perform(get("/api/asset/customer-companies/31/members/51/role"))
   .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("SESSION_INVALID"))
   .andExpect(header().string("Cache-Control","no-store"));
 }
 @Test void realRoleControllerKnownConflictKeeps409AndCode()throws Exception{
  var controller=mock(MembershipRoleController.class);
  when(controller.view(anyString(),anyString(),any())).thenThrow(new Problem(409,"LAST_ENTERPRISE_ADMIN"));
  MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new MembershipRoleErrors()).build()
   .perform(get("/api/asset/customer-companies/31/members/51/role"))
   .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_ENTERPRISE_ADMIN"));
 }
 @Test void membershipFactFailurePreserves403AndDoesNotExposeException()throws Exception{
  var controller=mock(FinanceMembershipController.class);
  when(controller.membership(any(),any())).thenThrow(new Problem(403,"ACCESS_DENIED"));
  MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new MembershipRoleErrors()).build()
   .perform(post("/internal/asset/finance-membership").contentType("application/json").content("{}"))
   .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
 }
 @Test void unknownWriteAndReadStay503WithoutLeakingInternalDetails()throws Exception{
  var controller=mock(MembershipRoleController.class);
  when(controller.view(anyString(),anyString(),any())).thenThrow(new IllegalStateException("private internal detail"));
  when(controller.change(anyString(),anyString(),any(),any())).thenThrow(new IllegalStateException("private internal detail"));
  var mvc=MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new MembershipRoleErrors()).build();
  mvc.perform(get("/api/asset/customer-companies/31/members/51/role")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE")).andExpect(jsonPath("$.message").doesNotExist());
  mvc.perform(post("/api/asset/customer-companies/31/members/51/role").contentType("application/json").content("{}"))
   .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("OPERATION_RESULT_UNKNOWN")).andExpect(header().string("Cache-Control","no-store"));
 }
}
