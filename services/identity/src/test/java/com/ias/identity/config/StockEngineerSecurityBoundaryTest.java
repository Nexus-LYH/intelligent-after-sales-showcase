package com.ias.identity.config;
import com.ias.trust.EdgeFilter;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
/** Production Spring security chain only; probe controller is not an identity/trust runtime replacement. */
class StockEngineerSecurityBoundaryTest {
 static AnnotationConfigWebApplicationContext context;static MockMvc mvc;
 @Configuration @EnableWebSecurity @EnableWebMvc static class SecurityOnly {
  @Bean SecurityFilterChain chain(HttpSecurity http)throws Exception{var env=new StandardEnvironment();env.setActiveProfiles("local");return new IdentityConfiguration().security(http,env,"127.0.0.1","http://127.0.0.1:5173",30,100);}
  @Bean Probe probe(){return new Probe();}
 }
 @RestController static class Probe {
  @RequestMapping("/internal/identity/stock-engineer-qualification") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED) void exact(){}
  @RequestMapping("/internal/identity/stock-engineer-qualification/extra") void neighbor(){}
  @RequestMapping("/internal/identity/other-domain") void other(){}
  @RequestMapping("/internal/identity/customer-eligibility") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED) void customer(){}
 }
 @BeforeAll static void prepare(){context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.register(SecurityOnly.class);context.refresh();mvc=MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();}
 @AfterAll static void close(){context.close();}
 @Test void exactPostDelegatesCsrfAndReachesDomainWithoutFrameworkLogin()throws Exception{mvc.perform(post("/internal/identity/stock-engineer-qualification").contentType("application/json").content("{}" )).andExpect(status().isAccepted());}
 @Test void getAndNeighboringWritesRemainDenied()throws Exception{mvc.perform(get("/internal/identity/stock-engineer-qualification")).andExpect(status().isForbidden());mvc.perform(post("/internal/identity/stock-engineer-qualification/extra")).andExpect(status().isForbidden());mvc.perform(post("/internal/identity/other-domain")).andExpect(status().isForbidden());}
 @Test void frameworkPermissionDoesNotBypassTrustedEdgeFilter()throws Exception{var protectedMvc=MockMvcBuilders.webAppContextSetup(context).addFilters(new EdgeFilter("identity")).apply(springSecurity()).build();protectedMvc.perform(post("/internal/identity/stock-engineer-qualification").secure(true).contentType("application/json").content("{}")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SERVICE_AUTHENTICATION_FAILED"));}
 @Test void customerEligibilityHasExactPostDelegationAndNoGetOrNeighborGrant()throws Exception{mvc.perform(post("/internal/identity/customer-eligibility").contentType("application/json").content("{}")).andExpect(status().isAccepted());mvc.perform(get("/internal/identity/customer-eligibility")).andExpect(status().isForbidden());mvc.perform(post("/internal/identity/customer-eligibility/other")).andExpect(status().isForbidden());}
}
