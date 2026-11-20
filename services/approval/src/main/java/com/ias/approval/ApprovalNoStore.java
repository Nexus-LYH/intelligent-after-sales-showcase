package com.ias.approval;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ReadListener;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public final class ApprovalNoStore extends OncePerRequestFilter {
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Content-Type-Options", "nosniff");
        if ("POST".equals(request.getMethod()) && request.getRequestURI().startsWith("/api/approval/")) {
            byte[] body = request.getInputStream().readNBytes(65537);
            if (body.length > 65536) {
                response.setStatus(413); response.setContentType("application/json");
                response.getWriter().write("{\"code\":\"REQUEST_TOO_LARGE\",\"retryable\":false,\"traceId\":\""+UUID.randomUUID()+"\"}");
                return;
            }
            var wrapped = new HttpServletRequestWrapper(request) {
                @Override public ServletInputStream getInputStream() {
                    var input = new ByteArrayInputStream(body);
                    return new ServletInputStream() {
                        public int read() { return input.read(); }
                        public boolean isFinished() { return input.available() == 0; }
                        public boolean isReady() { return true; }
                        public void setReadListener(ReadListener listener) { throw new IllegalStateException("Synchronous JSON endpoint"); }
                    };
                }
                @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
                @Override public int getContentLength() { return body.length; }
                @Override public long getContentLengthLong() { return body.length; }
            };
            chain.doFilter(wrapped, response); return;
        }
        chain.doFilter(request, response);
    }
}
