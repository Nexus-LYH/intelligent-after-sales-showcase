package com.ias.identity.auth;

import jakarta.servlet.http.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService service;
    public AuthController(AuthService service) { this.service=service; }
    public record LoginRequest(String realm,String tenantCode,String account,String password) {
        @Override public String toString() { return "LoginRequest[redacted]"; }
    }
    private String auth(HttpServletRequest request) {
        AuthHttp.cookie(request,AuthService.PREAUTH_COOKIE);
        return AuthHttp.cookie(request,AuthService.AUTH_COOKIE);
    }
    @GetMapping("/csrf") public Map<String,Object> csrf(HttpServletRequest request,HttpServletResponse response) {
        AuthService.Reply reply=service.csrf(auth(request),AuthHttp.cookie(request,AuthService.PREAUTH_COOKIE),AuthHttp.deadline(request));
        service.checkDelivery(reply,AuthHttp.deadline(request));
        if (reply.cookieToken!=null) AuthHttp.setCookie(response,reply.auth ? AuthService.AUTH_COOKIE:AuthService.PREAUTH_COOKIE,reply.cookieToken);
        return reply.body;
    }
    @PostMapping("/login") public Map<String,Object> login(@RequestBody LoginRequest body,HttpServletRequest request,HttpServletResponse response) {
        AuthService.Reply reply=service.login(auth(request),AuthHttp.cookie(request,AuthService.PREAUTH_COOKIE),AuthHttp.singleHeader(request,"X-CSRF-TOKEN"),
            AuthHttp.singleHeader(request,"X-Login-Attempt-Id"),body.realm,body.tenantCode,body.account,body.password,AuthHttp.trace(request),AuthHttp.deadline(request));
        service.checkDelivery(reply,AuthHttp.deadline(request));
        AuthHttp.setCookie(response,AuthService.AUTH_COOKIE,reply.cookieToken);
        AuthHttp.clearCookie(response,AuthService.PREAUTH_COOKIE);
        return reply.body;
    }
    @GetMapping("/me") public Map<String,Object> me(HttpServletRequest request,HttpServletResponse response) {
        AuthService.Reply reply=service.me(auth(request),AuthHttp.deadline(request));
        service.checkDelivery(reply,AuthHttp.deadline(request));
        if (reply.cookieToken!=null) AuthHttp.setCookie(response,AuthService.AUTH_COOKIE,reply.cookieToken);
        return reply.body;
    }
    @PostMapping("/logout") public ResponseEntity<Void> logout(HttpServletRequest request,HttpServletResponse response) {
        service.logout(auth(request),AuthHttp.singleHeader(request,"X-CSRF-TOKEN"),AuthHttp.trace(request),AuthHttp.deadline(request));
        AuthHttp.clearCookie(response,AuthService.AUTH_COOKIE); AuthHttp.clearCookie(response,AuthService.PREAUTH_COOKIE);
        return ResponseEntity.noContent().build();
    }
}
