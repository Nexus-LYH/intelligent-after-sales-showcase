package com.ias.finance;

import javax.sql.DataSource;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.ias.finance.FeeGroup.*;
import static com.ias.finance.JdbcFeeGroups.*;
import static com.ias.finance.JdbcMoneyLedger.JSON;
import static com.ias.finance.JdbcMoneyLedger.query;
import static com.ias.finance.JdbcMoneyLedger.execute;
import static com.ias.finance.JdbcMoneyLedger.stamp;

/** Source owner: no HTTP lookup, TTL or peer timeout may manufacture ABORTED. */
public final class JdbcQuoteSources {
    public record Binding(Actor actor,Identity owner,UUID operationId,UUID quoteId,long expectedGroupVersion,String commandDigest){
        public Binding{Objects.requireNonNull(actor);Objects.requireNonNull(owner);Objects.requireNonNull(operationId);Objects.requireNonNull(quoteId);if(actor.tenantId()!=owner.tenantId()||expectedGroupVersion<0||commandDigest==null||!commandDigest.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("INVALID_SOURCE_BINDING");}
    }
    public record Source(Binding binding,String state,String resultDigest,Submit command,QuoteDetails details){}
    private final DataSource source;private final Access access;
    private final List<Connection> quarantined=Collections.synchronizedList(new ArrayList<>());
    public JdbcQuoteSources(DataSource source,Access access){this.source=source;this.access=access;}
    static Binding binding(Actor a,Identity o,Submit c,QuoteDetails d){return new Binding(a,o,c.operationId(),c.applicationId(),c.expectedVersion(),JdbcFeeGroups.quoteDigest(o,c,d));}
    @FunctionalInterface interface Work {Source run(Connection db,long groupVersion)throws SQLException;}
    private Source transaction(Actor a,Identity o,Work work)throws SQLException{
        access.current(a,o,"QUOTE_SUBMITTED");if(a.tenantId()!=o.tenantId())throw new SecurityException("TARGET_NOT_ACCESSIBLE");if(!quarantined.isEmpty())throw new SQLException("UNCERTAIN_CONNECTION_REQUIRES_OPERATOR_RECONCILIATION");
        Connection db=source.getConnection();boolean safe=true,committing=false;
        try{if(!db.getAutoCommit())throw new SQLException("DEDICATED_AUTOCOMMIT_CONNECTION_REQUIRED");db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);db.setAutoCommit(false);
            try{long version;try(var q=query(db,"SELECT customer_company_id,row_version FROM finance_fee_group WHERE tenant_id=? AND fault_group_id=? FOR UPDATE",o.tenantId(),o.faultGroupId().toString());var r=q.executeQuery()){if(!r.next()||r.getLong(1)!=o.customerCompanyId())throw new SecurityException("TARGET_NOT_ACCESSIBLE");version=r.getLong(2);}access.current(a,o,"QUOTE_SUBMITTED");var result=work.run(db,version);committing=true;db.commit();return result;}
            catch(SQLException|RuntimeException failure){boolean uncertain=committing;try{db.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);uncertain=true;}if(uncertain){safe=false;try{db.abort(Runnable::run);}catch(SQLException abort){failure.addSuppressed(abort);quarantined.add(db);}throw new SQLException("OPERATION_RESULT_UNKNOWN",failure);}if(failure instanceof SQLException sql&&(sql.getErrorCode()==1062||"23505".equals(sql.getSQLState())))throw new IllegalStateException("SOURCE_BINDING_MISMATCH",failure);throw failure;}
        }finally{if(safe)db.close();}
    }
    public Source register(Actor a,Identity o,Submit c,QuoteDetails d)throws SQLException{
        Binding b=binding(a,o,c,d);
        // Owner lookups must finish before acquiring the local group/source transaction lock.
        boolean partPreflight=!d.partBindings().isEmpty();
        if(partPreflight){access.current(a,o,"QUOTE_SUBMITTED");boolean needsOwnerCheck;
            try(var db=source.getConnection()){var old=find(db,a.tenantId(),c.operationId());needsOwnerCheck=old==null;if(old!=null)match(old,b);}
            if(needsOwnerCheck)access.sourceRegistration(a,o,c,d);
        }
        return transaction(a,o,(db,version)->{
            var old=find(db,a.tenantId(),c.operationId());if(old!=null){match(old,b);return old;}
            if(version!=c.expectedVersion())throw new IllegalStateException("VERSION_CONFLICT");
            // Validate local plan legality before opening an external fence; this is a pure reduction.
            try(var q=query(db,"SELECT state_json FROM finance_fee_group WHERE tenant_id=? AND fault_group_id=?",o.tenantId(),o.faultGroupId().toString());var r=q.executeQuery()){if(!r.next())throw new SecurityException("TARGET_NOT_ACCESSIBLE");FeeGroup.submit(JSON.readValue(r.getString(1),State.class),c);}
            try(var q=query(db,"SELECT actor_id,fault_group_id,action_name,request_hash FROM finance_operation WHERE tenant_id=? AND operation_id=?",a.tenantId(),c.operationId().toString());var r=q.executeQuery()){if(r.next()){if(r.getLong(1)!=a.userId()||!r.getString(2).equals(o.faultGroupId().toString())||!r.getString(3).equals("QUOTE_SUBMITTED")||!r.getString(4).equals(b.commandDigest()))throw new IllegalStateException("OPERATION_CONFLICT");throw new com.ias.trust.Problem(503,"LEGACY_QUOTE_SOURCE_REQUIRES_RECONCILIATION");}}
            if(!partPreflight)access.sourceRegistration(a,o,c,d);
            execute(db,"INSERT INTO finance_quote_source VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",a.tenantId(),c.operationId().toString(),o.faultGroupId().toString(),a.userId(),o.customerCompanyId(),c.applicationId().toString(),c.expectedVersion(),b.commandDigest(),JSON.writeValueAsString(c),JSON.writeValueAsString(d),"IN_PROGRESS",null,stamp(),stamp());
            var result=new Source(b,"IN_PROGRESS",null,c,d);event(db,result,"REGISTERED");return result;
        });
    }
    public Source abort(Actor a,Identity o,UUID op,UUID quote,long expected,String digest,String reason)throws SQLException{
        MoneyLedger.text(reason,500);if(reason.codePointCount(0,reason.length())<5)throw new IllegalArgumentException("REASON_TOO_SHORT");var b=new Binding(a,o,op,quote,expected,digest);
        return transaction(a,o,(db,version)->{var current=required(db,b);
            if(current.state().equals("COMMITTED"))return current;
            if(current.state().equals("ABORTED")){try(var q=query(db,"SELECT reason FROM finance_quote_source_event WHERE tenant_id=? AND operation_id=? AND source_state='ABORTED'",a.tenantId(),op.toString());var r=q.executeQuery()){if(!r.next()||!reason.equals(r.getString(1)))throw new IllegalStateException("SOURCE_ABORT_CONFLICT");}return current;}
            if(execute(db,"UPDATE finance_quote_source SET source_state='ABORTED',updated_at=? WHERE tenant_id=? AND operation_id=? AND source_state='IN_PROGRESS'",stamp(),a.tenantId(),op.toString())!=1)throw new IllegalStateException("SOURCE_STATE_CONFLICT");
            var result=new Source(b,"ABORTED",null,current.command(),current.details());event(db,result,reason);outbox(db,result);return result;
        });
    }
    public Source read(Actor a,Identity o,UUID op)throws SQLException{
        access.current(a,o,"READ");if(a.tenantId()!=o.tenantId())throw new SecurityException("TARGET_NOT_ACCESSIBLE");
        try(var db=source.getConnection()){var result=find(db,a.tenantId(),op);if(result==null||!a.equals(result.binding().actor())||!o.equals(result.binding().owner()))throw new SecurityException("OPERATION_NOT_OBSERVED");return result;}
    }
    /** Reuse the server-frozen rule for an original HTTP command; never let a new publication rehash it. */
    public PublishedRule frozenRule(Actor a,Identity o,UUID op)throws SQLException{
        access.current(a,o,"QUOTE_SUBMITTED");if(a.tenantId()!=o.tenantId())throw new SecurityException("TARGET_NOT_ACCESSIBLE");
        try(var db=source.getConnection()){var result=find(db,a.tenantId(),op);if(result==null)return null;if(!a.equals(result.binding().actor())||!o.equals(result.binding().owner()))throw new SecurityException("TARGET_NOT_ACCESSIBLE");return result.command().rule();}
    }
    /** Caller transport trust is enforced separately. No end-user session is replayed by a reconciler. */
    public Source verified(Binding binding,UUID ticket,UUID round,UUID faultRelation)throws SQLException{
        UUID group=UUID.nameUUIDFromBytes(("finance:"+binding.owner().tenantId()+":"+binding.owner().customerCompanyId()+":"+faultRelation).getBytes(StandardCharsets.UTF_8));
        if(!group.equals(binding.owner().faultGroupId()))throw new IllegalStateException("SOURCE_BINDING_MISMATCH");
        try(var db=source.getConnection()){var result=required(db,binding);if(result.command().additions().stream().noneMatch(l->l.ticketId().equals(ticket)&&l.roundId().equals(round)))throw new IllegalStateException("SOURCE_BINDING_MISMATCH");return result;}
    }
    static boolean committed(Connection db,Actor a,Identity o,Submit c,QuoteDetails d)throws SQLException{var s=required(db,binding(a,o,c,d));if(s.state().equals("ABORTED"))throw new IllegalStateException("FINANCE_SOURCE_ABORTED");return s.state().equals("COMMITTED");}
    static void commit(Connection db,Actor a,Identity o,Submit c,QuoteDetails d,Receipt receipt)throws SQLException{
        var old=required(db,binding(a,o,c,d));if(!old.state().equals("IN_PROGRESS"))throw new IllegalStateException("SOURCE_STATE_CONFLICT");String digest=JdbcMoneyLedger.digest(receipt);
        if(execute(db,"UPDATE finance_quote_source SET source_state='COMMITTED',result_digest=?,updated_at=? WHERE tenant_id=? AND operation_id=? AND source_state='IN_PROGRESS'",digest,stamp(),a.tenantId(),c.operationId().toString())!=1)throw new IllegalStateException("SOURCE_STATE_CONFLICT");
        var result=new Source(old.binding(),"COMMITTED",digest,c,d);event(db,result,"LOCAL_QUOTE_COMMITTED");outbox(db,result);
    }
    static void reservedForQuote(Connection db,long tenant,UUID op)throws SQLException{if(find(db,tenant,op)!=null)throw new IllegalStateException("OPERATION_CONFLICT");}
    private static Source required(Connection db,Binding b)throws SQLException{var result=find(db,b.owner().tenantId(),b.operationId());if(result==null)throw new SecurityException("OPERATION_NOT_OBSERVED");match(result,b);return result;}
    private static void match(Source s,Binding b){if(!s.binding().equals(b))throw new IllegalStateException("SOURCE_BINDING_MISMATCH");}
    private static Source find(Connection db,long tenant,UUID op)throws SQLException{
        try(var q=query(db,"SELECT * FROM finance_quote_source WHERE tenant_id=? AND operation_id=?",tenant,op.toString());var r=q.executeQuery()){if(!r.next())return null;var binding=new Binding(new Actor(tenant,r.getLong("actor_id")),new Identity(tenant,r.getLong("customer_company_id"),UUID.fromString(r.getString("fault_group_id"))),op,UUID.fromString(r.getString("application_id")),r.getLong("expected_group_version"),r.getString("command_digest"));return new Source(binding,r.getString("source_state"),r.getString("result_digest"),JSON.readValue(r.getString("command_json"),Submit.class),JSON.readValue(r.getString("details_json"),QuoteDetails.class));}
    }
    static Map<String,Object> view(Source s){var b=s.binding();var out=new LinkedHashMap<String,Object>();out.put("tenantId",Long.toString(b.owner().tenantId()));out.put("actorUserId",Long.toString(b.actor().userId()));out.put("companyId",Long.toString(b.owner().customerCompanyId()));out.put("groupId",b.owner().faultGroupId().toString());out.put("operationId",b.operationId().toString());out.put("quoteId",b.quoteId().toString());out.put("expectedGroupVersion",Long.toString(b.expectedGroupVersion()));out.put("commandDigest",b.commandDigest());out.put("sourceState",s.state());out.put("state",s.state());out.put("resultDigest",s.resultDigest());return out;}
    private static void event(Connection db,Source s,String reason)throws SQLException{execute(db,"INSERT INTO finance_quote_source_event VALUES(?,?,?,?,?,?)",s.binding().owner().tenantId(),s.binding().operationId().toString(),s.state(),reason,JSON.writeValueAsString(view(s)),stamp());}
    private static void outbox(Connection db,Source s)throws SQLException{var payload=new LinkedHashMap<>(view(s));payload.put("targets",s.command().additions().stream().map(l->Map.of("ticketId",l.ticketId().toString(),"roundId",l.roundId().toString())).distinct().toList());execute(db,"INSERT INTO finance_quote_source_outbox VALUES(?,?,?,?,?)",s.binding().owner().tenantId(),s.binding().operationId().toString(),s.state().equals("COMMITTED")?"FINANCE_QUOTE_SOURCE_COMMITTED":"FINANCE_QUOTE_SOURCE_ABORTED",JSON.writeValueAsString(payload),stamp());}
}
