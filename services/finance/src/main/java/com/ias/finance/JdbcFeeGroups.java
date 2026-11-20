package com.ias.finance;

import tools.jackson.databind.json.JsonMapper;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.ias.finance.FeeGroup.*;

/** Finance-owned transactions, not an HTTP authorization facade or a receivables ledger. */
public final class JdbcFeeGroups {
    public record Actor(long tenantId,long userId){public Actor{if(tenantId<=0||userId<=0)throw new IllegalArgumentException("INVALID_ACTOR");}}
    public record PartBinding(UUID approvalId,long approvalVersion,long warehouseId,long partId,long quantity,String unitCode,String allocationRule){
        public PartBinding {Objects.requireNonNull(approvalId);if(approvalVersion<=0||warehouseId<=0||partId<=0||quantity<=0||unitCode==null||unitCode.isBlank()||!unitCode.equals(unitCode.strip())||unitCode.length()>20||unitCode.chars().anyMatch(Character::isISOControl)||!"FULL_LINE_SINGLE_USE_V1".equals(allocationRule))throw new IllegalArgumentException("INVALID_PART_BINDING");}
        Map<String,String> canonical(){var m=new TreeMap<String,String>();m.put("approvalId",approvalId.toString());m.put("approvalVersion",Long.toString(approvalVersion));m.put("warehouseId",Long.toString(warehouseId));m.put("partId",Long.toString(partId));m.put("quantity",Long.toString(quantity));m.put("unitCode",unitCode);m.put("allocationRule",allocationRule);return m;}
    }
    public record QuoteDetails(boolean chargesCustomer,String pricingDescription,Map<UUID,String> kinds,Map<UUID,PartBinding> partBindings){
        public QuoteDetails(boolean chargesCustomer,String pricingDescription,Map<UUID,String> kinds){this(chargesCustomer,pricingDescription,kinds,Map.of());}
        public QuoteDetails {
            text(pricingDescription,1000);kinds=Map.copyOf(kinds);partBindings=partBindings==null?Map.of():Map.copyOf(partBindings);
            if(kinds.isEmpty()||!Set.of("LABOR","PART","OTHER").containsAll(kinds.values()))throw new IllegalArgumentException("INVALID_FEE_KIND");
            for(var entry:partBindings.entrySet())if(!"PART".equals(kinds.get(entry.getKey()))||entry.getValue()==null)throw new IllegalArgumentException("INVALID_PART_BINDING");
            if(partBindings.values().stream().map(PartBinding::approvalId).distinct().count()!=partBindings.size())throw new IllegalArgumentException("DUPLICATE_PART_APPROVAL");
        }
    }
    public record QuoteVersion(Submit command,QuoteDetails details,Basis basis){}
    public record Receipt(UUID operationId,String action,Identity owner,long groupVersion,Basis basis){}
    /** Must validate current identity/object scope and authentic owner facts on every call, including replay. */
    public interface Access {
        default void sourceRegistration(Actor actor,Identity owner,Submit command,QuoteDetails details){}
        default void quoteDecision(Actor actor,Identity owner,QuoteVersion quote,UUID operation,String action,long expectedVersion,String reason){throw new com.ias.trust.Problem(503,"FINANCE_QUOTE_FREEZE_AUTHORITY_PENDING");}
        default void approvalHighWater(Connection db,Actor actor,Identity owner)throws SQLException{}
        void current(Actor actor,Identity owner,String action);
        void submission(Actor actor,Identity owner,Submit command,QuoteDetails details);
        void approval(Actor actor,Identity owner,UUID applicationId,ApprovalFact fact);
        void verifiedCost(Actor actor,Identity owner,CostFact fact);
        void execution(Actor actor,Identity owner,UUID lineId);
        default void customerConfirmation(Actor actor,Identity owner,QuoteVersion quote){throw new SecurityException("CUSTOMER_CONFIRMATION_AUTHORITY_UNAVAILABLE");}
    }
    @FunctionalInterface private interface Change { Mutation apply(State before,Connection db)throws SQLException; }
    private record Confirmation(UUID application,long quoteVersion){}
    private record Decision(UUID application,String action,String reason,long quoteVersion){}
    private record Mutation(State state,Basis basis,QuoteVersion quote,CostFact cost,UUID application,ApprovalFact approval,Confirmation confirmation,Decision decision){
        Mutation(State s,Basis b,QuoteVersion q,CostFact c,UUID a,ApprovalFact f){this(s,b,q,c,a,f,null,null);}
        Mutation(State s,Basis b,QuoteVersion q,CostFact c,UUID a,ApprovalFact f,Confirmation x){this(s,b,q,c,a,f,x,null);}
    }
    private final DataSource source;private final Access access;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final List<Connection> quarantined=Collections.synchronizedList(new ArrayList<>());
    public JdbcFeeGroups(DataSource source,Access access){this.source=Objects.requireNonNull(source);this.access=Objects.requireNonNull(access);}

