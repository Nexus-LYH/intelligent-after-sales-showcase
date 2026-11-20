package com.ias.asset;

import com.ias.trust.Problem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

/** Controller-local asset handlers do not cover these independently mapped endpoints. */
@RestControllerAdvice(assignableTypes={MembershipRoleController.class,FinanceMembershipController.class,CompanyReferences.class})
public final class MembershipRoleErrors {
 @ExceptionHandler(Problem.class)
 public ResponseEntity<?> known(Problem failure){return response(failure.status,failure.code);}
 @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.HttpMediaTypeNotSupportedException.class})
 public ResponseEntity<?> malformed(Exception failure){return response(400,"VALIDATION_ERROR");}
 @ExceptionHandler(Exception.class)
 public ResponseEntity<?> unknown(Exception failure,HttpServletRequest request){return response(503,"GET".equals(request.getMethod())?"DEPENDENCY_UNAVAILABLE":"OPERATION_RESULT_UNKNOWN");}
 private static ResponseEntity<?> response(int status,String code){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("code",code,"retryable",status==503));}
}
