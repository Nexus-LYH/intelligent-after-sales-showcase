package com.ias.asset;
import com.ias.trust.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;
@RestControllerAdvice(assignableTypes=RepairBindings.class)
public class RepairErrors {
 @ExceptionHandler(Problem.class) ResponseEntity<?> known(Problem p){return ResponseEntity.status(p.status).body(Map.of("code",p.code));}
 @ExceptionHandler(Exception.class) ResponseEntity<?> unknown(Exception p){return ResponseEntity.status(503).body(Map.of("code","OPERATION_RESULT_UNKNOWN"));}
}

