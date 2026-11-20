package com.ias.approval;

import tools.jackson.databind.json.JsonMapper;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Local owner candidate. No HTTP route; a verified source/W adapter and all paid-write hooks are prerequisites. */
public final class ApprovalFinanceGeneration {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public record Binding(String tenantId,String companyId,UUID ticketId,UUID roundId,UUID groupId,
                          String expectedGroupVersion,UUID quoteId,UUID sourceOperationId,String commandDigest,
                          UUID freezeId,String generation,String actorUserId,UUID faultRelationId,String ticketFenceDigest) {
        /** Compatibility constructor for isolated core callers; production wire must supply all 14 fields. */
        public Binding(String tenantId,String companyId,UUID ticketId,UUID roundId,UUID groupId,String expectedGroupVersion,UUID quoteId,UUID sourceOperationId,String commandDigest,UUID freezeId,String generation){this(tenantId,companyId,ticketId,roundId,groupId,expectedGroupVersion,quoteId,sourceOperationId,commandDigest,freezeId,generation,"1",ticketId,"0".repeat(64));}
        public Binding {
            positive(tenantId); positive(companyId); number(expectedGroupVersion); positive(generation);
            Objects.requireNonNull(ticketId);Objects.requireNonNull(roundId);Objects.requireNonNull(groupId);
            Objects.requireNonNull(quoteId);Objects.requireNonNull(sourceOperationId);Objects.requireNonNull(freezeId);
            if(commandDigest==null||!commandDigest.matches("[0-9a-f]{64}"))throw fail(400,"INVALID_COMMAND_DIGEST");
            positive(actorUserId);if(!ticketId.equals(faultRelationId))throw fail(400,"UNSUPPORTED_FAULT_RELATION");
            if(ticketFenceDigest==null||!ticketFenceDigest.matches("[0-9a-f]{64}"))throw fail(400,"INVALID_TICKET_FENCE_DIGEST");
        }
    }
    /** Must validate authentic source intent and W binding, not body-supplied booleans. Called before any DB write. */
    @FunctionalInterface public interface Verifier { void verify(Binding binding); }
    public record Result(String state,Map<String,Object> ack,List<String> blockers,Map<String,Object> localSnapshot) {
        public Result(String state,Map<String,Object> ack,List<String> blockers){this(state,ack,blockers,ack==null?Map.of():castMap(ack.get("localSnapshot")));}
    }
    private final DataSource source; private final Verifier verifier;
    private volatile boolean quarantined;
    public ApprovalFinanceGeneration(DataSource source){this(source,b->{throw fail(503,"FINANCE_SOURCE_ADAPTER_UNAVAILABLE");});}
    public ApprovalFinanceGeneration(DataSource source,Verifier verifier){this.source=Objects.requireNonNull(source);this.verifier=Objects.requireNonNull(verifier);}

