package com.ias.approval;

import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import tools.jackson.databind.json.JsonMapper;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static com.ias.approval.FeeApproval.*;

/** JDBC candidate, deliberately not a Spring bean or HTTP endpoint. No production authority exists by default. */
public final class FeeApprovalStore {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public interface Authority {
        /** Verify current identity/scope, immutable F quote/basis and W/P/S completed barrier. Called under owner lock. */
        Facts verify(Connection connection,Actor actor,Command command,Application current)throws SQLException;
        /** Current object-read permission, even for the original actor querying an immutable historical receipt. */
        void read(Connection connection,Actor actor,Application current)throws SQLException;
        default void persisted(Connection connection,Actor actor,Command command,Application previous,Application next,UUID eventId,String commandDigest)throws SQLException {}
    }
    public static final Authority UNAVAILABLE=new Authority(){
        public Facts verify(Connection c,Actor a,Command cmd,Application app){throw fail(503,"FEE_AUTHORITY_UNAVAILABLE");}
        public void read(Connection c,Actor a,Application app){throw fail(503,"FEE_AUTHORITY_UNAVAILABLE");}
    };
    private final DataSource source;private final Authority authority;private final Clock clock;
    private volatile boolean quarantined;
    public FeeApprovalStore(DataSource source){this(source,UNAVAILABLE,Clock.systemUTC());}
    public FeeApprovalStore(DataSource source,Authority authority,Clock clock){this.source=Objects.requireNonNull(source);this.authority=Objects.requireNonNull(authority);this.clock=Objects.requireNonNull(clock);}

