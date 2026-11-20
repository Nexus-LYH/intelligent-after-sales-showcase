package com.ias.identity.auth;

import jakarta.servlet.http.*;
import org.springframework.http.ResponseCookie;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.net.URI;
import java.util.*;

public final class AuthHttp {
    private AuthHttp() { }
    public static final String TRACE="identity.trace", DEADLINE="identity.deadline";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public static String trace(HttpServletRequest request) { return (String)request.getAttribute(TRACE); }
    public static long deadline(HttpServletRequest request) { return (Long)request.getAttribute(DEADLINE); }
    public static String cookie(HttpServletRequest request, String name) {
        String result=null; boolean found=false;
        var headers=request.getHeaders("Cookie");
        while (headers.hasMoreElements()) {
            for (String part:headers.nextElement().split(";")) {
                int index=part.indexOf('=');
                if (index>=0 && part.substring(0,index).strip().equals(name)) {
                    if (found) throw new AuthFailure(400,"AMBIGUOUS_COOKIE","NEW_PREAUTH");
                    found=true; result=part.substring(index+1).strip();
                }
            }
        }
        return result;
    }
    public static String singleHeader(HttpServletRequest request, String name) {
        var values=Collections.list(request.getHeaders(name));
        if (values.size()>1) throw new AuthFailure(400,"VALIDATION_ERROR","REUSE_PREAUTH");
        return values.isEmpty() ? null : values.getFirst();
    }
    public static void source(HttpServletRequest request, Set<String> allowed, boolean write) {
        String origin=singleHeader(request,"Origin");
        if (origin!=null) { if (!allowed.contains(origin)) rejected(); return; }
        String referer=singleHeader(request,"Referer");
        if (referer!=null) {
            try {
                URI uri=URI.create(referer);
                if (uri.getUserInfo()!=null || uri.getHost()==null || uri.getScheme()==null) rejected();
                String from=uri.getScheme()+"://"+uri.getRawAuthority();
                if (!allowed.contains(from)) rejected();
                return;
            } catch (IllegalArgumentException e) { rejected(); }
        }
        if (write || "cross-site".equals(request.getHeader("Sec-Fetch-Site")) || "same-site".equals(request.getHeader("Sec-Fetch-Site"))) rejected();
    }
    private static void rejected() { throw new AuthFailure(403,"ORIGIN_REJECTED","REUSE_PREAUTH"); }
    public static void setCookie(HttpServletResponse response, String name, String token) {
        response.addHeader("Set-Cookie",ResponseCookie.from(name,token).httpOnly(true).secure(false).sameSite("Lax").path("/").build().toString());
    }
    public static void clearCookie(HttpServletResponse response, String name) {
        response.addHeader("Set-Cookie",ResponseCookie.from(name,"").httpOnly(true).secure(false).sameSite("Lax").path("/").maxAge(0).build().toString());
    }
    public static Map<String,Object> error(AuthFailure failure, String trace) {
        Map<String,Object> body=new LinkedHashMap<>();
        body.put("code",failure.code);
        body.put("message",switch (failure.status) {
            case 401 -> "身份无效或登录失败，请重新核验或联系管理员";
            case 403 -> "请求未获允许";
            case 409 -> "请求状态冲突，请核对当前身份";
            case 429 -> "请求过于频繁，请稍后重试";
            case 503 -> "身份服务暂不可用，请核对结果后继续";
            default -> "请求无效";
        });
        body.put("traceId",trace==null ? UUID.randomUUID().toString() : trace);
        if (failure.recovery!=null) body.put("loginRecovery",failure.recovery);
        body.put("retryable",failure.status==503 || failure.status==429);
        return body;
    }
    public static void invalidCookies(AuthFailure failure, HttpServletResponse response) {
        if ("SESSION_INVALID".equals(failure.code)) clearCookie(response,AuthService.AUTH_COOKIE);
        if ("PREAUTH_EXPIRED".equals(failure.code)) clearCookie(response,AuthService.PREAUTH_COOKIE);
        if ("AMBIGUOUS_COOKIE".equals(failure.code)) {
            clearCookie(response,AuthService.AUTH_COOKIE); clearCookie(response,AuthService.PREAUTH_COOKIE);
        }
    }
    public static void writeFailure(AuthFailure failure,HttpServletRequest request,HttpServletResponse response) throws IOException {
        response.setStatus(failure.status); response.setContentType("application/json;charset=UTF-8");
        invalidCookies(failure,response);
        if (failure.status==429) response.setHeader("Retry-After","60");
        response.getWriter().write(JSON.writeValueAsString(error(failure,trace(request))));
    }
}
