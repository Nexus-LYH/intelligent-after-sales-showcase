package com.ias.stock;

import com.ias.trust.Problem;
import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Owner-local transactional quantities. Authority and durable ticket permits are supplied by trusted adapters. */
public final class StockExecutions {
 public enum Action { RESERVE, ISSUE, USE, RETURN, EXPIRE }
 public record Actor(long tenantId,long userId){public Actor{if(tenantId<=0||userId<0)throw new IllegalArgumentException();}}
 public record Approval(UUID id,long version,long tenantId,long warehouseId,String ticketId,String roundId,String assignmentId,long engineerUserId,Instant approvedAt,String chargeMode,SortedMap<Long,Long> quantities){
  public Approval{Objects.requireNonNull(id);Objects.requireNonNull(approvedAt);if(version<0||tenantId<=0||warehouseId<=0||engineerUserId<=0||!Set.of("FREE","CUSTOMER_PAID","UNKNOWN").contains(chargeMode)||quantities==null||quantities.isEmpty()||quantities.size()>50)throw new IllegalArgumentException();for(String value:List.of(ticketId,roundId,assignmentId))UUID.fromString(value);var copy=new TreeMap<Long,Long>();quantities.forEach((p,q)->{if(p==null||q==null||p<=0||q<=0)throw new IllegalArgumentException();copy.put(p,q);});quantities=Collections.unmodifiableSortedMap(copy);}
 }
 public record Command(UUID operationId,UUID orderId,Action action,long expectedVersion,SortedMap<Long,Long> quantities){
  public Command{Objects.requireNonNull(operationId);Objects.requireNonNull(orderId);Objects.requireNonNull(action);if(expectedVersion<0||quantities==null||quantities.size()>50)throw new IllegalArgumentException();var copy=new TreeMap<Long,Long>();quantities.forEach((p,q)->{if(p==null||q==null||p<=0||q<=0)throw new IllegalArgumentException();copy.put(p,q);});if((action==Action.USE||action==Action.RETURN)==copy.isEmpty())throw new IllegalArgumentException();quantities=Collections.unmodifiableSortedMap(copy);}
 }
 public record Result(UUID operationId,UUID orderId,String state,@com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) long version,String expiresAt,List<Map<String,String>> lines){}
 public interface Access {
  /** Recheck current identity/object/phase; never trust a public boolean or client approval snapshot. */
  Approval authorize(Actor actor,Command command);
  /** Must verify a persisted owner generation barrier plus exact quantity/money coverage for paid ISSUE/USE. */
  default void requirePaidCoverage(Actor a,Command c,Approval p){throw new Problem(503,"PAID_EXECUTION_AUTHORITY_NOT_AVAILABLE");}
 }
 private final DataSource source;private final Access access;private final Clock clock;
 private final List<Connection> quarantine=Collections.synchronizedList(new ArrayList<>());
 private static final JsonMapper JSON=JsonMapper.builder().build();
 public StockExecutions(DataSource source,Access access,Clock clock){this.source=source;this.access=access;this.clock=clock;}
 public Result execute(Actor actor,Command cmd)throws SQLException{
  if(actor.userId()==0||cmd.action()==Action.EXPIRE)throw new Problem(403,"SYSTEM_ACTION_ONLY");
  return execute(actor,cmd,null);
 }
 private Result execute(Actor actor,Command cmd,Approval system)throws SQLException{
  Approval first=system==null?access.authorize(actor,cmd):system;match(actor,cmd,first);
  if(!quarantine.isEmpty())throw new SQLException("UNCERTAIN_CONNECTION_REQUIRES_RECONCILIATION");
  Connection c=source.getConnection();boolean close=true,commit=false;
  try{if(!c.getAutoCommit())throw new SQLException("DEDICATED_AUTOCOMMIT_CONNECTION_REQUIRED");c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
   try{
    var generation=StockFinanceGeneration.lock(c,actor.tenantId(),first.ticketId());
    // A common warehouse row serializes absent balance/order creation and all quantity updates.
    active(c,"stock_warehouse",actor.tenantId(),first.warehouseId(),system!=null);
    Approval approved=system==null?access.authorize(actor,cmd):system;match(actor,cmd,approved);if(!first.equals(approved))throw conflict("APPROVAL_CHANGED");
    for(long part:approved.quantities().keySet())active(c,"stock_part",actor.tenantId(),part,system!=null);
    String digest=digest(cmd);try(var s=c.prepareStatement("SELECT actor_id,request_hash,result_json FROM stock_execution_operation WHERE tenant_id=? AND operation_id=?")){
     s.setLong(1,actor.tenantId());s.setString(2,cmd.operationId().toString());try(var r=s.executeQuery()){if(r.next()){if(r.getLong(1)!=actor.userId()||!r.getString(2).equals(digest))throw conflict("IDEMPOTENCY_CONFLICT");Result result=JSON.readValue(r.getString(3),Result.class);commit=true;c.commit();return result;}}
    }
    StockFinanceGeneration.writable(generation,cmd.action());
    Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);String state;long version;Instant expires=null;
    try(var s=c.prepareStatement("SELECT approval_json,state,row_version,expires_at FROM stock_execution_order WHERE tenant_id=? AND order_id=? FOR UPDATE")){
     s.setLong(1,actor.tenantId());s.setString(2,cmd.orderId().toString());try(var r=s.executeQuery()){
      if(r.next()){if(!JSON.readValue(r.getString(1),Approval.class).equals(approved))throw conflict("APPROVAL_CHANGED");state=r.getString(2);version=r.getLong(3);Timestamp at=r.getTimestamp(4,utc());if(at!=null)expires=at.toInstant();}
      else{if(cmd.action()!=Action.RESERVE)throw new Problem(404,"STOCK_ORDER_NOT_FOUND");state="WAITING_STOCK";version=0;insertOrder(c,actor,approved,now);}
     }
    }
    if(version!=cmd.expectedVersion()||version==Long.MAX_VALUE)throw conflict("VERSION_CONFLICT");
    var lines=lines(c,actor.tenantId(),cmd.orderId());var changes=new TreeMap<Long,long[]>();
    if(cmd.action()==Action.RESERVE){
     if(!Set.of("WAITING_STOCK","EXPIRED","RESERVED").contains(state))throw conflict("ORDER_NOT_RESERVABLE");
     if(state.equals("RESERVED")){
      throw conflict(now.isBefore(expires)?"ALREADY_RESERVED":"EXPIRE_RESERVATION_FIRST");
     }
     Instant approvalEnd=approved.approvedAt().plus(Duration.ofDays(7));if(!now.isBefore(approvalEnd))throw conflict("APPROVAL_EXPIRED");
     boolean enough=true;for(var l:lines.values()){long[] b=balanceRow(c,actor.tenantId(),approved.warehouseId(),l.part());if(b[0]-b[1]<l.quantity())enough=false;}
     if(enough){for(var l:lines.values())balance(c,actor.tenantId(),approved.warehouseId(),l.part(),0,l.quantity(),changes);state="RESERVED";expires=now.plus(Duration.ofHours(24));if(expires.isAfter(approvalEnd))expires=approvalEnd;}
     else{state="WAITING_STOCK";expires=null;}
    }else if(cmd.action()==Action.EXPIRE){
     if(!state.equals("RESERVED")||expires==null||now.isBefore(expires))throw conflict("RESERVATION_NOT_DUE");
     for(var l:lines.values())balance(c,actor.tenantId(),approved.warehouseId(),l.part(),0,-l.quantity(),changes);state="EXPIRED";
    }else if(cmd.action()==Action.ISSUE){
     if(!state.equals("RESERVED")||expires==null||!now.isBefore(expires))throw conflict("RESERVATION_EXPIRED_OR_NOT_READY");
     charge(actor,cmd,approved);
     for(var l:lines.values()){balance(c,actor.tenantId(),approved.warehouseId(),l.part(),-l.quantity(),-l.quantity(),changes);line(c,actor.tenantId(),cmd.orderId(),new Line(l.part(),l.quantity(),l.quantity(),0,0));}state="ISSUED";expires=null;
    }else{
     if(!state.equals("ISSUED"))throw conflict("ORDER_NOT_ISSUED");if(cmd.action()==Action.USE)charge(actor,cmd,approved);
     for(var e:cmd.quantities().entrySet()){Line l=lines.get(e.getKey());if(l==null||e.getValue()>l.issued()-l.used()-l.returned())throw conflict("QUANTITY_EXCEEDS_UNACCOUNTED");
      Line next=cmd.action()==Action.USE?new Line(l.part(),l.quantity(),l.issued(),Math.addExact(l.used(),e.getValue()),l.returned()):new Line(l.part(),l.quantity(),l.issued(),l.used(),Math.addExact(l.returned(),e.getValue()));
      line(c,actor.tenantId(),cmd.orderId(),next);lines.put(e.getKey(),next);
      if(cmd.action()==Action.RETURN)balance(c,actor.tenantId(),approved.warehouseId(),l.part(),e.getValue(),0,changes);else changes.put(l.part(),new long[]{0,0,e.getValue()});
     }
     if(lines.values().stream().allMatch(l->l.used()+l.returned()==l.issued()))state=lines.values().stream().allMatch(l->l.used()==0)?"RETURNED":"ACCOUNTED";
    }
    try(var s=c.prepareStatement("UPDATE stock_execution_order SET state=?,expires_at=?,row_version=row_version+1,updated_at=? WHERE tenant_id=? AND order_id=?")){s.setString(1,state);s.setTimestamp(2,expires==null?null:Timestamp.from(expires),utc());s.setTimestamp(3,Timestamp.from(now),utc());s.setLong(4,actor.tenantId());s.setString(5,cmd.orderId().toString());if(s.executeUpdate()!=1)throw new SQLException("ORDER_UPDATE_FAILED");}
    var result=new Result(cmd.operationId(),cmd.orderId(),state,version+1,expires==null?null:expires.toString(),view(lines(c,actor.tenantId(),cmd.orderId())));
    record(c,actor,cmd,digest,result,changes,now);StockFinanceGeneration.record(c,generation,actor,cmd,approved);commit=true;c.commit();return result;
   }catch(SQLException|RuntimeException e){boolean failed=false;try{c.rollback();}catch(SQLException rollback){failed=true;e.addSuppressed(rollback);}if(commit||failed){close=false;try{c.abort(Runnable::run);}catch(SQLException abort){quarantine.add(c);e.addSuppressed(abort);}}
    // A durable business rejection is a no-execution tombstone. It closes the owner permit
    // safely even if a duplicate request arrives late. Database/commit uncertainty never does.
    if(!commit&&!failed&&system==null&&e instanceof Problem p&&p.status>=400&&p.status<500){c.close();close=false;reject(actor,cmd,first,p.code);}
    throw e;}
  }finally{if(close)c.close();}
 }
 private void reject(Actor actor,Command cmd,Approval p,String code)throws SQLException{
  Connection c=source.getConnection();boolean close=true,committing=false;
  try{if(!c.getAutoCommit())throw new SQLException("DEDICATED_AUTOCOMMIT_CONNECTION_REQUIRED");c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
   try{active(c,"stock_warehouse",actor.tenantId(),p.warehouseId(),true);try(var q=c.prepareStatement("SELECT actor_id,request_hash FROM stock_execution_operation WHERE tenant_id=? AND operation_id=?")){q.setLong(1,actor.tenantId());q.setString(2,cmd.operationId().toString());try(var r=q.executeQuery()){if(r.next()){if(r.getLong(1)!=actor.userId()||!digest(cmd).equals(r.getString(2)))throw conflict("IDEMPOTENCY_CONFLICT");c.rollback();return;}}}
    Instant now=clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);record(c,actor,cmd,digest(cmd),new Result(cmd.operationId(),cmd.orderId(),"REJECTED:"+code,cmd.expectedVersion(),null,List.of()),Map.of(),now);committing=true;c.commit();
   }catch(SQLException|RuntimeException e){boolean failed=false;try{c.rollback();}catch(SQLException rollback){failed=true;e.addSuppressed(rollback);}if(committing||failed){close=false;try{c.abort(Runnable::run);}catch(SQLException abort){quarantine.add(c);e.addSuppressed(abort);}}throw e;}
  }finally{if(close)c.close();}
 }
 public Optional<Result> original(Actor actor,UUID operation,java.util.function.Consumer<UUID> authorize)throws SQLException{
  Result result;try(var c=source.getConnection();var s=c.prepareStatement("SELECT result_json FROM stock_execution_operation WHERE tenant_id=? AND actor_id=? AND operation_id=?")){s.setLong(1,actor.tenantId());s.setLong(2,actor.userId());s.setString(3,operation.toString());try(var r=s.executeQuery()){if(!r.next())return Optional.empty();result=JSON.readValue(r.getString(1),Result.class);}}
  authorize.accept(result.orderId());return Optional.of(result);
 }
 /** Bounded housekeeping; stable key per reservation version, even after disabled metadata or lost ACK. */
 public int expireDue(int limit)throws SQLException{
  if(limit<1||limit>100)throw new IllegalArgumentException();var candidates=new ArrayList<Map<String,Object>>();
  try(var c=source.getConnection();var s=c.prepareStatement("SELECT tenant_id,order_id,approval_json,row_version FROM stock_execution_order WHERE state='RESERVED' AND expires_at<=? ORDER BY expires_at,order_id LIMIT ?")){
   s.setTimestamp(1,Timestamp.from(clock.instant()),utc());s.setInt(2,limit);try(var r=s.executeQuery()){while(r.next())candidates.add(Map.of("tenant",r.getLong(1),"id",r.getString(2),"approval",r.getString(3),"version",r.getLong(4)));}
  }
  int completed=0;for(var row:candidates){Approval p=JSON.readValue(row.get("approval").toString(),Approval.class);long version=(Long)row.get("version");UUID op=UUID.nameUUIDFromBytes(("stock.expire:"+p.tenantId()+":"+p.id()+":"+version).getBytes(StandardCharsets.UTF_8));
   try{execute(new Actor(p.tenantId(),0),new Command(op,p.id(),Action.EXPIRE,version,new TreeMap<>()),p);completed++;}catch(Problem e){if(e.status!=409)throw e;}
  }return completed;
 }
 private void charge(Actor a,Command c,Approval p){if(p.chargeMode().equals("UNKNOWN"))throw new Problem(503,"EXPLICIT_CHARGE_POLICY_REQUIRED");if(p.chargeMode().equals("CUSTOMER_PAID"))access.requirePaidCoverage(a,c,p);}
 private static void match(Actor a,Command c,Approval p){if(p.tenantId()!=a.tenantId()||!p.id().equals(c.orderId()))throw new Problem(404,"NOT_FOUND");}
 private record Line(long part,long quantity,long issued,long used,long returned){}
 private static SortedMap<Long,Line> lines(Connection c,long tenant,UUID order)throws SQLException{var out=new TreeMap<Long,Line>();try(var s=c.prepareStatement("SELECT part_id,quantity,issued,used,returned FROM stock_execution_line WHERE tenant_id=? AND order_id=? ORDER BY part_id")){s.setLong(1,tenant);s.setString(2,order.toString());try(var r=s.executeQuery()){while(r.next())out.put(r.getLong(1),new Line(r.getLong(1),r.getLong(2),r.getLong(3),r.getLong(4),r.getLong(5)));}}return out;}
 private static List<Map<String,String>> view(SortedMap<Long,Line> lines){return lines.values().stream().map(l->Map.of("partId",Long.toString(l.part()),"quantity",Long.toString(l.quantity()),"issued",Long.toString(l.issued()),"used",Long.toString(l.used()),"returned",Long.toString(l.returned()),"unaccounted",Long.toString(l.issued()-l.used()-l.returned()))).toList();}
 private static void insertOrder(Connection c,Actor a,Approval p,Instant now)throws SQLException{try(var s=c.prepareStatement("INSERT INTO stock_execution_order VALUES(?,?,?,?,?,'WAITING_STOCK',NULL,0,?,?)")){s.setLong(1,a.tenantId());s.setString(2,p.id().toString());s.setLong(3,p.warehouseId());s.setLong(4,p.version());s.setString(5,JSON.writeValueAsString(p));s.setTimestamp(6,Timestamp.from(now),utc());s.setTimestamp(7,Timestamp.from(now),utc());s.executeUpdate();}for(var e:p.quantities().entrySet())try(var s=c.prepareStatement("INSERT INTO stock_execution_line VALUES(?,?,?,?,0,0,0)")){s.setLong(1,a.tenantId());s.setString(2,p.id().toString());s.setLong(3,e.getKey());s.setLong(4,e.getValue());s.executeUpdate();}}
 private static void line(Connection c,long tenant,UUID order,Line l)throws SQLException{try(var s=c.prepareStatement("UPDATE stock_execution_line SET issued=?,used=?,returned=? WHERE tenant_id=? AND order_id=? AND part_id=?")){s.setLong(1,l.issued());s.setLong(2,l.used());s.setLong(3,l.returned());s.setLong(4,tenant);s.setString(5,order.toString());s.setLong(6,l.part());s.executeUpdate();}}
 private static long[] balanceRow(Connection c,long tenant,long warehouse,long part)throws SQLException{try(var s=c.prepareStatement("INSERT IGNORE INTO stock_balance VALUES(?,?,?,0,0,0)")){s.setLong(1,tenant);s.setLong(2,warehouse);s.setLong(3,part);s.executeUpdate();}try(var s=c.prepareStatement("SELECT on_hand,reserved FROM stock_balance WHERE tenant_id=? AND warehouse_id=? AND part_id=? FOR UPDATE")){s.setLong(1,tenant);s.setLong(2,warehouse);s.setLong(3,part);try(var r=s.executeQuery()){if(!r.next())throw new SQLException("BALANCE_NOT_FOUND");return new long[]{r.getLong(1),r.getLong(2)};}}}
 private static void balance(Connection c,long tenant,long warehouse,long part,long onHand,long reserved,Map<Long,long[]> changes)throws SQLException{long[] old=balanceRow(c,tenant,warehouse,part);long next=Math.addExact(old[0],onHand),held=Math.addExact(old[1],reserved);if(next<0||held<0||held>next)throw conflict("INSUFFICIENT_STOCK");try(var s=c.prepareStatement("UPDATE stock_balance SET on_hand=?,reserved=?,row_version=row_version+1 WHERE tenant_id=? AND warehouse_id=? AND part_id=?")){s.setLong(1,next);s.setLong(2,held);s.setLong(3,tenant);s.setLong(4,warehouse);s.setLong(5,part);s.executeUpdate();}long[] d=changes.computeIfAbsent(part,k->new long[3]);d[0]=Math.addExact(d[0],onHand);d[1]=Math.addExact(d[1],reserved);}
 private static void record(Connection c,Actor a,Command cmd,String digest,Result result,Map<Long,long[]> changes,Instant now)throws SQLException{
  String json=JSON.writeValueAsString(result);try(var s=c.prepareStatement("INSERT INTO stock_execution_operation VALUES(?,?,?,?,?,?,?,?)")){s.setLong(1,a.tenantId());s.setString(2,cmd.operationId().toString());s.setLong(3,a.userId());s.setString(4,cmd.orderId().toString());s.setString(5,cmd.action().name());s.setString(6,digest);s.setString(7,json);s.setTimestamp(8,Timestamp.from(now),utc());s.executeUpdate();}
  for(var e:changes.entrySet())try(var s=c.prepareStatement("INSERT INTO stock_execution_movement VALUES(?,?,?,?,?,?)")){s.setLong(1,a.tenantId());s.setString(2,cmd.operationId().toString());s.setLong(3,e.getKey());for(int i=0;i<3;i++)s.setLong(i+4,e.getValue()[i]);s.executeUpdate();}
  boolean rejected=result.state().startsWith("REJECTED:");try(var s=c.prepareStatement("INSERT INTO stock_execution_audit VALUES(?,?,?,?,?)")){s.setLong(1,a.tenantId());s.setString(2,cmd.operationId().toString());s.setLong(3,a.userId());s.setString(4,rejected?"STOCK_REJECTED":"STOCK_"+cmd.action());s.setTimestamp(5,Timestamp.from(now),utc());s.executeUpdate();}
  UUID event=UUID.nameUUIDFromBytes(("stock:"+a.tenantId()+":"+cmd.operationId()).getBytes(StandardCharsets.UTF_8));try(var s=c.prepareStatement("INSERT INTO stock_execution_outbox VALUES(?,?,?,?,?,?)")){s.setLong(1,a.tenantId());s.setString(2,cmd.operationId().toString());s.setString(3,event.toString());s.setString(4,rejected?"stock.rejected":"stock."+cmd.action().name().toLowerCase(Locale.ROOT));s.setString(5,json);s.setTimestamp(6,Timestamp.from(now),utc());s.executeUpdate();}
 }
 public static String digest(Command cmd){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(cmd)));}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
 private static void active(Connection c,String table,long tenant,long id,boolean cleanup)throws SQLException{try(var s=c.prepareStatement("SELECT active FROM "+table+" WHERE tenant_id=? AND id=? FOR UPDATE")){s.setLong(1,tenant);s.setLong(2,id);try(var r=s.executeQuery()){if(!r.next()||(!cleanup&&!r.getBoolean(1)))throw new Problem(404,"NOT_FOUND");}}}
 private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
 private static Problem conflict(String code){return new Problem(409,code);}
}
