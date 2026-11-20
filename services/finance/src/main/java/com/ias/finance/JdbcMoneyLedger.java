package com.ias.finance;

import javax.sql.DataSource;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;
import static com.ias.finance.FeeGroup.Identity;
import static com.ias.finance.JdbcFeeGroups.Actor;
import static com.ias.finance.MoneyLedger.*;

/** Append-only facts plus a transactionally versioned balance. No payment gateway is invoked. */
public final class JdbcMoneyLedger {
    public sealed interface Command permits Assess,Lower,Collect,Reverse,RefundPaid,Rebook { long expectedVersion();long expectedGroupVersion(); }
    public record Assess(long expectedVersion,long expectedGroupVersion,UUID billId,Approval approval)implements Command{}
    public record Lower(long expectedVersion,long expectedGroupVersion,UUID billId,UUID lineId,long newCents,UUID adjustmentApprovalId,String coverageDigest,String reason)implements Command{}
    public record Collect(long expectedVersion,long expectedGroupVersion,UUID billId,Payment payment)implements Command{}
    public record Reverse(long expectedVersion,long expectedGroupVersion,UUID originalPaymentId,UUID correctionEvidenceId,String reason)implements Command{}
    public record Rebook(long expectedVersion,long expectedGroupVersion,UUID originalPaymentId,UUID correctionEvidenceId,String reason,Payment replacement)implements Command{}
    public record RefundPaid(long expectedVersion,long expectedGroupVersion,Refund refund)implements Command{}
    public record Receipt(UUID operationId,String action,Identity owner,long groupVersion,State balance){}
    public record Entry(String version,String operationId,String action,Map<String,Object> summary){}
    public record NotificationOwner(long departmentId,long applicantUserId){public NotificationOwner{if(departmentId<=0||applicantUserId<=0)throw new IllegalArgumentException("INVALID_NOTIFICATION_OWNER");}}
    public interface Authority {
        void current(Actor actor,Identity owner,String action);
        /** Must bind all command fields to immutable owner facts, even on an original-key replay.
         * Assess: completed approval + explicit executed/customer-authorized coverage;
         * Lower: customer confirmation + completed finance adjustment approval;
         * Collect/refund: current private file ownership and verified actual external funds;
         * Reverse: verified entry error (not actual refund or removal of real funds).
         */
        void verify(Actor actor,Identity owner,UUID operation,Command command);
        default String executionReceiptsDigest(Actor actor,Identity owner,UUID operation,Assess command){return digest(command.approval().executedAuthorizedLines());}
        default void recorded(Connection db,Actor actor,Identity owner,UUID operation,Command command)throws SQLException{}
        default NotificationOwner notificationOwner(Actor actor,Identity owner){return new NotificationOwner(1,actor.userId());}
    }
    static final JsonMapper JSON=JsonMapper.builder().build();
    private final DataSource source;private final Authority authority;
    private final List<Connection> quarantined=Collections.synchronizedList(new ArrayList<>());
    public JdbcMoneyLedger(DataSource source,Authority authority){this.source=Objects.requireNonNull(source);this.authority=Objects.requireNonNull(authority);}
    public State read(Actor a,Identity o)throws SQLException{authorize(a,o,"READ");try(var db=source.getConnection()){owner(db,o,false);return state(db,o);}}
    public List<Entry> history(Actor a,Identity o,long afterVersion,int limit)throws SQLException{
        if(afterVersion<0||limit<1||limit>50)throw new IllegalArgumentException("INVALID_PAGE");authorize(a,o,"READ");
        try(var db=source.getConnection()){owner(db,o,false);try(var q=query(db,"SELECT account_version,operation_id,action_name,command_json FROM finance_money_entry WHERE tenant_id=? AND fault_group_id=? AND account_version>? ORDER BY account_version LIMIT ?",o.tenantId(),o.faultGroupId().toString(),afterVersion,limit);var r=q.executeQuery()){
            var result=new ArrayList<Entry>();while(r.next()){Map<String,Object> c=JSON.readValue(r.getString(4),Map.class);var safe=new LinkedHashMap<String,Object>();for(String key:List.of("billId","lineId","originalPaymentId"))if(c.containsKey(key))safe.put(key,c.get(key));if(c.containsKey("newCents"))safe.put("newCents",c.get("newCents").toString());
                for(String kind:List.of("payment","refund"))if(c.get(kind) instanceof Map<?,?> fact){safe.put(kind+"Id",fact.get("id"));safe.put("amountCents",fact.get("cents").toString());if(fact.get("originalPaymentId")!=null)safe.put("originalPaymentId",fact.get("originalPaymentId"));if(fact.get("correctionOf")!=null)safe.put("correctionOf",fact.get("correctionOf"));}
                result.add(new Entry(Long.toString(r.getLong(1)),r.getString(2),r.getString(3),safe));}return List.copyOf(result);
        }}
    }
    public Receipt operation(Actor a,Identity o,UUID op)throws SQLException{
        authorize(a,o,"READ");try(var db=source.getConnection();var q=query(db,"SELECT e.receipt_json FROM finance_money_entry e JOIN finance_operation p ON p.tenant_id=e.tenant_id AND p.operation_id=e.operation_id WHERE e.tenant_id=? AND e.operation_id=? AND p.actor_id=? AND p.fault_group_id=?",a.tenantId(),op.toString(),a.userId(),o.faultGroupId().toString());var r=q.executeQuery()){
            if(!r.next())throw new SecurityException("OPERATION_NOT_OBSERVED");return JSON.readValue(r.getString(1),Receipt.class);
        }
    }
    public Receipt apply(Actor a,Identity o,UUID op,Command command)throws SQLException{
        Objects.requireNonNull(op);Objects.requireNonNull(command);validate(command);String action=action(command);authorize(a,o,action);
        if(!quarantined.isEmpty())throw new SQLException("UNCERTAIN_CONNECTION_REQUIRES_OPERATOR_RECONCILIATION");
        String digest=digest(List.of(o,action,command));Connection db=source.getConnection();boolean safe=true,committing=false;
        try{
            if(!db.getAutoCommit())throw new SQLException("DEDICATED_AUTOCOMMIT_CONNECTION_REQUIRED");db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);db.setAutoCommit(false);
            try{
                long groupVersion=owner(db,o,true);authorize(a,o,action);
                JdbcQuoteSources.reservedForQuote(db,o.tenantId(),op);
                try(var q=query(db,"SELECT 1 FROM finance_money_review_event WHERE tenant_id=? AND operation_id=?",o.tenantId(),op.toString());var r=q.executeQuery()){if(r.next())throw new IllegalStateException("OPERATION_CONFLICT");}
                try(var q=query(db,"SELECT actor_id,fault_group_id,action_name,request_hash FROM finance_operation WHERE tenant_id=? AND operation_id=?",o.tenantId(),op.toString());var r=q.executeQuery()){
                    if(r.next()){
                        if(r.getLong(1)!=a.userId()||!r.getString(2).equals(o.faultGroupId().toString())||!r.getString(3).equals(action)||!r.getString(4).equals(digest))throw new IllegalStateException("OPERATION_CONFLICT");
                        // Current authorization and authoritative facts remain mandatory on exact replay.
                        authority.verify(a,o,op,command);
                        Receipt receipt=receipt(db,o,op);committing=true;db.commit();return receipt;
                    }
                }
                authority.verify(a,o,op,command);
                if(command.expectedGroupVersion()!=groupVersion)throw new IllegalStateException("VERSION_CONFLICT");
                State before=state(db,o);State after;
                if(command instanceof Assess c)after=assess(before,c.expectedVersion(),c.billId(),c.approval());
                else if(command instanceof Lower c)after=lower(before,c.expectedVersion(),c.billId(),c.lineId(),c.newCents());
                else if(command instanceof Collect c){if(!c.billId().equals(c.payment().billId()))throw new IllegalArgumentException("PAYMENT_BILL_MISMATCH");after=collect(before,c.expectedVersion(),c.payment());}
                else if(command instanceof Reverse c)after=reverse(before,c.expectedVersion(),c.originalPaymentId());
                else if(command instanceof Rebook c)after=rebook(before,c.expectedVersion(),c.originalPaymentId(),c.replacement());
                else if(command instanceof RefundPaid c)after=refund(before,c.expectedVersion(),c.refund());
                else throw new IllegalArgumentException("INVALID_LEDGER_COMMAND");
                // Immutable original receipt and current-balance projection commit together.
                var receipt=new Receipt(op,action,o,groupVersion,after);
                var common=new JdbcFeeGroups.Receipt(op,action,o,groupVersion,null);
                execute(db,"INSERT INTO finance_operation VALUES(?,?,?,?,?,?,?,?)",o.tenantId(),op.toString(),o.faultGroupId().toString(),a.userId(),action,digest,JSON.writeValueAsString(common),stamp());
                authority.recorded(db,a,o,op,command);
                if(command instanceof Assess c)execute(db,"INSERT INTO finance_receivable_execution_fact VALUES(?,?,?,?,?,?,?,?)",o.tenantId(),c.billId().toString(),o.faultGroupId().toString(),c.approval().completionId().toString(),op.toString(),MoneyAuthorityFacts.binding(a,o,op,c).authorityDigest(),authority.executionReceiptsDigest(a,o,op,c),stamp());
                if(before.version()==0)execute(db,"INSERT INTO finance_money_account VALUES(?,?,?,?,?,?,?,?,?,?)",o.tenantId(),o.faultGroupId().toString(),after.version(),after.receivable(),after.collected(),after.refunded(),after.settled(),after.overpayment(),JSON.writeValueAsString(after),stamp());
                else if(execute(db,"UPDATE finance_money_account SET row_version=?,receivable_cents=?,collected_cents=?,refunded_cents=?,settled_cents=?,overpayment_cents=?,state_json=?,updated_at=? WHERE tenant_id=? AND fault_group_id=? AND row_version=?",after.version(),after.receivable(),after.collected(),after.refunded(),after.settled(),after.overpayment(),JSON.writeValueAsString(after),stamp(),o.tenantId(),o.faultGroupId().toString(),before.version())!=1)throw new IllegalStateException("LEDGER_VERSION_CONFLICT");
                execute(db,"INSERT INTO finance_money_entry VALUES(?,?,?,?,?,?,?,?)",o.tenantId(),op.toString(),o.faultGroupId().toString(),after.version(),action,JSON.writeValueAsString(command),JSON.writeValueAsString(receipt),stamp());
                if(command instanceof Collect c){
                    if(c.payment().correctionOf()==null)reference(db,o,op,"COLLECTION",c.payment().voucher().reference());
                    else try(var q=query(db,"SELECT fault_group_id FROM finance_money_reference WHERE tenant_id=? AND reference_kind='COLLECTION' AND external_reference=?",o.tenantId(),c.payment().voucher().reference());var r=q.executeQuery()){if(!r.next()||!o.faultGroupId().toString().equals(r.getString(1)))throw new IllegalStateException("COLLECTION_REFERENCE_OWNER_CONFLICT");}
                }
                if(command instanceof RefundPaid c)reference(db,o,op,"REFUND",c.refund().voucher().reference());
                if(command instanceof Rebook c)reference(db,o,op,"REVERSAL",c.originalPaymentId().toString());
                if(command instanceof Reverse c)reference(db,o,op,"REVERSAL",c.originalPaymentId().toString());
                execute(db,"INSERT INTO finance_audit VALUES(?,?,?,?,?,?,?)",o.tenantId(),op.toString(),a.userId(),action,o.faultGroupId().toString(),groupVersion,stamp());
                Map<String,Object> terminal=MoneyTerminalFacts.append(db,a,o,op,command,before,after,authority.notificationOwner(a,o));
                String event=terminal==null?"FINANCE_MONEY_RECORDED":action.equals("REFUND_VOUCHER_RECORDED")?"FINANCE_REFUND_FINALIZED":"FINANCE_COLLECTION_FINALIZED";
                execute(db,"INSERT INTO finance_outbox VALUES(?,?,?,?,?,?)",o.tenantId(),op.toString(),op.toString(),event,terminal==null?JSON.writeValueAsString(receipt):JSON.writeValueAsString(terminal),stamp());
                if(terminal!=null)execute(db,"INSERT INTO finance_money_notification_delivery(tenant_id,event_id,state,attempts,next_attempt_at,updated_at) VALUES(?,?,'PENDING',0,?,?)",o.tenantId(),op.toString(),stamp(),stamp());
                committing=true;db.commit();return receipt;
            }catch(SQLException|RuntimeException failure){boolean uncertain=committing;try{db.rollback();}catch(SQLException rollback){failure.addSuppressed(rollback);uncertain=true;}if(uncertain){safe=false;try{db.abort(Runnable::run);}catch(SQLException abort){failure.addSuppressed(abort);quarantined.add(db);}}if(uncertain)throw new SQLException("OPERATION_RESULT_UNKNOWN",failure);if(!uncertain&&failure instanceof SQLException sql&&(sql.getErrorCode()==1062||"23505".equals(sql.getSQLState())))throw new IllegalStateException("MONEY_REFERENCE_CONFLICT",failure);throw failure;}
        }finally{if(safe)db.close();}
    }
    private void authorize(Actor a,Identity o,String action){if(a.tenantId()!=o.tenantId())throw new SecurityException("TARGET_NOT_ACCESSIBLE");authority.current(a,o,action);}
    private static Receipt receipt(Connection db,Identity o,UUID op)throws SQLException{try(var q=query(db,"SELECT receipt_json FROM finance_money_entry WHERE tenant_id=? AND operation_id=?",o.tenantId(),op.toString());var r=q.executeQuery()){if(!r.next())throw new IllegalStateException("LEDGER_RECEIPT_MISSING");return JSON.readValue(r.getString(1),Receipt.class);}}
    private static long owner(Connection db,Identity o,boolean lock)throws SQLException{try(var q=query(db,"SELECT customer_company_id,row_version FROM finance_fee_group WHERE tenant_id=? AND fault_group_id=?"+(lock?" FOR UPDATE":""),o.tenantId(),o.faultGroupId().toString());var r=q.executeQuery()){if(!r.next()||r.getLong(1)!=o.customerCompanyId())throw new SecurityException("TARGET_NOT_ACCESSIBLE");return r.getLong(2);}}
    private static State state(Connection db,Identity o)throws SQLException{try(var q=query(db,"SELECT state_json FROM finance_money_account WHERE tenant_id=? AND fault_group_id=?",o.tenantId(),o.faultGroupId().toString());var r=q.executeQuery()){return r.next()?checked(JSON.readValue(r.getString(1),State.class)):empty();}}
    static void validate(Command c){if(c.expectedVersion()<0||c.expectedGroupVersion()<0)throw new IllegalArgumentException("INVALID_VERSION");if(c instanceof Assess x){Objects.requireNonNull(x.billId());Objects.requireNonNull(x.approval());}else if(c instanceof Lower x){Objects.requireNonNull(x.billId());Objects.requireNonNull(x.lineId());Objects.requireNonNull(x.adjustmentApprovalId());text(x.coverageDigest(),64);if(!x.coverageDigest().matches("[a-f0-9]{64}"))throw new IllegalArgumentException("INVALID_COVERAGE_DIGEST");text(x.reason(),500);}else if(c instanceof Collect x){Objects.requireNonNull(x.billId());Objects.requireNonNull(x.payment());}else if(c instanceof Reverse x){Objects.requireNonNull(x.originalPaymentId());Objects.requireNonNull(x.correctionEvidenceId());text(x.reason(),500);}else if(c instanceof Rebook x){Objects.requireNonNull(x.originalPaymentId());Objects.requireNonNull(x.correctionEvidenceId());Objects.requireNonNull(x.replacement());text(x.reason(),500);if(!x.originalPaymentId().equals(x.replacement().correctionOf()))throw new IllegalArgumentException("REBOOK_ORIGINAL_MISMATCH");}else if(c instanceof RefundPaid x)Objects.requireNonNull(x.refund());}
    static String action(Command c){return c instanceof Assess?"RECEIVABLE_ASSESSED":c instanceof Lower?"RECEIVABLE_LOWERED":c instanceof Collect?"COLLECTION_RECORDED":c instanceof Reverse?"COLLECTION_REVERSED":c instanceof Rebook?"COLLECTION_REBOOKED":"REFUND_VOUCHER_RECORDED";}
    static void reference(Connection db,Identity o,UUID op,String kind,String ref)throws SQLException{execute(db,"INSERT INTO finance_money_reference VALUES(?,?,?,?,?)",o.tenantId(),kind,ref,op.toString(),o.faultGroupId().toString());}
    static PreparedStatement query(Connection db,String sql,Object...values)throws SQLException{var q=db.prepareStatement(sql);try{for(int i=0;i<values.length;i++)q.setObject(i+1,values[i]);return q;}catch(SQLException e){q.close();throw e;}}
    static int execute(Connection db,String sql,Object...values)throws SQLException{try(var q=query(db,sql,values)){return q.executeUpdate();}}
    static Timestamp stamp(){return Timestamp.from(Instant.now());}
    static String digest(Object value){try{Object tree=JSON.readValue(JSON.writeValueAsString(value),Object.class);return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(canonical(tree)).getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException e){throw new AssertionError(e);}}
    static Object canonical(Object value){if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();map.forEach((k,v)->sorted.put(k.toString(),canonical(v)));return sorted;}if(value instanceof List<?> list)return list.stream().map(JdbcMoneyLedger::canonical).toList();return value;}
}
