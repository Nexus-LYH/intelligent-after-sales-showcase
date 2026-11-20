package com.ias.stock;

import com.ias.trust.Problem;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Local owner fence and receipt. No production HTTP adapter or global authorization is supplied here. */
public final class StockFinanceGeneration {
 public record Binding(long tenantId,long companyId,UUID ticketId,UUID roundId,UUID groupId,long expectedGroupVersion,UUID quoteId,UUID sourceOperationId,String commandDigest,UUID freezeId,long generation){
  public Binding{if(tenantId<1||companyId<1||expectedGroupVersion<0||generation<1||!hex(commandDigest))throw new IllegalArgumentException();for(Object value:List.of(ticketId,roundId,groupId,quoteId,sourceOperationId,freezeId))Objects.requireNonNull(value);}
 }
 /** Must come from the verified durable W/source protocol, never a browser boolean or an empty UI list. */
 public record VerifiedFence(Binding binding,String sourceState,String ticketFenceDigest,List<UUID> scopedOperationIds,List<UUID> inFlightOperationIds,String resultDigest){
  public VerifiedFence(Binding binding,String sourceState,String ticketFenceDigest,List<UUID> scopedOperationIds,List<UUID> inFlightOperationIds){this(binding,sourceState,ticketFenceDigest,scopedOperationIds,inFlightOperationIds,null);}
  public VerifiedFence{Objects.requireNonNull(binding);if(!Set.of("IN_PROGRESS","COMMITTED","ABORTED").contains(sourceState)||!hex(ticketFenceDigest))throw new IllegalArgumentException();scopedOperationIds=unique(scopedOperationIds);inFlightOperationIds=unique(inFlightOperationIds);if(!scopedOperationIds.containsAll(inFlightOperationIds))throw new IllegalArgumentException();}
 }
 @FunctionalInterface public interface Verifier{VerifiedFence verify(Binding binding);}
 public record Receipt(String owner,String state,Binding binding,String bindingDigest,String coverageDigest,String ackDigest,List<Map<String,String>> localSnapshot,String recordedAt){}
 public record Outcome(String state,Receipt receipt,List<String> unresolved){public boolean globalFreezeAck(){return false;}}
 record Gate(long generation,boolean fenced){}
 private record Scan(List<Map<String,String>> rows,List<String> unresolved){}
 private final DataSource source;private final Verifier verifier;
 private final List<Connection> quarantine=Collections.synchronizedList(new ArrayList<>());
 private static final JsonMapper JSON=JsonMapper.builder().build();
 public StockFinanceGeneration(DataSource source){this(source,b->{throw new Problem(503,"FINANCE_SOURCE_PROTOCOL_NOT_CONFIGURED");});}
 public StockFinanceGeneration(DataSource source,Verifier verifier){this.source=source;this.verifier=Objects.requireNonNull(verifier);}
 static boolean hex(String s){return s!=null&&s.matches("[0-9a-f]{64}");}
 static List<UUID> unique(List<UUID> values){if(values==null||values.size()>1000||values.stream().anyMatch(Objects::isNull)||new HashSet<>(values).size()!=values.size())throw new IllegalArgumentException();return values.stream().sorted().toList();}
 static String hash(Object value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value)));}catch(Exception e){throw new IllegalStateException(e);}}
 public static String bindingDigest(Binding b){return hash(List.of("STOCK_FINANCE_FENCE_V1",Long.toString(b.tenantId()),Long.toString(b.companyId()),b.ticketId().toString(),b.roundId().toString(),b.groupId().toString(),Long.toString(b.expectedGroupVersion()),b.quoteId().toString(),b.sourceOperationId().toString(),b.commandDigest(),b.freezeId().toString(),Long.toString(b.generation())));}
 /** Lock order for all quantity mutations: generation -> warehouse -> sorted parts -> order/balance. */
 static Gate lock(Connection c,long tenant,String ticket)throws SQLException{
  update(c,"INSERT INTO stock_finance_generation(tenant_id,ticket_id,generation,active_freeze_id,row_version) VALUES(?,?,0,NULL,0) ON DUPLICATE KEY UPDATE row_version=row_version",tenant,ticket);
  try(var q=statement(c,"SELECT generation,active_freeze_id FROM stock_finance_generation WHERE tenant_id=? AND ticket_id=? FOR UPDATE",tenant,ticket);var r=q.executeQuery()){if(!r.next())throw new SQLException("GENERATION_LOCK_MISSING");return new Gate(r.getLong(1),r.getString(2)!=null);}
 }
 static void writable(Gate gate,StockExecutions.Action phase){if(gate.fenced()&&!Set.of(StockExecutions.Action.RETURN,StockExecutions.Action.EXPIRE).contains(phase))throw new Problem(409,"STOCK_FINANCE_GENERATION_FENCED");}
 static void record(Connection c,Gate gate,StockExecutions.Actor actor,StockExecutions.Command command,StockExecutions.Approval approval)throws SQLException{
  update(c,"INSERT INTO stock_finance_execution_fact VALUES(?,?,?,?,?,?,?,?,?,?)",actor.tenantId(),command.operationId().toString(),approval.ticketId(),gate.generation(),command.orderId().toString(),approval.version(),command.action().name(),approval.chargeMode(),hash(List.of(command.operationId(),command.orderId(),command.action(),command.expectedVersion(),command.quantities())),now());
 }
 private VerifiedFence proof(Binding b){var p=verifier.verify(b);if(p==null||!b.equals(p.binding()))throw new Problem(503,"FINANCE_FENCE_BINDING_MISMATCH");if("ABORTED".equals(p.sourceState()))throw new Problem(409,"FINANCE_SOURCE_ABORTED");return p;}
 public Outcome freeze(Binding b)throws SQLException{
  Objects.requireNonNull(b);proof(b); // An unavailable source cannot create even a local lock row.
  if(!quarantine.isEmpty())throw new SQLException("UNCERTAIN_CONNECTION_REQUIRES_RECONCILIATION");
  Connection c=source.getConnection();boolean safe=true,committing=false;
  try{if(!c.getAutoCommit())throw new SQLException("DEDICATED_AUTOCOMMIT_CONNECTION_REQUIRED");c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
   try{
    Gate gate=lock(c,b.tenantId(),b.ticketId().toString());var p=proof(b);String digest=bindingDigest(b);
    try(var q=statement(c,"SELECT binding_digest,ticket_fence_digest FROM stock_finance_fence WHERE freeze_id=?",b.freezeId().toString());var r=q.executeQuery()){
     if(r.next()){if(!digest.equals(r.getString(1))||!p.ticketFenceDigest().equals(r.getString(2)))throw new Problem(409,"IDEMPOTENCY_CONFLICT");}
     else{
      if(!"IN_PROGRESS".equals(p.sourceState()))throw new Problem(409,"FINANCE_SOURCE_COMMITTED_BEFORE_OWNER_FENCE");
      if(gate.fenced()||b.generation()!=Math.incrementExact(gate.generation()))throw new Problem(409,"FINANCE_GENERATION_CONFLICT");
      update(c,"INSERT INTO stock_finance_fence VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",b.freezeId().toString(),b.tenantId(),b.companyId(),b.ticketId().toString(),b.roundId().toString(),b.groupId().toString(),b.expectedGroupVersion(),b.quoteId().toString(),b.sourceOperationId().toString(),b.commandDigest(),digest,p.ticketFenceDigest(),b.generation(),now());
      update(c,"UPDATE stock_finance_generation SET generation=?,active_freeze_id=?,row_version=row_version+1 WHERE tenant_id=? AND ticket_id=?",b.generation(),b.freezeId().toString(),b.tenantId(),b.ticketId().toString());audit(c,b,"FENCED");
     }
    }
    try(var q=statement(c,"SELECT receipt_json FROM stock_finance_ack WHERE freeze_id=?",b.freezeId().toString());var r=q.executeQuery()){
     if(r.next()){Receipt receipt=JSON.readValue(r.getString(1),Receipt.class);if(!digest.equals(receipt.bindingDigest())||!b.equals(receipt.binding()))throw new Problem(409,"IDEMPOTENCY_CONFLICT");committing=true;c.commit();return new Outcome("OWNER_FENCED",receipt,List.of());}
    }
    if(!"IN_PROGRESS".equals(p.sourceState()))throw new Problem(409,"FINANCE_SOURCE_COMMITTED_BEFORE_OWNER_ACK");
    Scan scan=scan(c,b,p);if(!scan.unresolved().isEmpty()){committing=true;c.commit();return new Outcome("WAITING_OWNER_OPERATIONS",null,scan.unresolved());}
    String coverage=hash(List.of(digest,p.ticketFenceDigest(),scan.rows()));String ack=hash(List.of("stock","OWNER_FENCED",digest,coverage));String at=Instant.now().toString();Receipt receipt=new Receipt("stock","OWNER_FENCED",b,digest,coverage,ack,scan.rows(),at);
    update(c,"INSERT INTO stock_finance_ack VALUES(?,?,?,?,?)",b.freezeId().toString(),coverage,ack,JSON.writeValueAsString(receipt),now());audit(c,b,"OWNER_ACKED");
    committing=true;c.commit();return new Outcome("OWNER_FENCED",receipt,List.of());
   }catch(SQLException|RuntimeException failure){boolean uncertain=committing;if(!committing)try{c.rollback();}catch(SQLException rollback){uncertain=true;failure.addSuppressed(rollback);}if(uncertain){safe=false;try{c.abort(Runnable::run);}catch(SQLException abort){quarantine.add(c);failure.addSuppressed(abort);}}throw failure;}
  }finally{if(safe)c.close();}
 }
 public Map<String,Object> release(Binding b)throws SQLException{
  terminalProof(b);if(!quarantine.isEmpty())throw new SQLException("UNCERTAIN_CONNECTION_REQUIRES_RECONCILIATION");Connection c=source.getConnection();boolean safe=true,committing=false;
  try{if(!c.getAutoCommit())throw new SQLException("DEDICATED_AUTOCOMMIT_CONNECTION_REQUIRED");c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
   try{Gate gate=lock(c,b.tenantId(),b.ticketId().toString());var proof=terminalProof(b);String digest=bindingDigest(b);
    try(var q=statement(c,"SELECT binding_digest,ticket_fence_digest,source_state,result_digest,receipt_json FROM stock_finance_terminal WHERE freeze_id=?",b.freezeId().toString());var r=q.executeQuery()){
     if(r.next()){if(!digest.equals(r.getString(1))||!proof.ticketFenceDigest().equals(r.getString(2))||!proof.sourceState().equals(r.getString(3))||!Objects.equals(proof.resultDigest(),r.getString(4)))throw new Problem(409,"IDEMPOTENCY_CONFLICT");Map<String,Object> receipt=JSON.readValue(r.getString(5),Map.class);committing=true;c.commit();return receipt;}
    }
    boolean local=false;try(var q=statement(c,"SELECT binding_digest,ticket_fence_digest FROM stock_finance_fence WHERE freeze_id=?",b.freezeId().toString());var r=q.executeQuery()){if(r.next()){local=true;if(!digest.equals(r.getString(1))||!proof.ticketFenceDigest().equals(r.getString(2)))throw new Problem(409,"IDEMPOTENCY_CONFLICT");}}
    if(local){if(gate.generation()!=b.generation()||!gate.fenced())throw new Problem(409,"FINANCE_GENERATION_CONFLICT");try(var q=statement(c,"SELECT active_freeze_id FROM stock_finance_generation WHERE tenant_id=? AND ticket_id=?",b.tenantId(),b.ticketId().toString());var r=q.executeQuery()){if(!r.next()||!b.freezeId().toString().equals(r.getString(1)))throw new Problem(409,"FINANCE_GENERATION_CONFLICT");}}
    else if(!proof.sourceState().equals("ABORTED")||gate.fenced()||b.generation()!=Math.incrementExact(gate.generation()))throw new Problem(409,"FINANCE_OWNER_FENCE_MISSING");
    if(proof.sourceState().equals("COMMITTED")){try(var q=statement(c,"SELECT freeze_id FROM stock_finance_ack WHERE freeze_id=?",b.freezeId().toString());var r=q.executeQuery()){if(!r.next())throw new Problem(409,"FINANCE_OWNER_ACK_MISSING");}}
    String ack=hash(List.of("stock","OWNER_RELEASED",digest,proof.ticketFenceDigest(),proof.sourceState(),Objects.toString(proof.resultDigest(),"")));var receipt=new LinkedHashMap<String,Object>();receipt.put("owner","stock");receipt.put("state","OWNER_RELEASED");receipt.put("sourceState",proof.sourceState());receipt.put("resultDigest",proof.resultDigest());receipt.put("ackDigest",ack);receipt.put("recordedAt",Instant.now().toString());
    update(c,"INSERT INTO stock_finance_terminal VALUES(?,?,?,?,?,?,?)",b.freezeId().toString(),digest,proof.ticketFenceDigest(),proof.sourceState(),proof.resultDigest(),JSON.writeValueAsString(receipt),now());update(c,"INSERT INTO stock_finance_terminal_audit VALUES(?,?,?,?)",b.freezeId().toString(),proof.sourceState(),ack,now());update(c,"UPDATE stock_finance_generation SET generation=?,active_freeze_id=NULL,row_version=row_version+1 WHERE tenant_id=? AND ticket_id=?",b.generation(),b.tenantId(),b.ticketId().toString());committing=true;c.commit();return receipt;
   }catch(SQLException|RuntimeException failure){boolean uncertain=committing;if(!committing)try{c.rollback();}catch(SQLException rollback){uncertain=true;failure.addSuppressed(rollback);}if(uncertain){safe=false;try{c.abort(Runnable::run);}catch(SQLException abort){quarantine.add(c);failure.addSuppressed(abort);}}throw failure;}
  }finally{if(safe)c.close();}
 }
 private VerifiedFence terminalProof(Binding b){var proof=verifier.verify(b);if(proof==null||!b.equals(proof.binding())||!Set.of("COMMITTED","ABORTED").contains(proof.sourceState())||("COMMITTED".equals(proof.sourceState())?!hex(proof.resultDigest()):proof.resultDigest()!=null))throw new Problem(503,"FINANCE_TERMINAL_NOT_CONFIRMED");return proof;}
 private static void audit(Connection c,Binding b,String phase)throws SQLException{String id=UUID.nameUUIDFromBytes(("stock-finance:"+b.freezeId()+":"+phase).getBytes(StandardCharsets.UTF_8)).toString();update(c,"INSERT INTO stock_finance_barrier_audit VALUES(?,?,?,?)",id,b.freezeId().toString(),phase,now());}
 private static Scan scan(Connection c,Binding b,VerifiedFence proof)throws SQLException{
  var rows=new ArrayList<Map<String,String>>();var unresolved=new TreeSet<String>();var orders=new TreeSet<String>();
  proof.inFlightOperationIds().forEach(id->unresolved.add("W_IN_FLIGHT:"+id));
  int count=0;
  try(var q=statement(c,"SELECT order_id,approval_json,state,row_version FROM stock_execution_order WHERE tenant_id=? ORDER BY order_id LIMIT 1001",b.tenantId());var r=q.executeQuery()){
   while(r.next()){if(++count>1000)throw new Problem(503,"STOCK_SCOPE_SNAPSHOT_LIMIT");var approval=JSON.readValue(r.getString(2),StockExecutions.Approval.class);if(!approval.ticketId().equals(b.ticketId().toString()))continue;
    if(approval.tenantId()!=b.tenantId())throw new Problem(503,"STOCK_OWNER_FACT_MISMATCH");String id=r.getString(1);orders.add(id);rows.add(new TreeMap<>(Map.of("type","ORDER","id",id,"state",r.getString(3),"version",r.getString(4),"approvalVersion",Long.toString(approval.version()),"chargeMode",approval.chargeMode(),"warehouseId",Long.toString(approval.warehouseId()),"roundId",approval.roundId())));
    if("UNKNOWN".equals(approval.chargeMode())&&!Set.of("RETURNED","ACCOUNTED","EXPIRED").contains(r.getString(3)))unresolved.add("UNKNOWN_POLICY:"+id);
    try(var l=statement(c,"SELECT part_id,quantity,issued,used,returned FROM stock_execution_line WHERE tenant_id=? AND order_id=? ORDER BY part_id",b.tenantId(),id);var lines=l.executeQuery()){while(lines.next())rows.add(new TreeMap<>(Map.of("type","LINE","orderId",id,"partId",lines.getString(1),"quantity",lines.getString(2),"issued",lines.getString(3),"used",lines.getString(4),"returned",lines.getString(5))));}
   }
  }
  var observed=new HashSet<UUID>();count=0;
  try(var q=statement(c,"SELECT o.operation_id,o.order_id,o.action_name,d.operation_id FROM stock_execution_operation o LEFT JOIN stock_execution_delivery d ON d.tenant_id=o.tenant_id AND d.operation_id=o.operation_id WHERE o.tenant_id=? ORDER BY o.operation_id LIMIT 1001",b.tenantId());var r=q.executeQuery()){
   while(r.next()){if(++count>1000)throw new Problem(503,"STOCK_SCOPE_SNAPSHOT_LIMIT");UUID op=UUID.fromString(r.getString(1));if(!orders.contains(r.getString(2))&&!proof.scopedOperationIds().contains(op))continue;observed.add(op);rows.add(new TreeMap<>(Map.of("type","OPERATION","id",op.toString(),"orderId",r.getString(2),"phase",r.getString(3),"delivered",Boolean.toString(r.getString(4)!=null))));if(!"EXPIRE".equals(r.getString(3))&&r.getString(4)==null)unresolved.add("LOCAL_ACK_UNKNOWN:"+op);
   }
  }
  for(UUID id:proof.scopedOperationIds())if(!observed.contains(id))unresolved.add("LOCAL_RESULT_MISSING:"+id);
  rows.sort(Comparator.comparing(StockFinanceGeneration::hash));return new Scan(List.copyOf(rows),List.copyOf(unresolved));
 }
 static Timestamp now(){return Timestamp.from(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));}
 static PreparedStatement statement(Connection c,String sql,Object...values)throws SQLException{var q=c.prepareStatement(sql);for(int i=0;i<values.length;i++){if(values[i] instanceof Timestamp t)q.setTimestamp(i+1,t,Calendar.getInstance(TimeZone.getTimeZone("UTC")));else q.setObject(i+1,values[i]);}return q;}
 static void update(Connection c,String sql,Object...values)throws SQLException{try(var q=statement(c,sql,values)){q.executeUpdate();}}
}
