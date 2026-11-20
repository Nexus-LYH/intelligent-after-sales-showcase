package com.ias.ticket;
import com.ias.trust.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
@RestControllerAdvice
public class Errors {
 private static final Logger LOG=LoggerFactory.getLogger(Errors.class);
 @ExceptionHandler(Problem.class) ResponseEntity<?> known(Problem e){return ResponseEntity.status(e.status).body(Map.of("code",e.code,"traceId",UUID.randomUUID().toString()));}
 @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) ResponseEntity<?> malformed(Exception e){return ResponseEntity.badRequest().body(Map.of("code","VALIDATION_ERROR"));}
 @ExceptionHandler(Exception.class) ResponseEntity<?> unknown(Exception e,HttpServletRequest request){
  String trace=Chain.trace(request),sqlState="NONE";int vendorCode=0;Throwable root=e;
  for(int i=0;i<16&&root!=null;i++){if(root instanceof SQLException sql){String state=sql.getSQLState();sqlState=state!=null&&state.matches("[A-Z0-9]{5}")?state:"UNAVAILABLE";vendorCode=sql.getErrorCode();break;}root=root.getCause();}
  // Never log exception messages, SQL, request bodies, headers or a stack containing bound values.
  LOG.warn("ticket.failure trace={} exceptionType={} sqlState={} vendorCode={}",trace,e.getClass().getSimpleName(),sqlState,vendorCode);
  return ResponseEntity.status(503).body(Map.of("code","OPERATION_RESULT_UNKNOWN","traceId",trace));
 }
}