    public Receipt open(Actor actor,Identity owner,UUID operationId)throws SQLException{
        return transaction(actor,owner,operationId,"GROUP_OPEN",List.of(),true,(before,db)->new Mutation(before,null,null,null,null,null));
    }
    public Receipt submit(Actor actor,Identity owner,Submit command,QuoteDetails details)throws SQLException{
        if(!details.kinds().keySet().equals(new HashSet<>(command.additions().stream().map(PlanLine::id).toList())))throw new IllegalArgumentException("FEE_KIND_COVERAGE_MISMATCH");
        new JdbcQuoteSources(source,access).register(actor,owner,command,details);
        var parameters=quoteParameters(command,details);
        return transaction(actor,owner,command.operationId(),"QUOTE_SUBMITTED",parameters,false,(before,db)->{
            boolean committed=JdbcQuoteSources.committed(db,actor,owner,command,details);
            if(!committed){access.submission(actor,owner,command,details);access.approvalHighWater(db,actor,owner);}var result=FeeGroup.submit(before,command);
            PartApprovalClaims.reserve(db,owner,command,details);
            return new Mutation(result.state(),result.basis(),new QuoteVersion(command,details,result.basis()),null,null,null);
        });
    }
    private static Object quoteParameters(Submit command,QuoteDetails details){

        var lines=command.additions().stream().map(l->List.of(l.id().toString(),l.ticketId().toString(),l.roundId().toString(),l.amountCents(),l.executed())).toList();
        var kinds=new TreeMap<String,String>();details.kinds().forEach((k,v)->kinds.put(k.toString(),v));
        var legacy=List.of(command.applicationId().toString(),command.expectedVersion(),command.replaces().stream().map(UUID::toString).sorted().toList(),lines,command.rule().version(),command.rule().thresholdCents(),details.chargesCustomer(),details.pricingDescription(),kinds);
        if(details.partBindings().isEmpty())return legacy;var bindings=new TreeMap<String,Map<String,String>>();details.partBindings().forEach((k,v)->bindings.put(k.toString(),v.canonical()));var versioned=new ArrayList<Object>(legacy);versioned.add("PART_COVERAGE_V2");versioned.add(bindings);return versioned;
    }
    static String quoteDigest(Identity owner,Submit command,QuoteDetails details){return hash(List.of(owner,"QUOTE_SUBMITTED",quoteParameters(command,details)));}
    static Map<String,Object> coverageLine(PlanLine line,QuoteDetails details){var row=new TreeMap<String,Object>();row.put("id",line.id().toString());row.put("ticketId",line.ticketId().toString());row.put("roundId",line.roundId().toString());row.put("amountCents",Long.toString(line.amountCents()));row.put("kind",details.kinds().get(line.id()));var part=details.partBindings().get(line.id());if(part!=null)row.put("partBinding",part.canonical());return row;}
    public Receipt approved(Actor actor,Identity owner,UUID operationId,long expectedVersion,UUID applicationId,ApprovalFact fact)throws SQLException{
        return transaction(actor,owner,operationId,"APPROVAL_RECORDED",List.of(expectedVersion,applicationId.toString(),fact),false,(before,db)->{
            access.approval(actor,owner,applicationId,fact);
            var quote=loadQuote(db,owner,applicationId);undecided(db,owner,applicationId);
            if(quote.details().chargesCustomer()){
                currentQuote(db,owner,applicationId);
                if(confirmation(db,owner,applicationId)==null)throw new IllegalStateException("CUSTOMER_QUOTE_CONFIRMATION_REQUIRED");
                if(before.bCents()>quote.basis().bCents())throw new IllegalStateException("QUOTE_BASIS_CHANGED");
            }
            return new Mutation(recordApproved(before,expectedVersion,applicationId,fact),null,null,null,applicationId,fact);
        });
    }
    public Receipt cost(Actor actor,Identity owner,UUID operationId,long expectedVersion,CostFact fact)throws SQLException{
        text(fact.evidenceRef(),500);
        return transaction(actor,owner,operationId,"COST_VERIFIED",List.of(expectedVersion,fact),false,(before,db)->{access.verifiedCost(actor,owner,fact);return new Mutation(recordVerifiedCost(before,expectedVersion,fact),null,null,fact,null,null);});
    }
    public Receipt protectExecution(Actor actor,Identity owner,UUID operationId,long expectedVersion,UUID lineId)throws SQLException{
        return transaction(actor,owner,operationId,"LINE_EXECUTED",List.of(expectedVersion,lineId.toString()),false,(before,db)->{access.execution(actor,owner,lineId);var line=before.lines().get(lineId);return new Mutation(line!=null&&line.executed()?before:protectExecutedLine(before,expectedVersion,lineId),null,null,null,null,null);});
    }
    public State read(Actor actor,Identity owner)throws SQLException{
        authorize(actor,owner,"READ");try(var db=source.getConnection()){return load(db,owner,false);}
    }
    public Receipt confirm(Actor actor,Identity owner,UUID operation,long expectedVersion,UUID application,long quoteVersion)throws SQLException{
        return transaction(actor,owner,operation,"CUSTOMER_QUOTE_CONFIRMED",List.of(expectedVersion,application.toString(),quoteVersion),false,(before,db)->{
            var quote=loadQuote(db,owner,application);undecided(db,owner,application);access.customerConfirmation(actor,owner,quote);
            if(!quote.details().chargesCustomer())throw new IllegalStateException("CUSTOMER_CONFIRMATION_NOT_APPLICABLE");
            currentQuote(db,owner,application);
            if(quote.basis().groupVersion()!=quoteVersion)throw new IllegalStateException("QUOTE_VERSION_CONFLICT");
            var prior=confirmation(db,owner,application);
            if(prior!=null){if(!operation.toString().equals(prior.get("operation"))||((Number)prior.get("actor")).longValue()!=actor.userId())throw new IllegalStateException("QUOTE_ALREADY_CONFIRMED");return new Mutation(before,quote.basis(),null,null,null,null);}
            if(before.version()!=expectedVersion)throw new IllegalStateException("VERSION_CONFLICT");
            if(before.bCents()>quote.basis().bCents())throw new IllegalStateException("QUOTE_BASIS_CHANGED");
            State after=new State(before.owner(),Math.incrementExact(before.version()),before.lines(),before.costFacts(),before.highestApprovedCents(),before.everRequiredFinance(),before.firstFinanceTrigger(),before.submissions(),before.approvedApplications());
            return new Mutation(after,quote.basis(),null,null,null,null,new Confirmation(application,quoteVersion));
        });
    }
    public Receipt decide(Actor actor,Identity owner,UUID operation,long expectedVersion,UUID application,long quoteVersion,String action,String reason)throws SQLException{
        if(!Set.of("CUSTOMER_QUOTE_REJECTED","QUOTE_WITHDRAWN").contains(action))throw new IllegalArgumentException("INVALID_QUOTE_DECISION");
        text(reason,500);if(reason.codePointCount(0,reason.length())<5)throw new IllegalArgumentException("REASON_TOO_SHORT");
        return transaction(actor,owner,operation,action,List.of(expectedVersion,application.toString(),quoteVersion,reason),false,(before,db)->{
            var quote=loadQuote(db,owner,application);
            access.quoteDecision(actor,owner,quote,operation,action,expectedVersion,reason);
            if(quote.basis().groupVersion()!=quoteVersion)throw new IllegalStateException("QUOTE_VERSION_CONFLICT");
            try(var q=prepare(db,"SELECT operation_id FROM finance_quote_decision WHERE tenant_id=? AND application_id=?",owner.tenantId(),application.toString());var rows=q.executeQuery()){
                if(rows.next()){if(!operation.toString().equals(rows.getString(1)))throw new IllegalStateException("QUOTE_ALREADY_DECIDED");return new Mutation(before,quote.basis(),null,null,null,null);}
            }
            currentQuote(db,owner,application);
            if(before.version()!=expectedVersion)throw new IllegalStateException("VERSION_CONFLICT");
            if(action.equals("CUSTOMER_QUOTE_REJECTED")&&(!quote.details().chargesCustomer()||confirmation(db,owner,application)!=null))throw new IllegalStateException("QUOTE_REJECTION_NOT_APPLICABLE");
            if(before.approvedApplications().containsKey(application))throw new IllegalStateException("APPROVED_QUOTE_REQUIRES_CANCELLATION");
            var effective=new HashMap<>(before.lines());
            for(var line:quote.command().additions()){var current=effective.get(line.id());if(current!=null&&current.executed())throw new IllegalStateException("EXECUTED_QUOTE_REQUIRES_CANCELLATION");effective.remove(line.id());}
            State after=new State(before.owner(),Math.incrementExact(before.version()),effective,before.costFacts(),before.highestApprovedCents(),before.everRequiredFinance(),before.firstFinanceTrigger(),before.submissions(),before.approvedApplications());
            return new Mutation(after,quote.basis(),null,null,null,null,null,new Decision(application,action,reason,quoteVersion));
        });
    }
    private static void undecided(Connection db,Identity owner,UUID application)throws SQLException{
        try(var q=prepare(db,"SELECT application_id FROM finance_quote_decision WHERE tenant_id=? AND application_id=?",owner.tenantId(),application.toString());var rows=q.executeQuery()){
            if(rows.next())throw new IllegalStateException("QUOTE_TERMINATED");
        }
    }
    public Identity owner(Actor actor,UUID group)throws SQLException{return groupOwner(source,actor,group);}
    /** Read-only owner lookup for money paths that must not borrow quote Access. */
    public static Identity groupOwner(javax.sql.DataSource source,Actor actor,UUID group)throws SQLException{
        try(var db=source.getConnection();var q=prepare(db,"SELECT customer_company_id FROM finance_fee_group WHERE tenant_id=? AND fault_group_id=?",actor.tenantId(),group.toString());var r=q.executeQuery()){
            if(!r.next())throw new SecurityException("TARGET_NOT_ACCESSIBLE");return new Identity(actor.tenantId(),r.getLong(1),group);
        }
    }
    public Receipt operation(Actor actor,Identity owner,UUID operation)throws SQLException{
        authorize(actor,owner,"READ");try(var db=source.getConnection();var q=prepare(db,"SELECT actor_id,fault_group_id,receipt_json FROM finance_operation WHERE tenant_id=? AND operation_id=?",actor.tenantId(),operation.toString());var r=q.executeQuery()){
            if(!r.next()||r.getLong(1)!=actor.userId()||!r.getString(2).equals(owner.faultGroupId().toString()))throw new SecurityException("OPERATION_NOT_OBSERVED");return JSON.readValue(r.getString(3),Receipt.class);
        }
    }
    private static void currentQuote(Connection db,Identity owner,UUID application)throws SQLException{
        try(var q=prepare(db,"SELECT application_id FROM finance_quote_version WHERE tenant_id=? AND fault_group_id=? ORDER BY group_version DESC LIMIT 1",owner.tenantId(),owner.faultGroupId().toString());var r=q.executeQuery()){
            if(!r.next()||!application.toString().equals(r.getString(1)))throw new IllegalStateException("QUOTE_NOT_CURRENT");
        }
    }
    private static Map<String,Object> confirmation(Connection db,Identity owner,UUID application)throws SQLException{
        try(var q=prepare(db,"SELECT operation_id,actor_id FROM finance_customer_confirmation WHERE tenant_id=? AND application_id=?",owner.tenantId(),application.toString());var r=q.executeQuery()){
            return r.next()?Map.of("operation",r.getString(1),"actor",r.getLong(2)):null;
        }
    }
    public QuoteVersion quote(Actor actor,Identity owner,UUID application)throws SQLException{
        authorize(actor,owner,"READ");try(var db=source.getConnection()){
            load(db,owner,false);
            return loadQuote(db,owner,application);
        }
    }
    public Map<String,Object> quoteStatus(Actor actor,Identity owner,UUID application)throws SQLException{
        authorize(actor,owner,"READ");try(var db=source.getConnection()){
            var quote=loadQuote(db,owner,application);boolean current;
            try(var q=prepare(db,"SELECT application_id FROM finance_quote_version WHERE tenant_id=? AND fault_group_id=? ORDER BY group_version DESC LIMIT 1",owner.tenantId(),owner.faultGroupId().toString());var r=q.executeQuery()){current=r.next()&&application.toString().equals(r.getString(1));}
            boolean confirmed=confirmation(db,owner,application)!=null;boolean requested;
            try(var q=prepare(db,"SELECT application_id FROM finance_approval_request WHERE tenant_id=? AND application_id=?",owner.tenantId(),application.toString());var r=q.executeQuery()){requested=r.next();}
            String decision="NONE";try(var q=prepare(db,"SELECT action_name FROM finance_quote_decision WHERE tenant_id=? AND application_id=?",owner.tenantId(),application.toString());var rows=q.executeQuery()){if(rows.next())decision=rows.getString(1);}
            return Map.of("current",current,"termination",decision,"customerDecision",decision.equals("CUSTOMER_QUOTE_REJECTED")?"REJECTED":!quote.details().chargesCustomer()?"NOT_REQUIRED":confirmed?"CONFIRMED":"PENDING","approvalRequestState",requested?"RECORDED_LOCALLY":"NOT_CREATED");
        }
    }
    private static QuoteVersion loadQuote(Connection db,Identity owner,UUID application)throws SQLException{
        try(var q=prepare(db,"SELECT quote_json FROM finance_quote_version WHERE tenant_id=? AND fault_group_id=? AND application_id=?",owner.tenantId(),owner.faultGroupId().toString(),application.toString());var rows=q.executeQuery()){
            if(!rows.next())throw new SecurityException("TARGET_NOT_ACCESSIBLE");return JSON.readValue(rows.getString(1),QuoteVersion.class);
        }
    }
    private void authorize(Actor actor,Identity owner,String action){if(actor.tenantId()!=owner.tenantId())throw new SecurityException("TARGET_NOT_ACCESSIBLE");access.current(actor,owner,action);}
    private Receipt transaction(Actor actor,Identity owner,UUID operation,String action,Object parameters,boolean create,Change change)throws SQLException{
        Objects.requireNonNull(operation);authorize(actor,owner,action);
        if(!quarantined.isEmpty())throw new SQLException("UNCERTAIN_CONNECTION_REQUIRES_OPERATOR_RECONCILIATION");
        String hash=hash(List.of(owner,action,parameters));Connection db=source.getConnection();boolean safe=true,commitAttempted=false;
        try{
            if(!db.getAutoCommit())throw new SQLException("DEDICATED_AUTOCOMMIT_CONNECTION_REQUIRED");
            db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);db.setAutoCommit(false);
            try{
                State before;boolean newGroup=false;
                if(create){
                    before=find(db,owner,true);
                    if(before==null){before=FeeGroup.open(owner);insertGroup(db,before);newGroup=true;}
                }else before=load(db,owner,true);
                // Recheck after acquiring owner lock; replay must never bypass current scope.
                authorize(actor,owner,action);
                if(!action.equals("QUOTE_SUBMITTED"))JdbcQuoteSources.reservedForQuote(db,owner.tenantId(),operation);
                try(var q=prepare(db,"SELECT fault_group_id,actor_id,action_name,request_hash,receipt_json FROM finance_operation WHERE tenant_id=? AND operation_id=?",owner.tenantId(),operation.toString());var rows=q.executeQuery()){
                    if(rows.next()){
                        if(!rows.getString(1).equals(owner.faultGroupId().toString())||rows.getLong(2)!=actor.userId()||!rows.getString(3).equals(action)||!rows.getString(4).equals(hash))throw new IllegalStateException("OPERATION_CONFLICT");
                        // Owner-specific current fact verification also applies to an exact replay.
                        change.apply(before,db);
                        Receipt receipt=JSON.readValue(rows.getString(5),Receipt.class);commitAttempted=true;db.commit();return receipt;
                    }
                }
                if(create&&!newGroup)throw new IllegalStateException("GROUP_ALREADY_EXISTS");
                Mutation mutation=change.apply(before,db);State after=mutation.state();
                if(!after.owner().equals(owner))throw new IllegalStateException("GROUP_OWNER_CHANGED");
                if(after.version()!=before.version())updateGroup(db,before,after);
                Receipt receipt=new Receipt(operation,action,owner,after.version(),mutation.basis());
                execute(db,"INSERT INTO finance_operation VALUES(?,?,?,?,?,?,?,?)",owner.tenantId(),operation.toString(),owner.faultGroupId().toString(),actor.userId(),action,hash,JSON.writeValueAsString(receipt),stamp());
                if(mutation.quote()!=null)execute(db,"INSERT INTO finance_quote_version VALUES(?,?,?,?,?,?,?,?)",owner.tenantId(),mutation.quote().command().applicationId().toString(),owner.faultGroupId().toString(),after.version(),operation.toString(),JSON.writeValueAsString(mutation.quote()),JSON.writeValueAsString(mutation.basis()),stamp());
                if(mutation.cost()!=null&&!before.costFacts().containsKey(mutation.cost().id())){
                    var cost=mutation.cost();execute(db,"INSERT INTO finance_cost_fact VALUES(?,?,?,?,?,?,?,?)",owner.tenantId(),cost.id().toString(),owner.faultGroupId().toString(),operation.toString(),cost.deltaCents(),cost.correctionOf()==null?null:cost.correctionOf().toString(),cost.evidenceRef(),stamp());
                }
                if(mutation.approval()!=null&&!before.approvedApplications().containsKey(mutation.application()))execute(db,"INSERT INTO finance_approval_fact VALUES(?,?,?,?,?,?,?)",owner.tenantId(),mutation.application().toString(),mutation.approval().completionEventId().toString(),owner.faultGroupId().toString(),operation.toString(),JSON.writeValueAsString(mutation.approval()),stamp());
                if(mutation.confirmation()!=null){
                    var c=mutation.confirmation();execute(db,"INSERT INTO finance_customer_confirmation VALUES(?,?,?,?,?,?,?)",owner.tenantId(),c.application().toString(),owner.faultGroupId().toString(),operation.toString(),actor.userId(),c.quoteVersion(),stamp());
                    execute(db,"INSERT INTO finance_approval_request VALUES(?,?,?,?,?,?)",owner.tenantId(),c.application().toString(),owner.faultGroupId().toString(),operation.toString(),JSON.writeValueAsString(mutation.basis()),stamp());
                }
                if(mutation.decision()!=null){var d=mutation.decision();execute(db,"INSERT INTO finance_quote_decision VALUES(?,?,?,?,?,?,?,?,?)",owner.tenantId(),d.application().toString(),owner.faultGroupId().toString(),operation.toString(),actor.userId(),d.action(),d.reason(),d.quoteVersion(),stamp());}
                boolean freeRequest=mutation.quote()!=null&&!mutation.quote().details().chargesCustomer();
                if(freeRequest)execute(db,"INSERT INTO finance_approval_request VALUES(?,?,?,?,?,?)",owner.tenantId(),mutation.quote().command().applicationId().toString(),owner.faultGroupId().toString(),operation.toString(),JSON.writeValueAsString(mutation.basis()),stamp());
                execute(db,"INSERT INTO finance_audit VALUES(?,?,?,?,?,?,?)",owner.tenantId(),operation.toString(),actor.userId(),action,owner.faultGroupId().toString(),after.version(),stamp());
                if(mutation.quote()!=null)JdbcQuoteSources.commit(db,actor,owner,mutation.quote().command(),mutation.quote().details(),receipt);
                // Domain fact only: this is NOT an instruction to start approval before customer confirmation.
                String event=mutation.decision()!=null?mutation.decision().action():mutation.confirmation()!=null||freeRequest?"FINANCE_APPROVAL_REQUESTED":mutation.quote()!=null?"FINANCE_QUOTE_AWAITING_CUSTOMER":"FINANCE_GROUP_CHANGED";
                execute(db,"INSERT INTO finance_outbox VALUES(?,?,?,?,?,?)",owner.tenantId(),operation.toString(),operation.toString(),event,JSON.writeValueAsString(receipt),stamp());
                commitAttempted=true;db.commit();return receipt;
            }catch(SQLException|RuntimeException failure){
                boolean uncertain=commitAttempted;try{db.rollback();}catch(SQLException rollback){uncertain=true;failure.addSuppressed(rollback);}
                if(uncertain){safe=false;try{db.abort(Runnable::run);}catch(SQLException abort){failure.addSuppressed(abort);quarantined.add(db);}}
                if(uncertain)throw new SQLException("OPERATION_RESULT_UNKNOWN",failure);
                throw failure;
            }
        }finally{if(safe)db.close();}
    }
    private State load(Connection db,Identity owner,boolean lock)throws SQLException{State state=find(db,owner,lock);if(state==null)throw new SecurityException("TARGET_NOT_ACCESSIBLE");return state;}
    private State find(Connection db,Identity owner,boolean lock)throws SQLException{
        try(var q=prepare(db,"SELECT customer_company_id,state_json FROM finance_fee_group WHERE tenant_id=? AND fault_group_id=?"+(lock?" FOR UPDATE":""),owner.tenantId(),owner.faultGroupId().toString());var rows=q.executeQuery()){
            if(!rows.next())return null;if(rows.getLong(1)!=owner.customerCompanyId())throw new SecurityException("TARGET_NOT_ACCESSIBLE");
            State state=JSON.readValue(rows.getString(2),State.class);if(!state.owner().equals(owner))throw new IllegalStateException("GROUP_STATE_CORRUPT");return state;
        }
    }
    private static void insertGroup(Connection db,State state)throws SQLException{var owner=state.owner();execute(db,"INSERT INTO finance_fee_group VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",owner.tenantId(),owner.faultGroupId().toString(),owner.customerCompanyId(),0L,0L,0L,0L,0L,false,null,null,null,JSON.writeValueAsString(state),stamp());}
    private static void updateGroup(Connection db,State before,State after)throws SQLException{
        var trigger=after.firstFinanceTrigger();int changed=execute(db,"UPDATE finance_fee_group SET row_version=?,q_cents=?,c_cents=?,h_cents=?,b_cents=?,ever_required_finance=?,first_trigger_application_id=?,first_trigger_rule_version=?,first_trigger_group_version=?,state_json=?,updated_at=? WHERE tenant_id=? AND fault_group_id=? AND row_version=?",after.version(),after.qCents(),after.cCents(),after.highestApprovedCents(),after.bCents(),after.everRequiredFinance(),trigger==null?null:trigger.applicationId().toString(),trigger==null?null:trigger.ruleVersion(),trigger==null?null:trigger.groupVersion(),JSON.writeValueAsString(after),stamp(),after.owner().tenantId(),after.owner().faultGroupId().toString(),before.version());
        if(changed!=1)throw new IllegalStateException("VERSION_CONFLICT");
    }
    private static PreparedStatement prepare(Connection db,String sql,Object...values)throws SQLException{var q=db.prepareStatement(sql);try{for(int i=0;i<values.length;i++){if(values[i] instanceof Timestamp timestamp)q.setTimestamp(i+1,timestamp,Calendar.getInstance(TimeZone.getTimeZone("UTC")));else q.setObject(i+1,values[i]);}return q;}catch(SQLException failure){q.close();throw failure;}}
    private static int execute(Connection db,String sql,Object...values)throws SQLException{try(var q=prepare(db,sql,values)){return q.executeUpdate();}}
    private static Timestamp stamp(){return Timestamp.from(Instant.now());}
    private static String hash(Object values){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsString(values).getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}}
    private static void text(String value,int max){if(value==null||value.isBlank()||value.length()>max||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("INVALID_TEXT");}
}
