package com.ias.notification;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.web.filter.OncePerRequestFilter;
import com.ias.trust.Chain;
import java.io.*;
import java.util.*;

public final class NotificationNoStore extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest r,HttpServletResponse s,FilterChain chain)throws ServletException,IOException {
        s.setHeader("Cache-Control","no-store");
        if(!r.getRequestURI().startsWith("/api/notification/")&&!r.getRequestURI().startsWith("/internal/notification/")){chain.doFilter(r,s);return;}
        byte[] bytes=r.getInputStream().readNBytes(65537);if(bytes.length>65536){s.setStatus(413);s.setContentType("application/json");s.getWriter().write(Chain.JSON.writeValueAsString(Map.of("code","REQUEST_TOO_LARGE","traceId",UUID.randomUUID().toString(),"retryable",false)));return;}
        chain.doFilter(new HttpServletRequestWrapper(r){
            @Override public ServletInputStream getInputStream(){return new ServletInputStream(){private final ByteArrayInputStream in=new ByteArrayInputStream(bytes);public int read(){return in.read();}public boolean isFinished(){return in.available()==0;}public boolean isReady(){return true;}public void setReadListener(ReadListener listener){throw new UnsupportedOperationException();}};}
            @Override public BufferedReader getReader(){return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8));}
        },s);
    }
}
