package com.ias.notification;

import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;

/** Durable at-least-once delivery. Business effects and inbox insertion share the supplied local transaction. */
public final class JdbcDelivery {
    public interface Work<T>{T apply(Connection c)throws Exception;}
    public interface Effect{void apply(Connection c,DomainEvent event)throws Exception;}
    public interface Transport{void send(String consumer,DomainEvent event)throws Exception;}
    public interface ReplayAuthorization{boolean currentAllowed(String tenant,long actor,String consumer,DomainEvent event)throws Exception;}
    public record Claim(DomainEvent event,String consumer,UUID lease,int attempt){}
    public enum Ack { COMMITTED, COMMITTED_DUPLICATE }
    public static final class DeliveryFailure extends Exception {
        public DeliveryFailure(String code){super(code);}
    }
    private final DataSource db;private final int maxAttempts;private final Duration lease;
    private static final List<Connection> QUARANTINED=Collections.synchronizedList(new ArrayList<>());
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    public JdbcDelivery(DataSource db,int maxAttempts,Duration lease){
        this.db=Objects.requireNonNull(db);
        // This first slice owns physical connections. Do not return an uncertain transaction to a pool.
        if(!Set.of("com.mysql.cj.jdbc.MysqlDataSource","org.h2.jdbcx.JdbcDataSource").contains(db.getClass().getName()))throw new IllegalArgumentException("UNPOOLED_PHYSICAL_DATASOURCE_REQUIRED");
        if(maxAttempts<1 || maxAttempts>100 || lease.toSeconds()<1 || lease.toSeconds()>600)throw new IllegalArgumentException("INVALID_DELIVERY_POLICY");
        this.maxAttempts=maxAttempts;this.lease=lease;
    }
    public <T>T transaction(Work<T> work)throws Exception {
        return onPhysical(open(),work);
    }
    private Connection open()throws SQLException {
        Connection c=db.getConnection();try(var s=c.createStatement()){
            s.execute(db.getClass().getName().equals("org.h2.jdbcx.JdbcDataSource")?"SET TIME ZONE 'UTC'":"SET time_zone='+00:00'");return c;
        }catch(SQLException failure){try{c.close();}catch(SQLException close){failure.addSuppressed(close);}throw failure;}
    }
    static <T>T onPhysical(Connection c,Work<T> work)throws Exception {
        boolean close=true;
        try {c.setAutoCommit(false);T result=work.apply(c);c.commit();return result;}
        catch(Exception|Error failure){
            try{c.rollback();}catch(Exception rollback){failure.addSuppressed(rollback);
                try{c.abort(Runnable::run);}catch(Exception abort){failure.addSuppressed(abort);close=false;QUARANTINED.add(c);}
            }
            throw failure;
        }finally{if(close)c.close();}
    }
    private static Instant now(Connection c)throws SQLException {try(var s=c.prepareStatement("SELECT CURRENT_TIMESTAMP(3)" );var r=s.executeQuery()){r.next();return r.getTimestamp(1,utc()).toInstant();}}
    private static void requireTx(Connection c)throws SQLException {if(c.getAutoCommit())throw new IllegalArgumentException("OWNER_TRANSACTION_REQUIRED");}
    private static String consumer(String id){if(id==null || !id.matches("[a-z][a-z0-9-]{0,31}"))throw new IllegalArgumentException("INVALID_CONSUMER");return id;}
    private static void update(Connection c,String sql,Object... values)throws SQLException {execute(c,sql,values);}
    private static int execute(Connection c,String sql,Object... values)throws SQLException {try(var s=c.prepareStatement(sql)){for(int i=0;i<values.length;i++){if(values[i] instanceof Timestamp time)s.setTimestamp(i+1,time,utc());else s.setObject(i+1,values[i]);}return s.executeUpdate();}}
    /** Called on the PRODUCER'S already-open business transaction. This method never commits it. */
    public void append(Connection c,DomainEvent event,Set<String> consumers)throws SQLException {
        appendOnOwnerTransaction(c,event,consumers);
    }
    /** Embedded producer adapter; no notification database connection is opened or business identity inferred. */
    public static void appendOnOwnerTransaction(Connection c,DomainEvent event,Set<String> consumers)throws SQLException {
        requireTx(c);if(consumers==null || consumers.isEmpty())throw new IllegalArgumentException("CONSUMERS_REQUIRED");
        for(String id:consumers)if(!event.allowsConsumer(consumer(id)))throw new IllegalArgumentException("CONSUMER_NOT_SUBSCRIBED");
        Instant at=now(c);
        update(c,"INSERT INTO event_outbox(event_id,tenant_id,producer,event_type,envelope_json,envelope_sha,created_at) VALUES(?,?,?,?,?,?,?)",event.eventId().toString(),Long.parseLong(event.tenantId()),event.producer(),event.type(),event.encode(),event.sha(),Timestamp.from(at));
        for(String id:new TreeSet<>(consumers))update(c,"INSERT INTO event_delivery(event_id,consumer_id,state,attempts,next_attempt_at) VALUES(?,?,'PENDING',0,?)",event.eventId().toString(),id,Timestamp.from(at));
    }
    /** Persist a lease BEFORE transport. Expired leases are redelivered with the exact original event. */
    public Optional<Claim> claim()throws Exception {
        return transaction(c->{Instant at=now(c);
            // Lock mutable dispatch state only. A locking join would also lock immutable event_outbox,
            // requiring extra MySQL privileges and undermining the INSERT/SELECT-only outbox boundary.
            try(var s=c.prepareStatement("SELECT d.event_id,d.consumer_id,d.attempts FROM event_delivery d WHERE ((d.state='PENDING' AND d.next_attempt_at<=?) OR (d.state='CLAIMED' AND d.lease_until<=?)) ORDER BY d.next_attempt_at,d.event_id,d.consumer_id LIMIT 1 FOR UPDATE SKIP LOCKED")){
                s.setTimestamp(1,Timestamp.from(at),utc());s.setTimestamp(2,Timestamp.from(at),utc());
                try(var r=s.executeQuery()){if(!r.next())return Optional.empty();String id=r.getString(1),target=r.getString(2);int attempts=r.getInt(3);
                    if(attempts>=maxAttempts){update(c,"UPDATE event_delivery SET state='DEAD',lease_id=NULL,lease_until=NULL,last_error_code='ATTEMPTS_EXHAUSTED' WHERE event_id=? AND consumer_id=?",id,target);return Optional.empty();}
                    String envelope;
                    try(var read=c.prepareStatement("SELECT envelope_json FROM event_outbox WHERE event_id=?")){
                        read.setString(1,id);try(var fact=read.executeQuery()){
                            if(!fact.next())throw new DeliveryFailure("OUTBOX_FACT_MISSING");
                            envelope=fact.getString(1);
                        }
                    }
                    DomainEvent event;
                    try{event=DomainEvent.decode(envelope);}catch(IllegalArgumentException poison){update(c,"UPDATE event_delivery SET state='DEAD',lease_id=NULL,lease_until=NULL,last_error_code='INVALID_ENVELOPE' WHERE event_id=? AND consumer_id=?",id,target);return Optional.empty();}
                    UUID token=UUID.randomUUID();
                    update(c,"UPDATE event_delivery SET state='CLAIMED',attempts=?,lease_id=?,lease_until=? WHERE event_id=? AND consumer_id=?",attempts+1,token.toString(),Timestamp.from(at.plus(lease)),id,target);
                    return Optional.of(new Claim(event,target,token,attempts+1));
                }
            }
        });
    }
    public boolean complete(Claim claim)throws Exception {
        return transaction(c->execute(c,"UPDATE event_delivery SET state='DELIVERED',delivered_at=?,lease_id=NULL,lease_until=NULL,last_error_code=NULL WHERE event_id=? AND consumer_id=? AND state='CLAIMED' AND lease_id=?",Timestamp.from(now(c)),claim.event().eventId().toString(),claim.consumer(),claim.lease().toString())==1);
    }
    public boolean failed(Claim claim)throws Exception {
        return transaction(c->{Instant at=now(c);long seconds=Math.min(300,1L<<Math.min(8,claim.attempt()-1));
            return execute(c,"UPDATE event_delivery SET state=?,next_attempt_at=?,lease_id=NULL,lease_until=NULL,last_error_code='DELIVERY_UNCONFIRMED' WHERE event_id=? AND consumer_id=? AND state='CLAIMED' AND lease_id=?",claim.attempt()>=maxAttempts?"DEAD":"PENDING",Timestamp.from(at.plusSeconds(seconds)),claim.event().eventId().toString(),claim.consumer(),claim.lease().toString())==1;});
    }
    public boolean pumpOne(Transport transport)throws Exception {
        Optional<Claim> next=claim();if(next.isEmpty())return false;Claim claim=next.get();
        try{transport.send(claim.consumer(),claim.event());complete(claim);}catch(Exception e){failed(claim);}
        return true;
    }
    /** trustedProducer is resolved by a verified service identity adapter, never copied from request JSON/header. */
    public Ack consume(String id,String trustedProducer,DomainEvent event,Effect effect)throws Exception {
        consumer(id);Objects.requireNonNull(effect);
        if(!event.producer().equals(trustedProducer)||!event.allowsConsumer(id))throw new DeliveryFailure("PRODUCER_NOT_AUTHORIZED");
        try {
            return transaction(c->{
                update(c,"INSERT INTO event_inbox(consumer_id,event_id,tenant_id,envelope_sha,processed_at) VALUES(?,?,?,?,?)",id,event.eventId().toString(),Long.parseLong(event.tenantId()),event.sha(),Timestamp.from(now(c)));
                effect.apply(c,event);return Ack.COMMITTED;
            });
        } catch(SQLException conflict){
            // Another transaction can win the unique inbox key. Read committed fact on a NEW connection.
            try(Connection c=open();var s=c.prepareStatement("SELECT tenant_id,envelope_sha FROM event_inbox WHERE consumer_id=? AND event_id=?")){
                s.setString(1,id);s.setString(2,event.eventId().toString());
                try(var r=s.executeQuery()){
                    if(r.next()){
                        if(r.getLong(1)!=Long.parseLong(event.tenantId()) || !r.getString(2).equals(event.sha()))throw new DeliveryFailure("EVENT_ID_CONTENT_CONFLICT");
                        return Ack.COMMITTED_DUPLICATE;
                    }
                }
            }
            throw conflict;
        }
    }
    /** Dead-letter replay is idempotent, scoped and audited; immutable event body/time/ID never change. */
    public void replay(UUID operation,String tenant,long actor,UUID eventId,String target,String reason,ReplayAuthorization authorization)throws Exception {
        consumer(target);if(actor<=0 || reason==null || reason.strip().length()<5 || reason.length()>500)throw new IllegalArgumentException("INVALID_REPLAY");
        transaction(c->{
            DomainEvent event;String state;
            try(var s=c.prepareStatement("SELECT o.envelope_json,d.state FROM event_outbox o JOIN event_delivery d ON d.event_id=o.event_id WHERE o.event_id=? AND d.consumer_id=? FOR UPDATE")){
                s.setString(1,eventId.toString());s.setString(2,target);try(var r=s.executeQuery()){if(!r.next())throw new DeliveryFailure("TARGET_NOT_ACCESSIBLE");event=DomainEvent.decode(r.getString(1));state=r.getString(2);}}
            if(!event.tenantId().equals(tenant) || !authorization.currentAllowed(tenant,actor,target,event))throw new DeliveryFailure("REPLAY_NOT_AUTHORIZED");
            try(var s=c.prepareStatement("SELECT tenant_id,event_id,consumer_id,actor_id,reason FROM event_replay_audit WHERE operation_id=?")){
                s.setString(1,operation.toString());try(var r=s.executeQuery()){if(r.next()){if(r.getLong(1)!=Long.parseLong(tenant)||!r.getString(2).equals(eventId.toString())||!r.getString(3).equals(target)||r.getLong(4)!=actor||!r.getString(5).equals(reason))throw new DeliveryFailure("IDEMPOTENCY_CONFLICT");return null;}}
            }
            if(!state.equals("DEAD"))throw new DeliveryFailure("NOT_DEAD_LETTER");Instant at=now(c);
            update(c,"INSERT INTO event_replay_audit(operation_id,tenant_id,event_id,consumer_id,actor_id,reason,replayed_at) VALUES(?,?,?,?,?,?,?)",operation.toString(),Long.parseLong(tenant),eventId.toString(),target,actor,reason,Timestamp.from(at));
            update(c,"UPDATE event_delivery SET state='PENDING',attempts=0,next_attempt_at=?,lease_id=NULL,lease_until=NULL,last_error_code=NULL WHERE event_id=? AND consumer_id=?",Timestamp.from(at),eventId.toString(),target);return null;
        });
    }
    public static void signal(Connection c,DomainEvent event)throws SQLException {
        update(c,"INSERT INTO notification_signal(event_id,tenant_id,producer,event_type,aggregate_id,aggregate_version,occurred_at) VALUES(?,?,?,?,?,?,?)",event.eventId().toString(),Long.parseLong(event.tenantId()),event.producer(),event.type(),event.aggregateId(),new java.math.BigDecimal(event.aggregateVersion()),Timestamp.from(event.occurredAt()));
    }
}
