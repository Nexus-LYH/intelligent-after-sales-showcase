package com.ias.identity.auth;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.util.*;

/** Browser protocol guard. Session-bound CSRF is checked exactly once by the transactional auth domain. */
public final class BrowserGuardFilter extends OncePerRequestFilter {
    private final Set<String> origins;
    private final int loginLimit, csrfLimit;
    private final Map<String,Window> windows=new HashMap<>();
    private record Window(long start, int count) { }
    public BrowserGuardFilter(Set<String> origins,int loginLimit,int csrfLimit) { this.origins=Set.copyOf(origins); this.loginLimit=loginLimit; this.csrfLimit=csrfLimit; }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        request.setAttribute(AuthHttp.TRACE,UUID.randomUUID().toString());
        request.setAttribute(AuthHttp.DEADLINE,com.ias.trust.Chain.active()?com.ias.trust.Chain.nanoDeadline(com.ias.trust.Chain.deadline(request)):System.nanoTime()+10_000_000_000L);
        response.setHeader("Cache-Control","no-store"); response.setHeader("X-Trace-Id",AuthHttp.trace(request));
        try {
            String path=request.getRequestURI();
            boolean revoke=path.matches("/api/identity/users/[^/]+/(sessions/revoke|status|scope)");
            boolean password=path.equals("/api/auth/password");
            boolean write="POST".equals(request.getMethod()) && (path.equals("/api/auth/login") || path.equals("/api/auth/logout") || path.equals("/api/identity/employees") || password || revoke);
            boolean csrf="GET".equals(request.getMethod()) && path.equals("/api/auth/csrf");
            if (write || csrf) {
                AuthHttp.source(request,origins,write);
                AuthHttp.cookie(request,AuthService.AUTH_COOKIE); AuthHttp.cookie(request,AuthService.PREAUTH_COOKIE);
                if (csrf || path.equals("/api/auth/login")) rate(request.getRemoteAddr()+":"+(csrf ? "csrf":"login"),csrf ? csrfLimit:loginLimit,csrf);
                if (write && revoke) {
                    try { rate(request.getRemoteAddr()+":revoke",loginLimit,false); }
                    catch (AuthFailure failure) { throw new AuthFailure(429,"REVOCATION_RATE_LIMITED",null); }
                }
                if (write && password) {
                    try { rate(request.getRemoteAddr()+":password",loginLimit,false); }
                    catch (AuthFailure failure) { throw new AuthFailure(429,"PASSWORD_CHANGE_RATE_LIMITED",null); }
                }
            }
            if (write) {
                String contentType=request.getContentType();
                if (contentType==null || !MediaType.APPLICATION_JSON.isCompatibleWith(MediaType.parseMediaType(contentType))) throw new AuthFailure(400,"VALIDATION_ERROR","REUSE_PREAUTH");
                int max=path.matches("/api/identity/users/[^/]+/scope")?32768:8192;byte[] body=request.getInputStream().readNBytes(max+1);
                if (body.length>max) throw new AuthFailure(400,"VALIDATION_ERROR","REUSE_PREAUTH");
                chain.doFilter(new BufferedRequest(request,body),response);
            } else chain.doFilter(request,response);
        } catch (AuthFailure e) { AuthHttp.writeFailure(e,request,response); }
        catch (IllegalArgumentException e) { AuthHttp.writeFailure(new AuthFailure(400,"VALIDATION_ERROR","REUSE_PREAUTH"),request,response); }
    }
    private synchronized void rate(String key,int limit,boolean csrf) {
        long now=System.nanoTime();
        windows.entrySet().removeIf(e->now-e.getValue().start>=60_000_000_000L);
        Window window=windows.get(key);
        if ((window==null && windows.size()>=4096) || (window!=null && window.count>=limit)) throw new AuthFailure(429,csrf ? "CSRF_RATE_LIMITED":"LOGIN_RATE_LIMITED","REUSE_PREAUTH");
        windows.put(key,new Window(window==null ? now:window.start,window==null ? 1:window.count+1));
    }
    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BufferedRequest(HttpServletRequest request,byte[] body) { super(request); this.body=body; }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream stream=new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return stream.read(); }
                @Override public boolean isFinished() { return stream.available()==0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8)); }
    }
}
