package com.ias.identity;

import com.ias.identity.auth.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import jakarta.servlet.FilterChain;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class BrowserGuardTest {
    @Test void passwordHasIndependentBoundedRateLimitAndAcceptsExplicitStaffOrigin() throws Exception {
        var filter=new BrowserGuardFilter(Set.of("http://127.0.0.1:5173"),1,2);
        for(int i=0;i<2;i++){
            var request=new MockHttpServletRequest("POST","/api/auth/password");request.setContentType("application/json");request.addHeader("Origin","http://127.0.0.1:5173");request.setRemoteAddr("127.0.0.1");
            var response=new MockHttpServletResponse();filter.doFilter(request,response,(req,res)->{});assertEquals(i==0?200:429,response.getStatus());
            if(i==1)assertTrue(response.getContentAsString().contains("PASSWORD_CHANGE_RATE_LIMITED"));
        }
    }
    @Test void boundedRateLimitCannotBeBypassedByChangingPreauthCookie() throws Exception {
        var filter=new BrowserGuardFilter(Set.of("http://127.0.0.1:8081"),2,2);
        FilterChain chain=(request,response)-> { ((jakarta.servlet.http.HttpServletResponse)response).setStatus(200); };
        for (int i=0;i<3;i++) {
            var request=new MockHttpServletRequest("GET","/api/auth/csrf");
            request.setRemoteAddr("127.0.0.1"); request.addHeader("Cookie",AuthService.PREAUTH_COOKIE+"=changed"+i);
            var response=new MockHttpServletResponse(); filter.doFilter(request,response,chain);
            assertEquals(i<2 ? 200:429,response.getStatus());
            if (i==2) assertEquals("60",response.getHeader("Retry-After"));
        }
    }
    @Test void explicitOriginAndRefererFallbackAreExact() throws Exception {
        var filter=new BrowserGuardFilter(Set.of("http://127.0.0.1:8081"),2,2);
        var request=new MockHttpServletRequest("POST","/api/auth/logout");
        request.setContentType("application/json"); request.addHeader("Referer","http://127.0.0.1:8081/staff/login");
        var response=new MockHttpServletResponse(); filter.doFilter(request,response,(req,res)->{}); assertEquals(200,response.getStatus());
        var hostile=new MockHttpServletRequest("POST","/api/auth/logout");
        hostile.setContentType("application/json"); hostile.addHeader("Origin","null"); hostile.addHeader("Referer","http://127.0.0.1:8081/");
        var denied=new MockHttpServletResponse(); filter.doFilter(hostile,denied,(req,res)->fail("Explicit null Origin must not fall back")); assertEquals(403,denied.getStatus());
    }
}
