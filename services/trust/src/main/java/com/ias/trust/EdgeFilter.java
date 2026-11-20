package com.ias.trust;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public final class EdgeFilter extends OncePerRequestFilter {
 private final String role;
 public EdgeFilter(String role){this.role=role;}
 @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain)throws ServletException,IOException{
  s.setHeader("Cache-Control","no-store");
  try{
   if(!Chain.active()){if(r.getRequestURI().startsWith("/internal/"))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");chain.doFilter(r,s);return;}
   if(!role.equals(Chain.role())||!r.isSecure())throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");
   String edge=Chain.single(r,"X-IAS-Edge"),authorization=Chain.single(r,"Authorization");
   Set<String> allowed=switch(role){case "identity"->Set.of("gateway-identity","asset-identity","ticket-identity","approval-identity","stock-identity","notification-identity","finance-identity");case "asset"->Set.of("gateway-asset","identity-asset","ticket-asset","finance-asset");case "ticket"->Set.of("gateway-ticket","asset-ticket","approval-ticket","stock-ticket","notification-ticket","finance-ticket");case "approval"->Set.of("gateway-approval","notification-approval","finance-approval","stock-approval","ticket-approval");case "stock"->Set.of("gateway-stock","approval-stock","ticket-stock","notification-stock","finance-stock","identity-stock");case "notification"->Set.of("gateway-notification","approval-notification","ticket-notification","stock-notification","finance-notification");case "finance"->Set.of("gateway-finance","ticket-finance","approval-finance","notification-finance");default->Set.of();};
   if(!allowed.contains(edge)||!Chain.allowedEdge(edge,r.getMethod(),r.getRequestURI())||!MessageDigest.isEqual(authorization.getBytes(StandardCharsets.US_ASCII),("Bearer "+Chain.secret(edge)).getBytes(StandardCharsets.US_ASCII)))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");
   if(r.getRequestURI().startsWith("/internal/")&&edge.startsWith("gateway-")&&!r.getRequestURI().equals("/internal/auth/introspect"))throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");
   long deadline;try{deadline=Long.parseLong(Chain.single(r,"X-IAS-Deadline"));}catch(NumberFormatException e){throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");}
   if(deadline>System.currentTimeMillis()+10000)throw new Problem(403,"SERVICE_AUTHENTICATION_FAILED");Chain.remaining(deadline);
   r.setAttribute("ias.chain.deadline",deadline);r.setAttribute("ias.chain.trace",Chain.single(r,"X-IAS-Trace"));r.setAttribute("ias.chain.edge",edge);
   Chain.begin(deadline);
   if("/internal/service-probe".equals(r.getRequestURI())){
    s.setContentType("application/json");s.getWriter().write(Chain.JSON.writeValueAsString(Map.of("role",role,"state","AUTHENTICATED")));return;
   }
   chain.doFilter(r,s);
  }catch(Problem e){s.setStatus(e.status);s.setContentType("application/json");s.getWriter().write(Chain.JSON.writeValueAsString(Map.of("code",e.code,"traceId",UUID.randomUUID().toString(),"retryable",e.status==503)));}finally{Chain.end();}
 }
}