    public Result fence(Binding b){
        verifier.verify(b);
        if(quarantined)throw fail(503,"OWNER_CONNECTION_QUARANTINED");
        Connection c;
        try{c=source.getConnection();}catch(SQLException e){throw fail(503,"DEPENDENCY_UNAVAILABLE");}
        boolean uncertain=false;
        try{
            c.setAutoCommit(false);
            long current=lock(c,b.tenantId(),b.ticketId());
            verifier.verify(b); // Source/W may have changed while this transaction waited for the generation lock.
            String generationState=one(c,"SELECT state FROM approval_finance_generation WHERE tenant_id=? AND ticket_id=? FOR UPDATE",b.tenantId(),b.ticketId());
            String binding=json(b),digest=sha(binding);
            String old=one(c,"SELECT binding_digest FROM approval_finance_fence WHERE tenant_id=? AND freeze_id=?",b.tenantId(),b.freezeId());
            if(old!=null&&!old.equals(digest))throw fail(409,"FREEZE_BINDING_CONFLICT");
            if(one(c,"SELECT source_state FROM approval_finance_terminal WHERE tenant_id=? AND freeze_id=?",b.tenantId(),b.freezeId())!=null)throw fail(409,"OWNER_ALREADY_RELEASED");
            long generation=number(b.generation());
            if(generation<current||old==null&&(current==Long.MAX_VALUE||generation!=current+1||!"OPEN".equals(generationState)))throw fail(409,"STALE_OR_ACTIVE_GENERATION");
            String receipt=one(c,"SELECT ack_json FROM approval_finance_owner_ack WHERE tenant_id=? AND freeze_id=?",b.tenantId(),b.freezeId());
            if(receipt!=null){try{c.commit();}catch(SQLException e){uncertain=true;throw fail(503,"OPERATION_RESULT_UNKNOWN");}return new Result("OWNER_FENCED",map(receipt),List.of());}
            Map<String,Object> snapshot=snapshot(c,b); @SuppressWarnings("unchecked") List<String> blockers=(List<String>)snapshot.get("blockers");
            Instant now=Instant.now().truncatedTo(ChronoUnit.MILLIS);
            if(old==null){
                execute(c,"UPDATE approval_finance_generation SET generation=?,state='FENCED' WHERE tenant_id=? AND ticket_id=?",generation,b.tenantId(),b.ticketId());
                execute(c,"INSERT INTO approval_finance_fence(tenant_id,freeze_id,ticket_id,generation,binding_digest,binding_json,initial_snapshot,recorded_at) VALUES(?,?,?,?,?,?,?,?)",b.tenantId(),b.freezeId(),b.ticketId(),generation,digest,binding,json(snapshot),utc(now));
                audit(c,b,"FENCE_RECORDED",digest,now);
            }
            FeeApprovalPersistence.terminate(c,b);
            snapshot=snapshot(c,b);blockers=(List<String>)snapshot.get("blockers");
            Map<String,Object> ack=null;
            if(blockers.isEmpty()){
                var unsigned=new TreeMap<String,Object>(wire(b));unsigned.put("owner","approval");unsigned.put("state","OWNER_FENCED");
                unsigned.put("freezeId",b.freezeId().toString());unsigned.put("generation",b.generation());unsigned.put("bindingDigest",digest);
                unsigned.put("coverageDigest",sha(json(snapshot)));unsigned.put("localSnapshot",snapshot);unsigned.put("recordedAt",now.toString());unsigned.put("globalFreezeAck",false);
                unsigned.put("ackDigest",sha(json(unsigned)));ack=Collections.unmodifiableMap(map(json(unsigned)));
                execute(c,"INSERT INTO approval_finance_owner_ack(tenant_id,freeze_id,ack_json,recorded_at) VALUES(?,?,?,?)",b.tenantId(),b.freezeId(),json(ack),utc(now));
                audit(c,b,"OWNER_FENCED",digest,now);
            }
            try{c.commit();}catch(SQLException e){uncertain=true;throw fail(503,"OPERATION_RESULT_UNKNOWN");}
            return new Result(ack==null?"WAITING_OWNER_OPERATIONS":"OWNER_FENCED",ack,List.copyOf(blockers),snapshot);
        }catch(SQLException e){try{c.rollback();}catch(SQLException rollback){uncertain=true;}throw fail(503,"DEPENDENCY_UNAVAILABLE");}
        catch(RuntimeException|Error e){if(!uncertain)try{c.rollback();}catch(SQLException rollback){uncertain=true;}throw e;}
        finally{if(uncertain)invalidate(c);else try{c.close();}catch(SQLException e){invalidate(c);}}
    }

