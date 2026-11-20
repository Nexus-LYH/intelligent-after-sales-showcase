package com.ias.approval;
import com.ias.trust.Problem;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class FinanceFeeRuleHttpErrorsTest {
 @Test void deniedRuleReadIs403AndMissingPublication503()throws Exception{
  var controller=mock(FinanceFeeRuleController.class,CALLS_REAL_METHODS);
  doThrow(new Problem(403,"ACCESS_DENIED"),new Problem(503,"FEE_RULE_UNAVAILABLE")).when(controller).rule(any(),any());
  var mvc=MockMvcBuilders.standaloneSetup(controller).build();
  mvc.perform(post("/internal/approval/fee-rule").contentType("application/json").content("{}"))
   .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  mvc.perform(post("/internal/approval/fee-rule").contentType("application/json").content("{}"))
   .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("FEE_RULE_UNAVAILABLE"));
 }
 @Test void malformedAndUnknownRuleReadsNeverLeakInternalDetails()throws Exception{
  var controller=mock(FinanceFeeRuleController.class,CALLS_REAL_METHODS);
  doThrow(new IllegalStateException("internal private detail")).when(controller).rule(any(),any());
  var mvc=MockMvcBuilders.standaloneSetup(controller).build();
  mvc.perform(post("/internal/approval/fee-rule").contentType("application/json").content("{}"))
   .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE")).andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.message").doesNotExist());
  mvc.perform(post("/internal/approval/fee-rule").contentType("application/json").content("{"))
   .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
 }
}
