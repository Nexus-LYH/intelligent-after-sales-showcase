package com.ias.approval;
import com.ias.trust.Problem;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import javax.sql.DataSource;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class FeeNotificationErrorHttpTest {
 @Test void upstreamAuthorizationFailuresKeepTheirStatus()throws Exception{
  for(int status:new int[]{403,404,503}){
   var controller=spy(new FeeNotificationContextController(mock(DataSource.class)));
   doThrow(new Problem(status,"ACCESS_DENIED")).when(controller).context(anyMap(),any());
   var mvc=MockMvcBuilders.standaloneSetup(controller).build();
   mvc.perform(post("/internal/approval/fee-notification-context").contentType("application/json").content("{}"))
    .andExpect(status().is(status)).andExpect(header().string("Cache-Control","no-store"))
    .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }
 }
 @Test void unknownOwnerFailureIsUnavailable()throws Exception{
  var controller=spy(new FeeNotificationContextController(mock(DataSource.class)));
  doThrow(new IllegalStateException("private details")).when(controller).context(anyMap(),any());
  MockMvcBuilders.standaloneSetup(controller).build().perform(post("/internal/approval/fee-notification-context").contentType("application/json").content("{}"))
   .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
 }
}