    /** Trusted immutable source terminal proof only; never infer terminal from absence or time. */
    public Map<String,Object> reconcile(Binding b,String sourceState,String resultDigest){
        if(!Set.of("COMMITTED","ABORTED").contains(sourceState)||"COMMITTED".equals(sourceState)&&(resultDigest==null||!resultDigest.matches("[0-9a-f]{64}"))||"ABORTED".equals(sourceState)&&resultDigest!=null)throw fail(400,"INVALID_SOURCE_TERMINAL");
        verifier.verify(b);if(quarantined)throw fail(503,"OWNER_CONNECTION_QUARANTINED");
        Connection c;try{c=source.getConnection();}catch(SQLException e){throw fail(503,"DEPENDENCY_UNAVAILABLE");}boolean uncertain=false;
        try{
            c.setAutoCommit(false);long current=lock(c,b.tenantId(),b.ticketId());verifier.verify(b);
            String digest=sha(json(b));String known=one(c,"SELECT binding_digest FROM approval_finance_fence WHERE tenant_id=? AND freeze_id=?",b.tenantId(),b.freezeId());
            if(known==null){
                if(!"ABORTED".equals(sourceState)||current==Long.MAX_VALUE||number(b.generation())!=current+1||!"OPEN".equals(one(c,"SELECT state FROM approval_finance_generation WHERE tenant_id=? AND ticket_id=? FOR UPDATE",b.tenantId(),b.ticketId())))throw fail(409,"OWNER_FENCE_NOT_OBSERVED");
                Instant now=Instant.now().truncatedTo(ChronoUnit.MILLIS);
                execute(c,"INSERT INTO approval_finance_fence(tenant_id,freeze_id,ticket_id,generation,binding_digest,binding_json,initial_snapshot,recorded_at) VALUES(?,?,?,?,?,?,?,?)",b.tenantId(),b.freezeId(),b.ticketId(),number(b.generation()),digest,json(b),json(Map.of("noExecution",true,"reason","VERIFIED_SOURCE_ABORTED_BEFORE_OWNER_PREPARE")),utc(now));
                execute(c,"UPDATE approval_finance_generation SET generation=?,state='FENCED' WHERE tenant_id=? AND ticket_id=?",number(b.generation()),b.tenantId(),b.ticketId());current=number(b.generation());audit(c,b,"ABORT_TOMBSTONE",digest,now);
            }else if(!digest.equals(known))throw fail(409,"FREEZE_BINDING_CONFLICT");
            String prior=one(c,"SELECT receipt_json FROM approval_finance_terminal WHERE tenant_id=? AND freeze_id=?",b.tenantId(),b.freezeId());
            Map<String,Object> receipt;
            if(prior!=null){receipt=map(prior);if(!sourceState.equals(receipt.get("sourceState"))||!Objects.equals(resultDigest,receipt.get("resultDigest")))throw fail(409,"SOURCE_TERMINAL_CONFLICT");}
            else{
                if(current!=number(b.generation())||!"FENCED".equals(one(c,"SELECT state FROM approval_finance_generation WHERE tenant_id=? AND ticket_id=? FOR UPDATE",b.tenantId(),b.ticketId())))throw fail(409,"STALE_OR_RELEASED_GENERATION");
                Instant now=Instant.now().truncatedTo(ChronoUnit.MILLIS);var value=new TreeMap<String,Object>(wire(b));
                value.put("owner","approval");value.put("state","OWNER_RELEASED");value.put("sourceState",sourceState);value.put("resultDigest",resultDigest);value.put("bindingDigest",digest);value.put("recordedAt",now.toString());value.put("globalFreezeAck",false);value.put("ackDigest",sha(json(value)));receipt=value;
                execute(c,"INSERT INTO approval_finance_terminal(tenant_id,freeze_id,ticket_id,generation,source_state,result_digest,receipt_json,recorded_at) VALUES(?,?,?,?,?,?,?,?)",b.tenantId(),b.freezeId(),b.ticketId(),current,sourceState,resultDigest,json(value),utc(now));
                execute(c,"UPDATE approval_finance_generation SET state='OPEN' WHERE tenant_id=? AND ticket_id=? AND generation=? AND state='FENCED'",b.tenantId(),b.ticketId(),current);
                audit(c,b,"OWNER_RELEASED",digest,now);
            }
            try{c.commit();}catch(SQLException e){uncertain=true;throw fail(503,"OPERATION_RESULT_UNKNOWN");}return Collections.unmodifiableMap(receipt);
        }catch(SQLException e){try{c.rollback();}catch(SQLException rollback){uncertain=true;}throw fail(503,"DEPENDENCY_UNAVAILABLE");}
        catch(RuntimeException|Error e){if(!uncertain)try{c.rollback();}catch(SQLException rollback){uncertain=true;}throw e;}
        finally{if(uncertain)invalidate(c);else try{c.close();}catch(SQLException e){invalidate(c);}}
    }

