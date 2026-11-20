package com.ias.finance;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bound actual streamed bytes before MVC materializes a String, including chunked input. */
public final class FinanceRequestBoundary extends OncePerRequestFilter {
 @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain)throws ServletException,IOException{
  s.setHeader("Cache-Control","no-store");
  if(!r.getMethod().equals("POST")||!r.getRequestURI().startsWith("/api/finance/")){chain.doFilter(r,s);return;}
  byte[] bytes=r.getContentLengthLong()>65536?null:r.getInputStream().readNBytes(65537);
  if(bytes==null||bytes.length>65536){s.setStatus(413);s.setContentType("application/json");s.getWriter().write(FinanceController.JSON.writeValueAsString(Map.of("code","REQUEST_TOO_LARGE","traceId",UUID.randomUUID().toString(),"retryable",false)));return;}
  var body=new HttpServletRequestWrapper(r){
   @Override public int getContentLength(){return bytes.length;}
   @Override public long getContentLengthLong(){return bytes.length;}
   @Override public ServletInputStream getInputStream(){var input=new ByteArrayInputStream(bytes);return new ServletInputStream(){public int read(){return input.read();}public int read(byte[] b,int off,int len){return input.read(b,off,len);}public boolean isFinished(){return input.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new IllegalStateException("Synchronous finance request only");}};}
   @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),StandardCharsets.UTF_8));}
  };chain.doFilter(body,s);
 }
}
