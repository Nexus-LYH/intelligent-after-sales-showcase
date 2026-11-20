package com.ias.ticket;
import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.*;
@RestController
public final class FinanceFreezeProofController {
 final FinanceFreezeCoordinator coordinator;
 public FinanceFreezeProofController(FinanceFreezeCoordinator coordinator){this.coordinator=coordinator;}
 @PostMapping("/internal/ticket/finance-quotes/proof") Map<String,Object> proof(@RequestBody Map<String,Object> b,HttpServletRequest r){ApprovalBoundaryController.trusted(r,Set.of("approval-ticket","stock-ticket"));return coordinator.proof(b,FinanceFreezeCoordinator.context(r));}
 @ExceptionHandler(Problem.class) ResponseEntity<Map<String,Object>> problem(Problem p){return ResponseEntity.status(p.status).header("Cache-Control","no-store").body(Map.of("code",p.code,"retryable",p.status==503));}
}