    /** Hook FIRST in the existing business transaction, before operation/application locks. Never substitutes current authorization. */
    public static void lockCurrentPaidMutation(Connection c,String tenant,UUID ticket,String actor,UUID operation,String action,String commandDigest)throws SQLException{
        lockCurrentMutation(c,tenant,ticket,actor,operation,action,commandDigest,"CUSTOMER_PAID");
    }
    public static void lockCurrentMutation(Connection c,String tenant,UUID ticket,String actor,UUID operation,String action,String commandDigest,String mode)throws SQLException{
        if(c.getAutoCommit())throw fail(500,"OWNER_TRANSACTION_REQUIRED");
        // The generation comes from the locked local row, never browser/default generation zero.
        long current=lock(c,tenant,ticket);
        lockMutation(c,tenant,ticket,current,actor,operation,action,commandDigest,mode);
    }
    /** Explicit-generation primitive for owner adapters/tests; no source/global ACK is inferred. */
    public static void lockPaidMutation(Connection c,String tenant,UUID ticket,long expectedGeneration,String actor,UUID operation,String action,String commandDigest)throws SQLException{
        lockMutation(c,tenant,ticket,expectedGeneration,actor,operation,action,commandDigest,"CUSTOMER_PAID");
    }
    private static void lockMutation(Connection c,String tenant,UUID ticket,long expectedGeneration,String actor,UUID operation,String action,String commandDigest,String mode)throws SQLException{
        positive(tenant);positive(actor);Objects.requireNonNull(ticket);Objects.requireNonNull(operation);
        if(c.getAutoCommit())throw fail(500,"OWNER_TRANSACTION_REQUIRED");
        if(expectedGeneration<0||!Set.of("SUBMIT","RESUBMIT","DECIDE","DECIDE_REJECT","WITHDRAW").contains(action)||commandDigest==null||!commandDigest.matches("[0-9a-f]{64}"))throw fail(400,"INVALID_PAID_INTENT");
        long generation=lock(c,tenant,ticket);
        String state=one(c,"SELECT state FROM approval_finance_generation WHERE tenant_id=? AND ticket_id=?",tenant,ticket);
        if(generation!=expectedGeneration||!"OPEN".equals(state)&&!("FENCED".equals(state)&&Set.of("WITHDRAW","DECIDE_REJECT").contains(action)))throw fail(409,"FINANCE_GENERATION_FENCED");
        if(!Set.of("CUSTOMER_PAID","UNKNOWN").contains(mode))throw fail(400,"INVALID_GUARDED_POLICY");
        String prior=one(c,"SELECT CONCAT(ticket_id,':',generation,':',action,':',command_digest,':',charge_mode) FROM approval_finance_write_intent WHERE tenant_id=? AND actor_id=? AND operation_id=?",tenant,actor,operation);
        String expected=ticket+":"+generation+":"+action+":"+commandDigest+":"+mode;
        if(prior!=null){if(!prior.equals(expected))throw fail(409,"OPERATION_BINDING_CONFLICT");return;}
        execute(c,"INSERT INTO approval_finance_write_intent(tenant_id,actor_id,operation_id,ticket_id,generation,action,charge_mode,command_digest,recorded_at) VALUES(?,?,?,?,?,?,?,?,?)",tenant,actor,operation,ticket,generation,action,mode,commandDigest,utc(Instant.now().truncatedTo(ChronoUnit.MILLIS)));
    }
    private static long lock(Connection c,String tenant,UUID ticket)throws SQLException{
        execute(c,"INSERT INTO approval_finance_generation(tenant_id,ticket_id,generation,state) VALUES(?,?,0,'OPEN') ON DUPLICATE KEY UPDATE generation=generation",tenant,ticket);
        return Long.parseLong(one(c,"SELECT generation FROM approval_finance_generation WHERE tenant_id=? AND ticket_id=? FOR UPDATE",tenant,ticket));
    }
    private static Map<String,Object> snapshot(Connection c,Binding b)throws SQLException{
        var rows=new ArrayList<Object>();var blockers=new ArrayList<String>();
        try(var p=c.prepareStatement("SELECT a.id,a.state,a.row_version,a.snapshot,s.company_id,s.round_id,s.assignment_id,s.command_digest,e.charge_mode FROM parts_application a JOIN approval_source_reference s ON s.tenant_id=a.tenant_id AND s.application_id=a.id LEFT JOIN approval_execution_policy e ON e.tenant_id=a.tenant_id AND e.application_id=a.id WHERE a.tenant_id=? AND s.ticket_id=? ORDER BY a.id")){
            bind(p,b.tenantId(),b.ticketId());try(var r=p.executeQuery()){while(r.next()){
                String mode=r.getString(9),state=r.getString(2),id=r.getString(1);if("FREE".equals(mode))continue;
                var row=new TreeMap<String,Object>();row.put("applicationId",id);row.put("state",state);row.put("version",r.getString(3));row.put("snapshot",map(r.getString(4)));
                row.put("companyId",r.getString(5));row.put("roundId",r.getString(6));row.put("assignmentId",r.getString(7));row.put("commandDigest",r.getString(8));row.put("chargeMode",mode==null?"UNKNOWN":mode);rows.add(row);
                if(!b.companyId().equals(r.getString(5)))blockers.add("COMPANY_BINDING_UNRESOLVED:"+id);
                if(Set.of("PENDING","WAITING_CONFIGURATION").contains(state)||mode==null&&"APPROVED".equals(state))blockers.add("UNSETTLED_APPLICATION:"+id);
            }}
        }
        try(var p=c.prepareStatement("SELECT i.actor_id,i.operation_id,i.generation,i.action,i.command_digest,o.receipt,i.charge_mode,a.state,a.row_version,a.snapshot FROM approval_finance_write_intent i LEFT JOIN approval_operation o ON o.tenant_id=i.tenant_id AND o.actor_id=i.actor_id AND o.operation_id=i.operation_id LEFT JOIN parts_application a ON a.tenant_id=o.tenant_id AND a.id=o.target_id WHERE i.tenant_id=? AND i.ticket_id=? ORDER BY i.actor_id,i.operation_id")){
            bind(p,b.tenantId(),b.ticketId());try(var r=p.executeQuery()){while(r.next()){
                var row=new TreeMap<String,Object>();row.put("actorId",r.getString(1));row.put("operationId",r.getString(2));row.put("generation",r.getString(3));row.put("action",r.getString(4));row.put("commandDigest",r.getString(5));String receipt=r.getString(6);row.put("receipt",receipt==null?null:map(receipt));rows.add(row);
                if(receipt==null)blockers.add("UNCONFIRMED_PAID_OPERATION:"+r.getString(2));
                row.put("chargeMode",r.getString(7));row.put("currentApplicationState",r.getString(8));
                row.put("currentApplicationVersion",r.getString(9));row.put("currentApplicationSnapshot",r.getString(10)==null?null:map(r.getString(10)));
                if("UNKNOWN".equals(r.getString(7))&&(r.getString(8)==null||Set.of("PENDING","WAITING_CONFIGURATION","APPROVED").contains(r.getString(8))))blockers.add("UNRESOLVED_UNKNOWN_OPERATION:"+r.getString(2));
            }}
        }
        var feeFacts=FeeApprovalPersistence.coverage(c,b);rows.addAll(feeFacts);
        for(Object value:feeFacts){@SuppressWarnings("unchecked")var fact=(Map<String,Object>)value;@SuppressWarnings("unchecked")var app=(Map<String,Object>)fact.get("feeApplication");var state=Objects.toString(app.get("state"));if(fact.get("termination")==null&&!Set.of("COMPLETED","REJECTED","WITHDRAWN","TERMINATED").contains(state))blockers.add("UNSETTLED_FEE_APPLICATION:"+app.get("id"));}
        return Map.of("scope",Map.of("tenantId",b.tenantId(),"ticketId",b.ticketId().toString(),"coveragePolicy","PAID_UNKNOWN_PARTS_AND_FEE_APPLICATIONS"),"facts",rows,"blockers",blockers);
    }
    private static java.time.LocalDateTime utc(Instant instant){return java.time.LocalDateTime.ofInstant(instant,java.time.ZoneOffset.UTC);}
    private static void audit(Connection c,Binding b,String event,String digest,Instant now)throws SQLException{execute(c,"INSERT INTO approval_finance_fence_audit(audit_id,tenant_id,freeze_id,event_type,binding_digest,recorded_at) VALUES(?,?,?,?,?,?)",UUID.randomUUID(),b.tenantId(),b.freezeId(),event,digest,utc(now));}
    private void invalidate(Connection c){try{if(source instanceof HikariDataSource h){h.evictConnection(c);return;}c.abort(Runnable::run);}catch(Exception e){quarantined=true;/* Never close/reset an uncertain connection; stop reuse through this owner instance. */}}
    private static void bind(PreparedStatement p,Object...v)throws SQLException{for(int i=0;i<v.length;i++)p.setObject(i+1,v[i] instanceof UUID?v[i].toString():v[i]);}
    private static void execute(Connection c,String sql,Object...v)throws SQLException{try(var p=c.prepareStatement(sql)){bind(p,v);p.executeUpdate();}}
    private static String one(Connection c,String sql,Object...v)throws SQLException{try(var p=c.prepareStatement(sql)){bind(p,v);try(var r=p.executeQuery()){return r.next()?r.getString(1):null;}}}
    private static long number(String s){try{long n=Long.parseLong(s);if(n<0||!Long.toString(n).equals(s))throw new NumberFormatException();return n;}catch(Exception e){throw fail(400,"INVALID_DECIMAL");}}
    private static void positive(String s){if(number(s)==0)throw fail(400,"INVALID_POSITIVE_DECIMAL");}
    private static ApprovalFailure fail(int status,String code){return new ApprovalFailure(status,code);}
    public static Map<String,Object> wire(Binding b){return Map.ofEntries(Map.entry("tenantId",b.tenantId()),Map.entry("actorUserId",b.actorUserId()),Map.entry("companyId",b.companyId()),Map.entry("faultRelationId",b.faultRelationId().toString()),Map.entry("ticketId",b.ticketId().toString()),Map.entry("roundId",b.roundId().toString()),Map.entry("groupId",b.groupId().toString()),Map.entry("expectedGroupVersion",b.expectedGroupVersion()),Map.entry("quoteId",b.quoteId().toString()),Map.entry("operationId",b.sourceOperationId().toString()),Map.entry("commandDigest",b.commandDigest()),Map.entry("freezeId",b.freezeId().toString()),Map.entry("generation",b.generation()),Map.entry("ticketFenceDigest",b.ticketFenceDigest()));}
    private static String json(Object value){if(value instanceof Binding b)return json(wire(b));return JSON.writeValueAsString(canonical(value));}
    @SuppressWarnings("unchecked") private static Map<String,Object> castMap(Object value){return value instanceof Map<?,?>?(Map<String,Object>)value:Map.of();}
    private static Object canonical(Object value){if(value instanceof Map<?,?> m){var result=new TreeMap<String,Object>();m.forEach((k,v)->result.put(k.toString(),canonical(v)));return result;}if(value instanceof List<?> l)return l.stream().map(ApprovalFinanceGeneration::canonical).toList();return value;}
    @SuppressWarnings("unchecked") private static Map<String,Object> map(String json){return JSON.readValue(json,Map.class);}
    private static String sha(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
