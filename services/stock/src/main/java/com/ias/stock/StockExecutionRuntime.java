package com.ias.stock;

import com.ias.trust.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.time.Clock;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static com.ias.stock.StockExecutions.*;

@Configuration
@EnableScheduling
public class StockExecutionRuntime {
 @Bean TrustedStockExecutions stockExecutionAccess(StockAccess a,JdbcTemplate db){return new TrustedStockExecutions(a,db);}
 @Bean StockExecutions stockExecutions(DataSource source,TrustedStockExecutions a){return new StockExecutions(source,a,Clock.systemUTC());}
 @Bean Coordinator stockExecutionCoordinator(StockExecutions ledger,JdbcTemplate db){return new Coordinator(ledger,db);}
 public static final class Coordinator {
  private final StockExecutions ledger;private final JdbcTemplate db;private final java.util.concurrent.atomic.AtomicBoolean running=new java.util.concurrent.atomic.AtomicBoolean();
  Coordinator(StockExecutions ledger,JdbcTemplate db){this.ledger=ledger;this.db=db;}
  public void complete(long tenant,UUID operation){
   var rows=db.queryForList("SELECT o.request_hash,o.result_json,d.result_digest FROM stock_execution_operation o LEFT JOIN stock_execution_delivery d ON d.tenant_id=o.tenant_id AND d.operation_id=o.operation_id WHERE o.tenant_id=? AND o.operation_id=?",tenant,operation.toString());
   if(rows.isEmpty())throw new Problem(404,"OPERATION_NOT_FOUND");var row=rows.getFirst();String resultDigest=hash(row.get("result_json").toString());
   if(row.get("result_digest")!=null){if(!resultDigest.equals(row.get("result_digest")))throw new Problem(503,"COORDINATION_FACT_MISMATCH");return;}
   var body=Map.<String,Object>of("tenantId",Long.toString(tenant),"operationId",operation.toString(),"commandDigest",row.get("request_hash"),"resultDigest",resultDigest);
   var ack=Chain.call("stock-ticket","/internal/ticket/stock-executions/complete",body,UUID.randomUUID().toString(),System.currentTimeMillis()+5000);
   if(!operation.toString().equals(ack.get("operationId"))||!body.get("commandDigest").equals(ack.get("commandDigest"))||!resultDigest.equals(ack.get("resultDigest"))||!"COMPLETED".equals(ack.get("state")))throw new Problem(503,"COORDINATION_ACK_UNCONFIRMED");
   try{db.update("INSERT INTO stock_execution_delivery VALUES(?,?,?,CURRENT_TIMESTAMP(3))",tenant,operation.toString(),resultDigest);}catch(org.springframework.dao.DuplicateKeyException ignored){}
  }
  @Scheduled(initialDelay=1000,fixedDelay=1000) public void tick(){if(!running.compareAndSet(false,true))return;try{ledger.expireDue(50);var rows=db.queryForList("SELECT o.tenant_id,o.operation_id FROM stock_execution_operation o LEFT JOIN stock_execution_delivery d ON d.tenant_id=o.tenant_id AND d.operation_id=o.operation_id WHERE o.action_name<>'EXPIRE' AND d.operation_id IS NULL ORDER BY o.recorded_at,o.operation_id LIMIT 5");for(var row:rows){try{Chain.begin(System.currentTimeMillis()+5000);complete(((Number)row.get("tenant_id")).longValue(),UUID.fromString(row.get("operation_id").toString()));}catch(RuntimeException failure){/* Conservative OPEN permit retained; next bounded tick retries original fact. */}finally{Chain.end();}}}catch(Exception failure){/* No parameter/secret logging and no false coordination ACK. */}finally{running.set(false);}}
  private static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 }
}