    public Application execute(Actor actor,Command command){
        if(actor.tenantId()!=command.basis().tenantId())throw fail(403,"TENANT_MISMATCH");
        String commandJson=json(command),digest=sha(commandJson);
        return transact(c->{
            // Same physical generation row as P prepare/reconcile; generation -> application -> operation lock order.
            lockGeneration(c,actor.tenantId(),command.basis().ticketId());
            Application old=load(c,actor.tenantId(),command.applicationId(),true);
            Saved saved=saved(c,actor,command.operationId());
            if(saved!=null){
                if(!saved.digest().equals(digest)||!saved.commandJson().equals(commandJson))throw fail(409,"OPERATION_BINDING_CONFLICT");
                if(old==null)throw fail(503,"RECEIPT_APPLICATION_MISSING");authority.read(c,actor,old);return parse(saved.receipt(),Application.class);
            }
            Facts facts=authority.verify(c,actor,command,old);
            if(facts==null)throw fail(503,"FEE_AUTHORITY_UNAVAILABLE");
            boolean cleanup=command.action()==Action.WITHDRAW||command.action()==Action.REJECT;
            try(var p=c.prepareStatement("SELECT generation,state FROM approval_finance_generation WHERE tenant_id=? AND ticket_id=?")){
                bind(p,actor.tenantId(),command.basis().ticketId());try(var r=p.executeQuery()){r.next();
                    if(r.getLong(1)!=command.basis().generation()||!"OPEN".equals(r.getString(2))&&!cleanup)throw fail(409,"FINANCE_GENERATION_FENCED");
                }
            }
            Application next=FeeApproval.apply(old,actor,command,facts);String snapshot=json(next);Timestamp time=Timestamp.valueOf(LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
            if(old==null){
                execute(c,"INSERT INTO fee_approval_application(tenant_id,id,ticket_id,group_id,quote_id,quote_version,applicant_id,state,row_version,snapshot,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",actor.tenantId(),next.id(),next.basis().ticketId(),next.basis().groupId(),next.basis().quoteId(),next.basis().quoteVersion(),next.applicantId(),next.state(),next.version(),snapshot,time,time);
            }else if(execute(c,"UPDATE fee_approval_application SET state=?,row_version=?,snapshot=?,updated_at=? WHERE tenant_id=? AND id=? AND row_version=?",next.state(),next.version(),snapshot,time,actor.tenantId(),next.id(),old.version())!=1)throw fail(409,"VERSION_CONFLICT");
            execute(c,"INSERT INTO fee_approval_operation(tenant_id,actor_id,operation_id,application_id,command_digest,command_json,receipt,created_at) VALUES(?,?,?,?,?,?,?,?)",actor.tenantId(),actor.userId(),command.operationId(),next.id(),digest,commandJson,snapshot,time);
            UUID event=UUID.randomUUID();
            execute(c,"INSERT INTO fee_approval_audit(event_id,tenant_id,actor_id,operation_id,application_id,action,resulting_version,command_digest,occurred_at) VALUES(?,?,?,?,?,?,?,?,?)",event,actor.tenantId(),actor.userId(),command.operationId(),next.id(),command.action(),next.version(),digest,time);
            Map<String,Object> envelope=new TreeMap<>();envelope.put("eventId",event);envelope.put("producer","approval");envelope.put("type","FEE_APPROVAL_"+next.state());envelope.put("operationId",command.operationId());envelope.put("actor",actor);envelope.put("application",next);envelope.put("commandDigest",digest);
            execute(c,"INSERT INTO fee_approval_outbox(event_id,tenant_id,application_id,aggregate_version,event_type,envelope,created_at) VALUES(?,?,?,?,?,?,?)",event,actor.tenantId(),next.id(),next.version(),"FEE_APPROVAL_"+next.state(),json(envelope),time);
            authority.persisted(c,actor,command,old,next,event,digest);
            return next;
        });
    }

    public Application query(Actor actor,UUID operation){return transact(c->{
        Saved saved=saved(c,actor,operation);if(saved==null)throw fail(404,"FEE_OPERATION_NOT_FOUND");
        Application historical=parse(saved.receipt(),Application.class),current=load(c,actor.tenantId(),historical.id(),false);
        if(current==null)throw fail(503,"RECEIPT_APPLICATION_MISSING");authority.read(c,actor,current);return historical;
    });}
    public Application detail(Actor actor,UUID application){return transact(c->{Application app=load(c,actor.tenantId(),application,false);if(app==null)throw fail(404,"FEE_APPLICATION_NOT_FOUND");authority.read(c,actor,app);return app;});}
    Application local(long tenant,UUID id){return transact(c->load(c,tenant,id,false));}
    Application localOperation(Actor actor,UUID operation){return transact(c->{var saved=saved(c,actor,operation);return saved==null?null:parse(saved.receipt(),Application.class);});}
    List<Application> localPage(long tenant,UUID ticket,int page,int size){return transact(c->{var result=new ArrayList<Application>();try(var p=c.prepareStatement("SELECT snapshot FROM fee_approval_application WHERE tenant_id=? AND ticket_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?")){bind(p,tenant,ticket,size,(page-1)*size);try(var r=p.executeQuery()){while(r.next())result.add(FeeApprovalPersistence.current(c,parse(r.getString(1),Application.class)));}}return List.copyOf(result);});}
    private record Saved(String digest,String commandJson,String receipt){}
    private Saved saved(Connection c,Actor a,UUID operation)throws SQLException{
        try(var p=c.prepareStatement("SELECT command_digest,command_json,receipt FROM fee_approval_operation WHERE tenant_id=? AND actor_id=? AND operation_id=?")){bind(p,a.tenantId(),a.userId(),operation);try(var r=p.executeQuery()){return r.next()?new Saved(r.getString(1),r.getString(2),r.getString(3)):null;}}
    }
    private Application load(Connection c,long tenant,UUID id,boolean lock)throws SQLException{
        try(var p=c.prepareStatement("SELECT snapshot FROM fee_approval_application WHERE tenant_id=? AND id=?"+(lock?" FOR UPDATE":""))){bind(p,tenant,id);try(var r=p.executeQuery()){return r.next()?FeeApprovalPersistence.current(c,parse(r.getString(1),Application.class)):null;}}
    }
    private void lockGeneration(Connection c,long tenant,UUID ticket)throws SQLException{
        execute(c,"INSERT INTO approval_finance_generation(tenant_id,ticket_id,generation,state) VALUES(?,?,0,'OPEN') ON DUPLICATE KEY UPDATE generation=generation",tenant,ticket);
        try(var p=c.prepareStatement("SELECT generation FROM approval_finance_generation WHERE tenant_id=? AND ticket_id=? FOR UPDATE")){bind(p,tenant,ticket);try(var r=p.executeQuery()){if(!r.next())throw fail(503,"GENERATION_MISSING");}}
    }
    @FunctionalInterface private interface Work<T>{T apply(Connection c)throws SQLException;}
    private <T>T transact(Work<T> work){
        if(quarantined)throw fail(503,"FEE_CONNECTION_QUARANTINED");Connection c;try{c=source.getConnection();}catch(SQLException e){throw fail(503,"DEPENDENCY_UNAVAILABLE");}
        boolean uncertain=false;
        try{c.setAutoCommit(false);T result=work.apply(c);try{c.commit();}catch(SQLException e){uncertain=true;throw fail(503,"OPERATION_RESULT_UNKNOWN");}return result;}
        catch(SQLException e){try{c.rollback();}catch(SQLException rollback){uncertain=true;throw unknown(e,rollback);}var failure=fail(e.getSQLState()!=null&&e.getSQLState().startsWith("23")?409:503,e.getSQLState()!=null&&e.getSQLState().startsWith("23")?"FEE_PERSISTENCE_CONFLICT":"DEPENDENCY_UNAVAILABLE");failure.initCause(e);throw failure;}
        catch(RuntimeException|Error e){if(!uncertain)try{c.rollback();}catch(SQLException rollback){uncertain=true;throw unknown(e,rollback);}throw e;}
        finally{if(uncertain)invalidate(c);else try{c.close();}catch(SQLException e){invalidate(c);}}
    }
    private static ApprovalFailure unknown(Throwable original,SQLException rollback){var failure=fail(503,"OPERATION_RESULT_UNKNOWN");failure.initCause(original);failure.addSuppressed(rollback);return failure;}
    private void invalidate(Connection c){if(source instanceof HikariDataSource h){try{h.evictConnection(c);}catch(RuntimeException ignored){}}else quarantined=true;try{c.abort(Runnable::run);}catch(Exception ignored){}try{c.close();}catch(Exception ignored){}}
    private static int execute(Connection c,String sql,Object...values)throws SQLException{try(var p=c.prepareStatement(sql)){bind(p,values);return p.executeUpdate();}}
    private static void bind(PreparedStatement p,Object...values)throws SQLException{for(int i=0;i<values.length;i++){Object v=values[i];p.setObject(i+1,v instanceof UUID||v instanceof Enum<?>?v.toString():v);}}
    private static String json(Object o){return JSON.writeValueAsString(o);}
    private static <T>T parse(String value,Class<T> type){return JSON.readValue(value,type);}
    private static String sha(String s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
