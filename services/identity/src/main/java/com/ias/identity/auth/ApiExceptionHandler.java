package com.ias.identity.auth;

import jakarta.servlet.http.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(com.ias.trust.Problem.class) public ResponseEntity<?> chain(com.ias.trust.Problem e,HttpServletRequest request){return ResponseEntity.status(e.status).body(java.util.Map.of("code",e.code,"traceId",AuthHttp.trace(request)));}
    @ExceptionHandler(AuthFailure.class)
    public ResponseEntity<?> auth(AuthFailure failure,HttpServletRequest request,HttpServletResponse response) {
        if (failure.status==503 && (request.getRequestURI().startsWith("/api/identity/users/") || request.getRequestURI().equals("/api/auth/password")))
            failure=new AuthFailure(503,"OPERATION_RESULT_UNKNOWN",null);
        AuthHttp.invalidCookies(failure,response);
        return ResponseEntity.status(failure.status).body(AuthHttp.error(failure,AuthHttp.trace(request)));
    }
    @ExceptionHandler({DataAccessException.class,org.springframework.transaction.TransactionException.class})
    public ResponseEntity<?> storage(RuntimeException failure,HttpServletRequest request) {
        // Never serialize SQL, credentials, connection details or uncertain COMMIT results.
        boolean revoke=request.getRequestURI().startsWith("/api/identity/users/") || request.getRequestURI().equals("/api/auth/password");
        return ResponseEntity.status(503).body(AuthHttp.error(new AuthFailure(503,revoke ? "OPERATION_RESULT_UNKNOWN":"IDENTITY_UNAVAILABLE",revoke ? null:"VERIFY_ATTEMPT"),AuthHttp.trace(request)));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> malformed(HttpServletRequest request) {
        return ResponseEntity.badRequest().body(AuthHttp.error(new AuthFailure(400,"VALIDATION_ERROR","REUSE_PREAUTH"),AuthHttp.trace(request)));
    }
}
