package com.ias.finance;
import com.ias.trust.Problem;
import jakarta.servlet.http.*;
import org.springframework.web.bind.annotation.*;
import java.sql.SQLException;
import java.util.*;
@RestControllerAdvice
public final class FinanceErrors {
 @ExceptionHandler(Exception.class) public Map<String,Object> error(Exception e,HttpServletResponse r){int status;String code;
  if(e instanceof Problem p){status=p.status;code=p.code;}
  else if(e instanceof SQLException){status=503;code="OPERATION_RESULT_UNKNOWN";}
  else if(e instanceof SecurityException){status=e.getMessage()!=null&&Set.of("TARGET_NOT_ACCESSIBLE","OPERATION_NOT_OBSERVED").contains(e.getMessage())?404:403;code=status==404?e.getMessage():"ACCESS_DENIED";}
  else if(e instanceof IllegalStateException){status=409;code=e.getMessage()!=null&&e.getMessage().matches("[A-Z_]{1,80}")?e.getMessage():"FINANCE_STATE_CONFLICT";}
  else{status=400;code="INVALID_REQUEST";}
  r.setStatus(status);r.setHeader("Cache-Control","no-store");return Map.of("code",code,"traceId",UUID.randomUUID().toString(),"retryable",status==503);
 }
}
