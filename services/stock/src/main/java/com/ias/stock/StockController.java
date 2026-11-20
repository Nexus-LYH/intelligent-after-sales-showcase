package com.ias.stock;

import com.ias.trust.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
public final class StockController {
    private final StockCatalog catalog;private final StockAccess access;
    public StockController(StockCatalog catalog,StockAccess access){this.catalog=catalog;this.access=access;}
    @GetMapping("/api/stock/warehouse-catalog") Map<String,Object> warehouseCatalogue(HttpServletRequest r){query(r,Set.of("page","size"));int[] p=paging(r);return catalog.warehouseCatalogue(access.balance(r,0),p[0],p[1]);}
    @GetMapping("/api/stock/warehouse-catalog/{warehouseId}/parts") Map<String,Object> warehouseParts(@PathVariable String warehouseId,HttpServletRequest r){query(r,Set.of("page","size"));long warehouse=id(warehouseId);int[] p=paging(r);return catalog.warehouseParts(access.balance(r,warehouse),warehouse,p[0],p[1]);}
    @GetMapping("/api/stock/warehouses") Map<String,Object> warehouses(HttpServletRequest r){
        query(r,Set.of("ticketId","assignmentId","page","size"));String ticket=uuid(r.getParameter("ticketId")),assignment=uuid(r.getParameter("assignmentId"));int[] p=paging(r);
        var actor=access.catalog(r,ticket,assignment);return catalog.warehouses(actor.tenantId(),p[0],p[1]);
    }
    @GetMapping("/api/stock/warehouses/{warehouseId}/parts") Map<String,Object> parts(@PathVariable String warehouseId,HttpServletRequest r){
        query(r,Set.of("ticketId","assignmentId","page","size"));String ticket=uuid(r.getParameter("ticketId")),assignment=uuid(r.getParameter("assignmentId"));long warehouse=id(warehouseId);int[]p=paging(r);
        var actor=access.catalog(r,ticket,assignment);return catalog.parts(actor.tenantId(),warehouse,p[0],p[1]);
    }
    @GetMapping("/api/stock/warehouses/{warehouseId}/balances") Map<String,Object> balances(@PathVariable String warehouseId,HttpServletRequest r){
        query(r,Set.of("partId","page","size"));long warehouse=id(warehouseId);Long part=r.getParameter("partId")==null?null:id(r.getParameter("partId"));int[]p=paging(r);
        var actor=access.balance(r,warehouse);return catalog.balances(actor,warehouse,part,p[0],p[1]);
    }
    @PostMapping("/internal/stock/approval-check") Map<String,Object> approval(@RequestBody Map<String,Object> body,HttpServletRequest r){
        if(!body.keySet().equals(Set.of("sessionToken","warehouseId","partId"))||!(body.get("sessionToken")instanceof String token)||token.isBlank())throw bad();
        long warehouse=id(body.get("warehouseId")),part=id(body.get("partId"));return catalog.approval(access.approval(r,token),warehouse,part);
    }
    static void query(HttpServletRequest r,Set<String> allowed){if(!allowed.containsAll(r.getParameterMap().keySet()))throw bad();for(String k:r.getParameterMap().keySet())if(r.getParameterValues(k).length!=1)throw bad();}
    static int[] paging(HttpServletRequest r){int page=r.getParameter("page")==null?1:small(r.getParameter("page")),size=r.getParameter("size")==null?20:small(r.getParameter("size"));if(page>100000||!Set.of(20,50,100).contains(size))throw bad();return new int[]{page,size};}
    private static int small(String s){try{return Math.toIntExact(id(s));}catch(Exception e){throw bad();}}
    static long id(Object value){if(!(value instanceof String s)||!s.matches("[1-9][0-9]{0,18}"))throw bad();try{return Long.parseLong(s);}catch(Exception e){throw bad();}}
    static String uuid(Object value){if(!(value instanceof String s))throw bad();try{if(!UUID.fromString(s).toString().equals(s))throw bad();return s;}catch(Exception e){throw bad();}}
    static Problem bad(){return new Problem(400,"VALIDATION_ERROR");}
    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class) ResponseEntity<Map<String,Object>> malformed(){return problem(bad());}
    @ExceptionHandler(Problem.class) ResponseEntity<Map<String,Object>> problem(Problem e){return ResponseEntity.status(e.status).header("Cache-Control","no-store").body(Map.of("code",e.code,"traceId",UUID.randomUUID().toString(),"retryable",e.status==503));}
}
