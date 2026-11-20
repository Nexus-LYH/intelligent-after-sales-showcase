package com.ias.ai;

import javax.sql.DataSource;
import java.sql.*;
import java.math.*;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import static com.ias.ai.BudgetAdmission.*;

/** Durable admission only. No provider transport, browser endpoint or implicit reconciliation authority. */
public final class JdbcBudgetLedger {
 public enum Action { RESERVE,READ,UNKNOWN,RECONCILE }
 public interface Access { boolean currentAllowed(long tenant,long actor,String budget,String period,Action action); }
 public record Command(long tenant,long actor,String budget,String period,UUID operation,Bound bound) {
  public Command {if(tenant<=0||actor<=0)throw invalid("INVALID_IDENTITY");reference(budget,100);reference(period,100);Objects.requireNonNull(operation);Objects.requireNonNull(bound);reference(bound.price().modelId(),100);reference(bound.price().configVersion(),100);reference(bound.price().priceVersion(),100);money(bound.maximum());money(bound.price().inputPerMillion());money(bound.price().outputPerMillion());}
 }
 public record Receipt(UUID operation,long tenant,long actor,String budget,String period,State state,Bound bound,BigDecimal actualCost){}
 public record Balance(BigDecimal limit,BigDecimal held,BigDecimal spent,long version){}
 private final DataSource source;private final Access access;
 private final List<Connection> quarantine=Collections.synchronizedList(new ArrayList<>());
 public JdbcBudgetLedger(DataSource source,Access access){this.source=Objects.requireNonNull(source);this.access=Objects.requireNonNull(access);if(!Set.of("org.h2.jdbcx.JdbcDataSource","com.mysql.cj.jdbc.MysqlDataSource").contains(source.getClass().getName()))throw invalid("PHYSICAL_DATASOURCE_REQUIRED");}
 private interface Work<T>{T apply(Connection c)throws SQLException;}
 private <T>T transaction(Work<T> work)throws SQLException {
  if(!quarantine.isEmpty())throw new SQLException("UNCERTAIN_CONNECTION_QUARANTINED");
  Connection c=source.getConnection();boolean close=true,commitAttempted=false;
  try{
   if(!c.getAutoCommit())throw new SQLException("FRESH_PHYSICAL_CONNECTION_REQUIRED");
   try(Statement s=c.createStatement()){s.execute(source.getClass().getName().startsWith("org.h2")?"SET TIME ZONE 'UTC'":"SET time_zone = '+00:00'");}
   c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);c.setAutoCommit(false);
   try{T result=work.apply(c);commitAttempted=true;c.commit();return result;}
   catch(SQLException|RuntimeException|Error e){boolean rollbackFailed=false;try{c.rollback();}catch(SQLException r){rollbackFailed=true;e.addSuppressed(r);}if(commitAttempted||rollbackFailed){close=false;try{c.abort(Runnable::run);}catch(SQLException abort){e.addSuppressed(abort);quarantine.add(c);}}throw e;}
  }finally{if(close)c.close();}
 }
 public Receipt reserve(Command command)throws SQLException {
  allowed(command.tenant(),command.actor(),command.budget(),command.period(),Action.RESERVE);
  Bound snapshot=command.bound();Price price=snapshot.price();
  String hash=hash("RESERVE",command.tenant(),command.actor(),command.budget(),command.period(),price.modelId(),price.configVersion(),price.priceVersion(),price.currency(),money(price.inputPerMillion()).toPlainString(),money(price.outputPerMillion()).toPlainString(),snapshot.maxInputTokens(),snapshot.maxOutputTokens(),snapshot.maxBillableAttempts(),money(snapshot.maximum()).toPlainString());
  return transaction(c->{Balance balance=balance(c,command.tenant(),command.budget(),command.period(),true);allowed(command.tenant(),command.actor(),command.budget(),command.period(),Action.RESERVE);Receipt old=reservation(c,command.tenant(),command.operation(),false);
   if(old!=null){if(old.actor()!=command.actor()||!Objects.equals(readHash(c,command.tenant(),command.operation()),hash))throw invalid("IDEMPOTENCY_CONFLICT");return old;}
   String currency=currency(c,command.tenant(),command.budget(),command.period());if(!currency.equals(command.bound().price().currency()))throw invalid("CURRENCY_MISMATCH");
   BigDecimal max=money(command.bound().maximum());if(balance.held().add(balance.spent()).add(max).compareTo(balance.limit())>0)throw invalid("BUDGET_EXHAUSTED");
   update(c,"UPDATE ai_budget SET held_amount=held_amount+?,row_version=row_version+1 WHERE tenant_id=? AND budget_id=? AND period_key=?",max,command.tenant(),command.budget(),command.period());
   Bound b=command.bound();Price p=b.price();Instant at=now(c);
   update(c,"INSERT INTO ai_reservation VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",command.tenant(),command.operation().toString(),command.actor(),command.budget(),command.period(),hash,"RESERVED",p.modelId(),p.configVersion(),p.priceVersion(),p.currency(),money(p.inputPerMillion()),money(p.outputPerMillion()),b.maxInputTokens(),b.maxOutputTokens(),b.maxBillableAttempts(),max,null,Timestamp.from(at),Timestamp.from(at));
   audit(c,command.tenant(),command.actor(),command.operation(),command.operation(),"RESERVE",hash,"",at);
   return reservation(c,command.tenant(),command.operation(),true);
  });
 }
 public Receipt read(long tenant,long actor,String budget,String period,UUID operation)throws SQLException {
  allowed(tenant,actor,budget,period,Action.READ);return transaction(c->owned(c,tenant,actor,budget,period,operation));
 }
 public Receipt markUnknown(long tenant,long actor,String budget,String period,UUID operation,UUID commandId)throws SQLException {
  return change(tenant,actor,budget,period,operation,commandId,"UNKNOWN",null,"",Action.UNKNOWN);
 }
 /** evidenceRef must be a trusted provider/accounting fact, never a browser Boolean or model claim. */
 public Receipt settle(long tenant,long actor,String budget,String period,UUID operation,UUID commandId,BigDecimal actual,String evidenceRef)throws SQLException {
  return change(tenant,actor,budget,period,operation,commandId,"SETTLE",money(actual),reference(evidenceRef,200),Action.RECONCILE);
 }
 public Receipt releaseVerifiedNoCharge(long tenant,long actor,String budget,String period,UUID operation,UUID commandId,String evidenceRef)throws SQLException {
  return change(tenant,actor,budget,period,operation,commandId,"RELEASE",null,reference(evidenceRef,200),Action.RECONCILE);
 }
 private Receipt change(long tenant,long actor,String budget,String period,UUID op,UUID commandId,String action,BigDecimal actual,String evidence,Action privilege)throws SQLException {
  Objects.requireNonNull(op);Objects.requireNonNull(commandId);if(commandId.equals(op))throw invalid("COMMAND_ID_CONFLICT");allowed(tenant,actor,budget,period,privilege);
  String hash=hash(action,tenant,actor,budget,period,op,actual==null?"":actual.toPlainString(),evidence);
  return transaction(c->{balance(c,tenant,budget,period,true);allowed(tenant,actor,budget,period,privilege);Receipt old=owned(c,tenant,actor,budget,period,op);
   try(var q=prepare(c,"SELECT reservation_id,action_name,command_hash FROM ai_budget_audit WHERE tenant_id=? AND command_id=?",tenant,commandId.toString());var r=q.executeQuery()){if(r.next()){if(!r.getString(1).equals(op.toString())||!r.getString(2).equals(action)||!r.getString(3).equals(hash))throw invalid("IDEMPOTENCY_CONFLICT");return old;}}
   State next;BigDecimal settled=null;
   if(action.equals("UNKNOWN")){if(old.state()!=State.RESERVED&&old.state()!=State.UNKNOWN)throw invalid("ALREADY_FINALIZED");next=State.UNKNOWN;}
   else {if(old.state()==State.SETTLED){if(action.equals("SETTLE")&&old.actualCost().compareTo(actual)==0){audit(c,tenant,actor,commandId,op,action,hash,evidence,now(c));return old;}throw invalid("SETTLEMENT_CONFLICT");}if(old.state()==State.RELEASED){if(action.equals("RELEASE")){audit(c,tenant,actor,commandId,op,action,hash,evidence,now(c));return old;}throw invalid("ALREADY_RELEASED_RECONCILE");}
    next=action.equals("SETTLE")?State.SETTLED:State.RELEASED;settled=actual;
    update(c,"UPDATE ai_budget SET held_amount=held_amount-?,spent_amount=spent_amount+?,row_version=row_version+1 WHERE tenant_id=? AND budget_id=? AND period_key=?",money(old.bound().maximum()),actual==null?BigDecimal.ZERO:actual,tenant,budget,period);
   }
   Instant at=now(c);update(c,"UPDATE ai_reservation SET state=?,actual_cost=?,updated_at=? WHERE tenant_id=? AND operation_id=?",next.name(),settled,Timestamp.from(at),tenant,op.toString());audit(c,tenant,actor,commandId,op,action,hash,evidence,at);return reservation(c,tenant,op,true);
  });
 }
 public Balance balance(long tenant,long actor,String budget,String period)throws SQLException {allowed(tenant,actor,budget,period,Action.READ);return transaction(c->balance(c,tenant,budget,period,false));}
 private void allowed(long tenant,long actor,String budget,String period,Action action){if(tenant<=0||actor<=0)throw invalid("INVALID_IDENTITY");reference(budget,100);reference(period,100);if(!access.currentAllowed(tenant,actor,budget,period,action))throw invalid("ACCESS_DENIED");}
 private static Balance balance(Connection c,long tenant,String budget,String period,boolean lock)throws SQLException {try(var q=prepare(c,"SELECT limit_amount,held_amount,spent_amount,row_version FROM ai_budget WHERE tenant_id=? AND budget_id=? AND period_key=?"+(lock?" FOR UPDATE":""),tenant,budget,period);var r=q.executeQuery()){if(!r.next())throw invalid("BUDGET_NOT_ACCESSIBLE");return new Balance(r.getBigDecimal(1),r.getBigDecimal(2),r.getBigDecimal(3),r.getLong(4));}}
 private static String currency(Connection c,long tenant,String budget,String period)throws SQLException {try(var q=prepare(c,"SELECT currency FROM ai_budget WHERE tenant_id=? AND budget_id=? AND period_key=?",tenant,budget,period);var r=q.executeQuery()){if(!r.next())throw invalid("BUDGET_NOT_ACCESSIBLE");return r.getString(1);}}
 private static Receipt owned(Connection c,long tenant,long actor,String budget,String period,UUID id)throws SQLException {Receipt old=reservation(c,tenant,id,true);if(old==null||old.actor()!=actor||!old.budget().equals(budget)||!old.period().equals(period))throw invalid("TARGET_NOT_ACCESSIBLE");return old;}
 private static Receipt reservation(Connection c,long tenant,UUID id,boolean lock)throws SQLException {try(var q=prepare(c,"SELECT * FROM ai_reservation WHERE tenant_id=? AND operation_id=?"+(lock?" FOR UPDATE":""),tenant,id.toString());var r=q.executeQuery()){if(!r.next())return null;Price p=new Price(r.getString("model_id"),r.getString("config_version"),r.getString("price_version"),r.getString("currency"),r.getBigDecimal("input_price"),r.getBigDecimal("output_price"));Bound b=new Bound(p,r.getLong("max_input"),r.getLong("max_output"),r.getInt("max_attempts"),r.getBigDecimal("maximum_cost"));return new Receipt(id,tenant,r.getLong("actor_id"),r.getString("budget_id"),r.getString("period_key"),State.valueOf(r.getString("state")),b,r.getBigDecimal("actual_cost"));}}
 private static String readHash(Connection c,long tenant,UUID id)throws SQLException{try(var q=prepare(c,"SELECT command_hash FROM ai_reservation WHERE tenant_id=? AND operation_id=?",tenant,id.toString());var r=q.executeQuery()){r.next();return r.getString(1);}}
 private static void audit(Connection c,long tenant,long actor,UUID command,UUID reservation,String action,String hash,String evidence,Instant at)throws SQLException{update(c,"INSERT INTO ai_budget_audit VALUES(?,?,?,?,?,?,?,?)",tenant,command.toString(),reservation.toString(),actor,action,hash,evidence,Timestamp.from(at));}
 private static PreparedStatement prepare(Connection c,String sql,Object...args)throws SQLException{var q=c.prepareStatement(sql);for(int i=0;i<args.length;i++){Object a=args[i];if(a instanceof Timestamp t)q.setTimestamp(i+1,t,Calendar.getInstance(TimeZone.getTimeZone("UTC")));else q.setObject(i+1,a);}return q;}
 private static void update(Connection c,String sql,Object...args)throws SQLException{try(var q=prepare(c,sql,args)){if(q.executeUpdate()!=1)throw new SQLException("LEDGER_WRITE_FAILED");}}
 private static Instant now(Connection c)throws SQLException{try(var q=c.createStatement();var r=q.executeQuery("SELECT CURRENT_TIMESTAMP(3)")){r.next();return r.getTimestamp(1,Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant();}}
 private static String hash(Object...values){try{StringBuilder b=new StringBuilder();for(Object v:values){String s=String.valueOf(v);b.append(s.length()).append(':').append(s);}return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b.toString().getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
 private static BigDecimal money(BigDecimal v){if(v==null||v.signum()<0)throw invalid("INVALID_AMOUNT");try{v=v.setScale(12,RoundingMode.UNNECESSARY);}catch(ArithmeticException e){throw invalid("INVALID_AMOUNT_PRECISION");}if(v.precision()>38)throw invalid("AMOUNT_OVERFLOW");return v;}
 private static String reference(String v,int max){if(v==null||v.isBlank()||v.length()>max)throw invalid("REFERENCE_REQUIRED");return v;}
 private static IllegalArgumentException invalid(String code){return new IllegalArgumentException(code);}
}
