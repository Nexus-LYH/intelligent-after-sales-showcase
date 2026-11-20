package com.ias.approval;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import com.ias.trust.Problem;
import java.util.*;

@RestController
@RequestMapping("/api/approval/fees")
public final class FeeApprovalController {
    private final FeeApprovalService service;
    public FeeApprovalController(FeeApprovalService service){this.service=service;}
    @GetMapping("/quote-context")public Map<String,Object> quoteContext(HttpServletRequest r){if(!r.getParameterMap().keySet().equals(Set.of("ticketId","groupId","quoteId")))throw new ApprovalFailure(400,"INVALID_QUERY");for(var values:r.getParameterMap().values())if(values.length!=1)throw new ApprovalFailure(400,"INVALID_QUERY");return service.quoteContext(r,FeeApprovalService.id(r.getParameter("ticketId")),FeeApprovalService.id(r.getParameter("groupId")),FeeApprovalService.id(r.getParameter("quoteId")));}
    @GetMapping public Map<String,Object> list(HttpServletRequest r){if(!Set.of("ticketId","page","size").containsAll(r.getParameterMap().keySet()))throw new ApprovalFailure(400,"INVALID_QUERY");for(var values:r.getParameterMap().values())if(values.length!=1)throw new ApprovalFailure(400,"INVALID_QUERY");int page=number(r.getParameter("page"),1),size=number(r.getParameter("size"),20);if(page>100000||!Set.of(20,50,100).contains(size))throw new ApprovalFailure(400,"INVALID_QUERY");return service.list(r,FeeApprovalService.id(r.getParameter("ticketId")),page,size);}
    @PostMapping public Map<String,Object> submit(HttpServletRequest r,@RequestBody FeeApprovalService.SubmitBody body){key(r,body.operationId());return service.submit(r,body);}
    @GetMapping("/{id}") public Map<String,Object> detail(HttpServletRequest r,@PathVariable String id){return service.detail(r,FeeApprovalService.id(id));}
    @GetMapping("/operations/{id}") public Map<String,Object> operation(HttpServletRequest r,@PathVariable String id){return service.operation(r,FeeApprovalService.id(id));}
    @PostMapping("/{id}/refresh") public Map<String,Object> refresh(HttpServletRequest r,@PathVariable String id,@RequestBody FeeApprovalService.RefreshBody body){key(r,body.operationId());return service.refresh(r,FeeApprovalService.id(id),body);}
    @PostMapping("/{id}/decisions") public Map<String,Object> decide(HttpServletRequest r,@PathVariable String id,@RequestBody FeeApprovalService.DecisionBody body){key(r,body.operationId());return service.decide(r,FeeApprovalService.id(id),body);}
    @PostMapping("/{id}/withdrawals") public Map<String,Object> withdraw(HttpServletRequest r,@PathVariable String id,@RequestBody FeeApprovalService.WithdrawBody body){key(r,body.operationId());return service.withdraw(r,FeeApprovalService.id(id),body);}
    private static void key(HttpServletRequest r,String body){var values=Collections.list(r.getHeaders("Idempotency-Key"));if(values.size()!=1||!FeeApprovalService.id(body).toString().equals(values.getFirst()))throw new ApprovalFailure(400,"INVALID_IDEMPOTENCY_KEY");}
    private static int number(String s,int fallback){if(s==null)return fallback;try{int n=Integer.parseInt(s);if(n<1||!Integer.toString(n).equals(s))throw new Exception();return n;}catch(Exception e){throw new ApprovalFailure(400,"INVALID_QUERY");}}
    @ExceptionHandler(ApprovalFailure.class)ResponseEntity<Map<String,Object>> failure(ApprovalFailure e){return response(e.status(),e.getMessage());}
    @ExceptionHandler(Problem.class)ResponseEntity<Map<String,Object>> failure(Problem e){return response(e.status,e.code);}
    @ExceptionHandler({HttpMessageNotReadableException.class,NullPointerException.class})ResponseEntity<Map<String,Object>> invalid(Exception e){return response(400,"INVALID_REQUEST");}
    private ResponseEntity<Map<String,Object>> response(int status,String code){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("code",code,"retryable",status==503));}
}
