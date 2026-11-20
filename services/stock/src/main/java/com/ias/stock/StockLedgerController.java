package com.ias.stock;

import com.ias.trust.Problem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
public final class StockLedgerController {
    private final StockLedgerQueries queries;
    private final StockAccess access;

    public StockLedgerController(StockLedgerQueries queries, StockAccess access) {
        this.queries = queries;
        this.access = access;
    }

    @GetMapping("/api/stock/warehouses/{warehouseId}/movements")
    public Map<String, Object> movements(@PathVariable String warehouseId, HttpServletRequest request) {
        StockController.query(request, Set.of("partId", "orderId", "page", "size"));
        long warehouse = StockController.id(warehouseId);
        Long part = request.getParameter("partId") == null ? null : StockController.id(request.getParameter("partId"));
        String order = request.getParameter("orderId") == null ? null : StockController.uuid(request.getParameter("orderId"));
        int[] paging = StockController.paging(request);
        var actor = access.balance(request, warehouse);
        return queries.movements(actor, warehouse, part, order, paging[0], paging[1]);
    }

    @ExceptionHandler(Problem.class)
    ResponseEntity<Map<String, Object>> problem(Problem failure) {
        return ResponseEntity.status(failure.status).header("Cache-Control", "no-store").body(
                Map.of("code", failure.code, "traceId", UUID.randomUUID().toString(), "retryable", failure.status == 503));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, Object>> unavailable() {
        return problem(new Problem(503, "STOCK_LEDGER_UNAVAILABLE"));
    }
}
