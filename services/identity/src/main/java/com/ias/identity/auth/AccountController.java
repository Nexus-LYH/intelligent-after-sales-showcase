package com.ias.identity.auth;

import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

import java.util.*;

@RestController
public class AccountController {
    private final AuthService service;

    public AccountController(AuthService service) {
        this.service = service;
    }

    private String auth(HttpServletRequest r) {
        AuthHttp.cookie(r, AuthService.PREAUTH_COOKIE);
        return AuthHttp.cookie(r, AuthService.AUTH_COOKIE);
    }

    @PostMapping("/api/auth/password")
    public Map<String, Object> password(@RequestBody JsonNode b, HttpServletRequest r, HttpServletResponse response) {
        if (!b.isObject() || b.size() != 3) throw bad();
        for (String field : List.of("currentPassword", "newPassword", "confirmPassword"))
            if (!b.has(field) || !b.get(field).isTextual()) throw bad();
        Map<String, Object> result = service.changePassword(auth(r), AuthHttp.singleHeader(r, "X-CSRF-TOKEN"), b.get("currentPassword").asText(), b.get("newPassword").asText(), b.get("confirmPassword").asText(), AuthHttp.trace(r), AuthHttp.deadline(r));
        AuthHttp.clearCookie(response, AuthService.AUTH_COOKIE);
        AuthHttp.clearCookie(response, AuthService.PREAUTH_COOKIE);
        return result;
    }

    @GetMapping("/api/identity/users")
    public Map<String, Object> users(HttpServletRequest r) {
        for (var p : r.getParameterMap().entrySet())
            if (!Set.of("keyword", "status", "page", "size").contains(p.getKey()) || p.getValue().length != 1)
                throw bad();
        int page = number(r.getParameter("page"), 1), size = number(r.getParameter("size"), 20);
        String status = r.getParameter("status"), keyword = r.getParameter("keyword");
        if (page < 1 || page > 1_000_000 || !Set.of(20, 50, 100).contains(size) || (status != null && !Set.of("0", "1").contains(status)) || (keyword != null && AuthCrypto.codePoints(keyword) > 100))
            throw bad();
        return service.users(auth(r), keyword, status == null ? null : Integer.valueOf(status), page, size, null, AuthHttp.deadline(r));
    }

