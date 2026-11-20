package com.ias.asset;
import org.junit.jupiter.api.Test;import org.springframework.mock.web.*;import static org.junit.jupiter.api.Assertions.*;
class MembershipRoleRequestBoundaryTest {
 MockHttpServletResponse request(String content)throws Exception{var r=new MockHttpServletRequest("POST","/api/asset/customer-companies/31/members/51/role");r.setContentType("application/json");r.setContent(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));var response=new MockHttpServletResponse();new MembershipRoleRequestBoundary().doFilter(r,response,(request,out)->((jakarta.servlet.http.HttpServletResponse)out).setStatus(204));return response;}
 @Test void duplicateRoleKeysAreRejectedInsteadOfLastKeyWins()throws Exception{assertEquals(400,request("{\"role\":\"MEMBER\",\"role\":\"ADMIN\"}").getStatus());assertEquals(400,request("[]").getStatus());}
 @Test void bodyIsBoundedAndValidBodyContinues()throws Exception{assertEquals(413,request(" ".repeat(8193)).getStatus());assertEquals(204,request("{\"role\":\"ADMIN\"}").getStatus());}
}
