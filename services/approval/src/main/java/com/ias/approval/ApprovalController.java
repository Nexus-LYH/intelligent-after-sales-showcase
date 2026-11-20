package com.ias.approval;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/approval/parts")
public final class ApprovalController {
    private final ApprovalService service;
    private final ApprovalQueries queries;
    public ApprovalController(ApprovalService service) { this(service,null); }
    @org.springframework.beans.factory.annotation.Autowired
    public ApprovalController(ApprovalService service,ApprovalQueries queries) { this.service=service;this.queries=queries; }
    @GetMapping public ApprovalQueries.Page list(HttpServletRequest request){
        if(!java.util.Set.of("ticketId","page","size").containsAll(request.getParameterMap().keySet()))throw new ApprovalFailure(400,"INVALID_QUERY");
        for(var value:request.getParameterMap().values())if(value.length!=1)throw new ApprovalFailure(400,"INVALID_QUERY");
        String ticket=request.getParameter("ticketId");try{if(!UUID.fromString(ticket).toString().equals(ticket))throw new IllegalArgumentException();}catch(Exception e){throw new ApprovalFailure(400,"INVALID_QUERY");}
        int page=number(request.getParameter("page"),1),size=number(request.getParameter("size"),20);
        if(page<1||page>100000||!java.util.Set.of(20,50,100).contains(size))throw new ApprovalFailure(400,"INVALID_QUERY");
        var principal=service.ticketRead(request,ticket);
        if(queries==null)throw new ApprovalFailure(503,"QUERY_NOT_CONFIGURED");
        return queries.list(principal.tenantId(),ticket,page,size);
    }
    private static int number(String value,int fallback){try{if(value==null)return fallback;if(!value.matches("[1-9][0-9]{0,5}"))throw new Exception();return Integer.parseInt(value);}catch(Exception e){throw new ApprovalFailure(400,"INVALID_QUERY");}}
    @PostMapping public ApprovalView submit(HttpServletRequest request, @RequestBody ApprovalService.SubmitBody body) {
        idempotency(request, body.operationId());
        return ApprovalView.of(service.submit(request, body));
    }
    @GetMapping("/{id}") public Map<String,Object> get(HttpServletRequest request, @PathVariable UUID id) {
        var view=service.currentView(request, id);
        @SuppressWarnings("unchecked") Map<String,Object> result=com.ias.trust.Chain.JSON.convertValue(view,Map.class);
        result=new java.util.LinkedHashMap<>(result);
        // Reviewers see the frozen cost bearer; historical write receipts remain unchanged.
        result.put("chargeMode",queries==null?"UNKNOWN":queries.executionChargeMode(view.submission().tenantId(),id));
        return result;
    }
    @GetMapping("/operations/{id}") public ApprovalView operation(HttpServletRequest request, @PathVariable UUID id) {
        return ApprovalView.of(service.operation(request, id));
    }
    @PostMapping("/{id}/decisions") public ApprovalView decide(HttpServletRequest request,
            @PathVariable UUID id, @RequestBody ApprovalService.DecisionBody body) {
        idempotency(request, body.operationId()); return ApprovalView.of(service.decide(request, id, body));
    }
    @PostMapping("/{id}/withdrawals") public ApprovalView withdraw(HttpServletRequest request,
            @PathVariable UUID id, @RequestBody ApprovalService.WithdrawBody body) {
        idempotency(request, body.operationId()); return ApprovalView.of(service.withdraw(request, id, body));
    }
    private void idempotency(HttpServletRequest request, UUID bodyId) {
        var headers = java.util.Collections.list(request.getHeaders("Idempotency-Key"));
        if (bodyId == null || headers.size() != 1 || !bodyId.toString().equals(headers.getFirst()))
            throw new ApprovalFailure(400, "INVALID_IDEMPOTENCY_KEY");
    }

    @ExceptionHandler(ApprovalFailure.class) ResponseEntity<Map<String, Object>> failure(ApprovalFailure failure) {
        return response(failure.status(), failure.getMessage());
    }
    @ExceptionHandler(PartsApproval.Rejected.class) ResponseEntity<Map<String, Object>> rejected(PartsApproval.Rejected failure) {
        String code = failure.getMessage();
        int status = code.equals("NOT_AUTHORIZED") ? 403
                : code.startsWith("INVALID_") || code.equals("REASON_REQUIRED") || code.equals("REQUIRED_FIELD") ? 400 : 409;
        return response(status, code);
    }
    @ExceptionHandler({NullPointerException.class, HttpMessageNotReadableException.class})
    ResponseEntity<Map<String, Object>> invalid(Exception ignored) { return response(400, "INVALID_REQUEST"); }
    private ResponseEntity<Map<String, Object>> response(int status, String code) {
        return ResponseEntity.status(status).header("Cache-Control", "no-store").body(
                Map.of("code", code, "retryable", status == 503, "traceId", UUID.randomUUID().toString()));
    }
}