    @GetMapping("/api/identity/users/{userId}")
    public Map<String, Object> detail(@PathVariable String userId, HttpServletRequest r) {
        if (!r.getParameterMap().isEmpty()) throw bad();
        long id;
        try {
            if (!userId.matches("[1-9][0-9]{0,18}")) throw bad();
            id = Long.parseLong(userId);
        } catch (NumberFormatException e) {
            throw bad();
        }
        return service.users(auth(r), null, null, 1, 20, id, AuthHttp.deadline(r));
    }
    @GetMapping("/api/identity/users/{userId}/access")
    public Map<String,Object> access(@PathVariable String userId,HttpServletRequest r){
        if(!r.getParameterMap().isEmpty()||!userId.matches("[1-9][0-9]{0,18}"))throw bad();
        try{return service.employeeAccess(auth(r),Long.parseLong(userId),AuthHttp.deadline(r));}catch(NumberFormatException e){throw bad();}
    }
    @PostMapping("/api/identity/users/{userId}/status")
    public Map<String,Object> status(@PathVariable String userId,@RequestBody JsonNode b,HttpServletRequest r){
        if(!r.getParameterMap().isEmpty()||!userId.matches("[1-9][0-9]{0,18}")||!b.isObject()||b.size()!=4||!b.has("operationId")||!b.get("operationId").isTextual()||!b.has("expectedSecurityVersion")||!b.get("expectedSecurityVersion").isTextual()||!b.has("status")||!b.get("status").isIntegralNumber()||!b.has("reason")||!b.get("reason").isTextual())throw bad();
        String operation=b.get("operationId").asText(),version=b.get("expectedSecurityVersion").asText();if(!operation.equals(AuthHttp.singleHeader(r,"Idempotency-Key"))||!version.matches("[1-9][0-9]{0,18}")||!Set.of("0","1").contains(b.get("status").toString()))throw bad();
        try{return service.setEmployeeStatus(auth(r),AuthHttp.singleHeader(r,"X-CSRF-TOKEN"),Long.parseLong(userId),Long.parseLong(version),b.get("status").asInt(),b.get("reason").asText(),operation,AuthHttp.trace(r),AuthHttp.deadline(r));}catch(NumberFormatException e){throw bad();}
    }
    @GetMapping("/api/identity/users/{userId}/status-operations/{operation}")
    public Map<String,Object> statusOperation(@PathVariable String userId,@PathVariable String operation,HttpServletRequest r){
        if(!r.getParameterMap().isEmpty()||!userId.matches("[1-9][0-9]{0,18}"))throw bad();try{return service.employeeStatusOperation(auth(r),Long.parseLong(userId),operation,AuthHttp.deadline(r));}catch(NumberFormatException e){throw bad();}
    }
    @PostMapping("/api/identity/employees")
    public Map<String,Object> createEmployee(@RequestBody JsonNode b,HttpServletRequest r){
        if(!r.getParameterMap().isEmpty()||!b.isObject()||b.size()!=4)throw bad();for(String key:List.of("operationId","account","displayName","initialPassword"))if(!b.has(key)||!b.get(key).isTextual())throw bad();String operation=b.get("operationId").asText();if(!operation.equals(AuthHttp.singleHeader(r,"Idempotency-Key")))throw bad();
        return service.createEmployee(auth(r),AuthHttp.singleHeader(r,"X-CSRF-TOKEN"),b.get("account").asText(),b.get("displayName").asText(),b.get("initialPassword").asText(),operation,AuthHttp.trace(r),AuthHttp.deadline(r));
    }
    @GetMapping("/api/identity/employee-create-operations/{operation}")
    public Map<String,Object> employeeCreateOperation(@PathVariable String operation,HttpServletRequest r){if(!r.getParameterMap().isEmpty())throw bad();return service.employeeCreateOperation(auth(r),operation,AuthHttp.deadline(r));}
    @PostMapping("/api/identity/users/{userId}/scope")
    public Map<String,Object> employeeScope(@PathVariable String userId,@RequestBody JsonNode body,HttpServletRequest r){
        if(!r.getParameterMap().isEmpty()||!userId.matches("[1-9][0-9]{0,18}"))throw bad();var command=EmployeeScopeCommand.parse(body);if(!command.operationId().equals(AuthHttp.singleHeader(r,"Idempotency-Key")))throw bad();try{return service.setEmployeeScope(auth(r),AuthHttp.singleHeader(r,"X-CSRF-TOKEN"),Long.parseLong(userId),command,AuthHttp.trace(r),AuthHttp.deadline(r),com.ias.trust.Chain.deadline(r));}catch(NumberFormatException e){throw bad();}
    }
    @GetMapping("/api/identity/users/{userId}/scope-operations/{operation}")
    public Map<String,Object> employeeScopeOperation(@PathVariable String userId,@PathVariable String operation,HttpServletRequest r){if(!r.getParameterMap().isEmpty()||!userId.matches("[1-9][0-9]{0,18}"))throw bad();try{return service.employeeScopeOperation(auth(r),Long.parseLong(userId),operation,AuthHttp.deadline(r));}catch(NumberFormatException e){throw bad();}}

    private static int number(String raw, int fallback) {
        try {
            if (raw == null) return fallback;
            if (!raw.matches("[1-9][0-9]{0,6}")) throw bad();
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw bad();
        }
    }

    private static AuthFailure bad() {
        return new AuthFailure(400, "VALIDATION_ERROR", null);
    }
}
