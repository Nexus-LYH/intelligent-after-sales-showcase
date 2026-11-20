package com.ias.asset;
import com.ias.trust.*;import jakarta.servlet.*;import jakarta.servlet.http.*;import java.io.*;import java.util.*;import org.springframework.web.filter.OncePerRequestFilter;
/** Bound only to the new role mutation. Strict bounded parsing does not change legacy asset APIs. */
public final class MembershipRoleRequestBoundary extends OncePerRequestFilter {
 @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse response,FilterChain chain)throws IOException,ServletException {
  if(!r.getMethod().equals("POST")||!r.getRequestURI().matches("/api/asset/customer-companies/[1-9][0-9]*/members/[1-9][0-9]*/role")){chain.doFilter(r,response);return;}
  response.setHeader("Cache-Control","no-store");
  try{if(r.getContentType()==null||!r.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json"))throw new Problem(400,"VALIDATION_ERROR");byte[] bytes=r.getInputStream().readNBytes(8193);if(bytes.length>8192)throw new Problem(413,"REQUEST_TOO_LARGE");try{tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build().readValue(bytes,Map.class);}catch(Exception malformed){throw new Problem(400,"VALIDATION_ERROR");}chain.doFilter(new HttpServletRequestWrapper(r){@Override public ServletInputStream getInputStream(){var in=new ByteArrayInputStream(bytes);return new ServletInputStream(){public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}};}@Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}},response);
  }catch(Problem p){response.setStatus(p.status);response.setContentType("application/json");response.getWriter().write(Chain.JSON.writeValueAsString(Map.of("code",p.code,"retryable",false)));}
 }
}
