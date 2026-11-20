package com.ias.stock;

import com.ias.trust.Problem;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.*;

/** Real warehouse-authorized inbound and private evidence; no client tenant/actor authority. */
@RestController
public final class StockInboundController {
    private final JdbcTemplate db;private final DataSource source;private final StockAccess access;private final InboundLedger writer;
    private final java.util.concurrent.Semaphore uploads=new java.util.concurrent.Semaphore(1);
    public StockInboundController(JdbcTemplate db,DataSource source,StockAccess access){this.db=db;this.source=source;this.access=access;
        writer=new InboundLedger(source,(actor,c)->{
            var r=((org.springframework.web.context.request.ServletRequestAttributes)org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()).getRequest();
            var a=access.manage(r,c.warehouseId());if(a.tenantId()!=actor.tenantId()||a.userId()!=actor.userId())throw new Problem(401,"SESSION_INVALID");evidence(a,c.warehouseId(),c.evidenceFileId(),true);
        });
    }
    private StockAccess.Principal read(HttpServletRequest r,long warehouse){var a=access.balance(r,warehouse);if(!a.warehouseIds().contains(warehouse))throw new Problem(404,"NOT_FOUND");return a;}
    private void active(StockAccess.Principal a,long warehouse){if(db.queryForObject("SELECT COUNT(*) FROM stock_warehouse WHERE tenant_id=? AND id=? AND active=TRUE",Long.class,a.tenantId(),warehouse)!=1)throw new Problem(404,"NOT_FOUND");}
    private static UUID key(HttpServletRequest r,String path){String key=StockController.uuid(r.getHeader("Idempotency-Key"));if(path!=null&&!key.equals(path))throw StockController.bad();return UUID.fromString(key);}
    @PostMapping(value="/api/stock/warehouses/{warehouseId}/inbound-files/{fileId}",consumes="application/octet-stream")
    Map<String,Object> upload(@PathVariable String warehouseId,@PathVariable String fileId,HttpServletRequest r)throws IOException{
        if(!uploads.tryAcquire())throw new Problem(429,"RATE_LIMITED");
        try{return uploadBody(warehouseId,fileId,r);}finally{uploads.release();}
    }
    private Map<String,Object> uploadBody(String warehouseId,String fileId,HttpServletRequest r)throws IOException{
        StockController.query(r,Set.of());long warehouse=StockController.id(warehouseId);UUID id=key(r,StockController.uuid(fileId));
        var a=access.manage(r,warehouse);active(a,warehouse);
        if(r.getContentLengthLong()>20971520)throw new Problem(413,"FILE_TOO_LARGE");
        byte[] bytes=r.getInputStream().readNBytes(20971521);if(bytes.length>20971520)throw new Problem(413,"FILE_TOO_LARGE");
        String mime=mime(bytes),hash=hash(bytes);
        // Primary key serializes racing uploads. A conflicting upload is never overwritten.
        try{db.update("INSERT INTO stock_evidence VALUES(?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP(3))",a.tenantId(),id.toString(),a.userId(),warehouse,hash,mime,bytes.length,bytes);}
        catch(org.springframework.dao.DuplicateKeyException ignored){}
        var fact=evidence(a,warehouse,id,true);
        if(!hash.equals(fact.get("contentHash")))throw new Problem(409,"IDEMPOTENCY_CONFLICT");return fact;
    }
    @GetMapping("/api/stock/warehouses/{warehouseId}/inbound-files/{fileId}")
    Map<String,Object> file(@PathVariable String warehouseId,@PathVariable String fileId,HttpServletRequest r){StockController.query(r,Set.of());long w=StockController.id(warehouseId);return evidence(read(r,w),w,UUID.fromString(StockController.uuid(fileId)),false);}
    private Map<String,Object> evidence(StockAccess.Principal a,long warehouse,UUID id,boolean own){
        var rows=db.queryForList("SELECT actor_id,warehouse_id,content_hash,content_type,byte_size FROM stock_evidence WHERE tenant_id=? AND file_id=?",a.tenantId(),id.toString());
        if(rows.isEmpty())throw new Problem(404,"NOT_FOUND");var row=rows.getFirst();
        if(((Number)row.get("warehouse_id")).longValue()!=warehouse||(own&&((Number)row.get("actor_id")).longValue()!=a.userId()))throw new Problem(404,"NOT_FOUND");
        return Map.of("fileId",id.toString(),"warehouseId",Long.toString(warehouse),"contentHash",row.get("content_hash"),"contentType",row.get("content_type"),"byteSize",row.get("byte_size").toString(),"state","READY");
    }
    @GetMapping("/api/stock/warehouses/{warehouseId}/inbound-files/{fileId}/content")
    ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> content(@PathVariable String warehouseId,@PathVariable String fileId,HttpServletRequest r){
        StockController.query(r,Set.of());long w=StockController.id(warehouseId);String id=StockController.uuid(fileId);var a=read(r,w);var fact=evidence(a,w,UUID.fromString(id),false);
        long size=Long.parseLong(fact.get("byteSize").toString()),start=0,end=size-1;String range=r.getHeader("Range");boolean partial=range!=null;
        if(partial){if(Collections.list(r.getHeaders("Range")).size()!=1||!range.matches("bytes=[0-9]+-[0-9]*"))throw new Problem(416,"RANGE_NOT_SATISFIABLE");try{String[] p=range.substring(6).split("-",-1);long from=Long.parseLong(p[0]),to=p[1].isEmpty()?end:Long.parseLong(p[1]);if(from>to||from>=size)throw new Exception();start=from;end=Math.min(to,end);}catch(Exception ex){throw new Problem(416,"RANGE_NOT_SATISFIABLE");}}
        var out=ResponseEntity.status(partial?206:200).header("Content-Type",fact.get("contentType").toString()).header("Content-Disposition","attachment; filename=\""+id+"\"").header("X-Content-Type-Options","nosniff").header("Accept-Ranges","bytes");
        if(partial)out.header("Content-Range","bytes "+start+"-"+end+"/"+size);
        long offset=start,length=end-start+1;out.header("Content-Length",Long.toString(length));boolean head=r.getMethod().equals("HEAD");
        return out.body(output->{if(head)return;long deadline=System.nanoTime()+120_000_000_000L;
            try(var connection=source.getConnection();var q=connection.prepareStatement("SELECT SUBSTRING(content_bytes,?,?) FROM stock_evidence WHERE tenant_id=? AND file_id=?")){
                q.setLong(1,offset+1);q.setLong(2,length);q.setLong(3,a.tenantId());q.setString(4,id);
                try(var rs=q.executeQuery()){if(!rs.next())throw new IOException("Private evidence unavailable");try(var input=rs.getBinaryStream(1)){byte[] buffer=new byte[32768];long remaining=length;while(remaining>0){if(System.nanoTime()>deadline)throw new IOException("Transfer deadline");int n=input.read(buffer,0,(int)Math.min(remaining,buffer.length));if(n<0)throw new IOException("Private evidence truncated");output.write(buffer,0,n);remaining-=n;}}}
            }catch(SQLException e){throw new IOException("Private evidence unavailable");}
        });
    }
    private InboundLedger ledger(HttpServletRequest r,StockAccess.Principal a,boolean write){if(write)return writer;return new InboundLedger(source,(actor,c)->{
        var current=write?access.manage(r,c.warehouseId()):read(r,c.warehouseId());
        if(current.tenantId()!=actor.tenantId()||current.userId()!=actor.userId())throw new Problem(401,"SESSION_INVALID");
        evidence(current,c.warehouseId(),c.evidenceFileId(),write);
    });}
    @PostMapping("/api/stock/warehouses/{warehouseId}/inbounds")
    Map<String,Object> inbound(@PathVariable String warehouseId,@RequestBody Map<String,Object> body,HttpServletRequest r)throws SQLException{
        StockController.query(r,Set.of());if(!body.keySet().equals(Set.of("partId","quantity","source","batchNote","evidenceFileId")))throw StockController.bad();
        long w=StockController.id(warehouseId);var a=access.manage(r,w);UUID op=key(r,null);
        if(!(body.get("source")instanceof String s)||!(body.get("batchNote")instanceof String note))throw StockController.bad();
        InboundLedger.Command c;try{c=new InboundLedger.Command(op,w,StockController.id(body.get("partId")),StockController.id(body.get("quantity")),s,note,UUID.fromString(StockController.uuid(body.get("evidenceFileId"))));}catch(IllegalArgumentException ex){throw StockController.bad();}
        try{return receipt(ledger(r,a,true).receive(new InboundLedger.Actor(a.tenantId(),a.userId()),c));}
        catch(IllegalStateException ex){throw new Problem(409,"IDEMPOTENCY_CONFLICT");}
        catch(SecurityException ex){throw new Problem(404,"NOT_FOUND");}
        catch(ArithmeticException ex){throw new Problem(409,"STOCK_QUANTITY_OVERFLOW");}
    }
    @GetMapping("/api/stock/warehouses/{warehouseId}/inbound-operations/{operationId}")
    Map<String,Object> operation(@PathVariable String warehouseId,@PathVariable String operationId,HttpServletRequest r)throws SQLException{
        StockController.query(r,Set.of());long w=StockController.id(warehouseId);var a=read(r,w);
        if(db.queryForObject("SELECT COUNT(*) FROM stock_operation WHERE tenant_id=? AND actor_id=? AND warehouse_id=? AND operation_id=?",Long.class,a.tenantId(),a.userId(),w,StockController.uuid(operationId))!=1)throw new Problem(404,"NOT_FOUND");
        return receipt(ledger(r,a,false).find(new InboundLedger.Actor(a.tenantId(),a.userId()),UUID.fromString(StockController.uuid(operationId))).orElseThrow(()->new Problem(404,"NOT_FOUND")));
    }
    static Map<String,Object> receipt(InboundLedger.Receipt r){return Map.of("operationId",r.operationId().toString(),"onHand",Long.toString(r.onHand()),"recordedAt",r.recordedAt().toString());}
    private static String hash(byte[] b){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));}catch(Exception e){throw new IllegalStateException(e);}}
    static String mime(byte[] b){if(b.length>=8&&Arrays.equals(Arrays.copyOf(b,8),new byte[]{(byte)137,80,78,71,13,10,26,10}))return "image/png";if(b.length>=3&&(b[0]&255)==255&&(b[1]&255)==216&&(b[2]&255)==255)return "image/jpeg";if(b.length>=5&&new String(b,0,5,StandardCharsets.US_ASCII).equals("%PDF-"))return "application/pdf";throw new Problem(400,"UNSUPPORTED_FILE_TYPE");}
    @ExceptionHandler(Problem.class)ResponseEntity<Map<String,Object>> problem(Problem p){return ResponseEntity.status(p.status).header("Cache-Control","no-store").body(Map.of("code",p.code,"retryable",p.status==503));}
    @ExceptionHandler({SQLException.class,org.springframework.dao.DataAccessException.class})ResponseEntity<Map<String,Object>> unavailable(Exception e){return problem(new Problem(503,"OPERATION_RESULT_UNKNOWN"));}
}
