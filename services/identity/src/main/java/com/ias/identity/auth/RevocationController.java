package com.ias.identity.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import tools.jackson.databind.JsonNode;

@RestController
public class RevocationController {
    private final AuthService service;
    public RevocationController(AuthService service) { this.service=service; }
    @PostMapping("/api/identity/users/{userId}/sessions/revoke")
    public Map<String,Object> revoke(@PathVariable String userId,@RequestBody JsonNode body,HttpServletRequest request) {
        if (!userId.matches("[1-9][0-9]{0,18}") || !body.isObject() || body.size()!=2 || !body.has("reason")
                || !body.get("reason").isTextual() || !body.has("expectedSecurityVersion")) throw bad();
        JsonNode version=body.get("expectedSecurityVersion");
        // Decimal string is lossless for browsers; integral JSON is accepted when exactly representable as long.
        String raw=version.isTextual() ? version.asText():version.isIntegralNumber() ? version.toString():"";
        if (!raw.matches("[1-9][0-9]{0,18}")) throw bad();
        long target,expected;
        try { target=Long.parseLong(userId); expected=Long.parseLong(raw); }
        catch (NumberFormatException e) { throw bad(); }
        AuthHttp.cookie(request,AuthService.PREAUTH_COOKIE);
        return service.revokeUser(AuthHttp.cookie(request,AuthService.AUTH_COOKIE),AuthHttp.singleHeader(request,"X-CSRF-TOKEN"),
            target,expected,body.get("reason").asText(),AuthHttp.singleHeader(request,"Idempotency-Key"),AuthHttp.trace(request),AuthHttp.deadline(request));
    }
    private static AuthFailure bad() { return new AuthFailure(400,"VALIDATION_ERROR",null); }
}
