package com.ias.stock;

import javax.sql.DataSource;
import java.sql.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** JDBC inbound slice only; no browser endpoint or implicit trusted identity. */
public final class InboundLedger {
    public record Actor(long tenantId, long userId) {
        public Actor { if (tenantId<=0 || userId<=0) throw new IllegalArgumentException("INVALID_ACTOR"); }
    }
    public record Command(UUID operationId, long warehouseId, long partId, long quantity,
                          String source, String batchNote, UUID evidenceFileId) {
        public Command {
            Objects.requireNonNull(operationId); Objects.requireNonNull(evidenceFileId);
            if(warehouseId<=0 || partId<=0 || quantity<=0) throw new IllegalArgumentException("INVALID_QUANTITY_OR_OBJECT");
            for(String value : new String[]{source,batchNote})
                if(value==null || value.isBlank() || value.length()>500) throw new IllegalArgumentException("INVALID_DESCRIPTION");
        }
    }
    /** Adapter MUST resolve current warehouse scope and private evidence ownership server-side. */
    @FunctionalInterface public interface Access {
        void requireCurrentInbound(Actor actor, Command command);
    }
    public record Receipt(UUID operationId, long onHand, Instant recordedAt) {}
    private final DataSource source;
    private final Access access;
    private final List<Connection> quarantined=Collections.synchronizedList(new ArrayList<>());
    public InboundLedger(DataSource source, Access access) {
        this.source=Objects.requireNonNull(source); this.access=Objects.requireNonNull(access);
    }
    /** Pure original-result query: never repairs, replays or creates an inbound fact. */
    public Optional<Receipt> find(Actor actor, UUID operationId) throws SQLException {
        Objects.requireNonNull(actor); Objects.requireNonNull(operationId);
        Command command; Receipt result;
        try (Connection db=source.getConnection(); PreparedStatement q=db.prepareStatement(
                "SELECT o.warehouse_id,o.part_id,o.quantity,m.source_text,m.batch_note,m.evidence_file_id,o.resulting_on_hand,o.recorded_at FROM stock_operation o JOIN stock_movement m ON m.tenant_id=o.tenant_id AND m.operation_id=o.operation_id WHERE o.tenant_id=? AND o.actor_id=? AND o.operation_id=?")) {
            q.setLong(1,actor.tenantId()); q.setLong(2,actor.userId()); q.setString(3,operationId.toString());
            try (ResultSet rs=q.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                command=new Command(operationId,rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getString(4),rs.getString(5),UUID.fromString(rs.getString(6)));
                result=new Receipt(operationId,rs.getLong(7),rs.getTimestamp(8,utc()).toInstant());
            }
        }
        // Immutable fact read needs no held pool connection during online authorization.
        access.requireCurrentInbound(actor,command);
        return Optional.of(result);
    }
    public Receipt receive(Actor actor, Command command) throws SQLException {
        Objects.requireNonNull(actor); Objects.requireNonNull(command);
        access.requireCurrentInbound(actor,command); // replay never bypasses current object/evidence authorization
        if(!quarantined.isEmpty()) throw new SQLException("UNCERTAIN_CONNECTION_REQUIRES_OPERATOR_RECONCILIATION");
        Connection db=source.getConnection();boolean safeToClose=true;boolean commitAttempted=false;
        try {
            if(!db.getAutoCommit()) throw new SQLException("DEDICATED_AUTOCOMMIT_CONNECTION_REQUIRED");
            db.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            db.setAutoCommit(false);
            try {
                // Stable object ordering also serializes first balance creation; no unlocked read-modify-write.
                active(db,"stock_warehouse",actor.tenantId(),command.warehouseId());
                active(db,"stock_part",actor.tenantId(),command.partId());
                String hash=hash(command);
                try(PreparedStatement q=db.prepareStatement("SELECT actor_id,request_hash,resulting_on_hand,recorded_at FROM stock_operation WHERE tenant_id=? AND operation_id=?")) {
                    q.setLong(1,actor.tenantId());q.setString(2,command.operationId().toString());
                    try(ResultSet rs=q.executeQuery()) {
                        if(rs.next()) {
                            if(rs.getLong(1)!=actor.userId() || !rs.getString(2).equals(hash)) throw new IllegalStateException("IDEMPOTENCY_CONFLICT");
                            Receipt result=new Receipt(command.operationId(),rs.getLong(3),rs.getTimestamp(4,utc()).toInstant());
                            commitAttempted=true;db.commit();return result;
                        }
                    }
                }
                long before=0,version=-1;
                try(PreparedStatement q=db.prepareStatement("SELECT on_hand,reserved,row_version FROM stock_balance WHERE tenant_id=? AND warehouse_id=? AND part_id=? FOR UPDATE")) {
                    key(q,actor,command);try(ResultSet rs=q.executeQuery()){if(rs.next()){before=rs.getLong(1);version=rs.getLong(3);}}
                }
                long after=Math.addExact(before,command.quantity());
                String sql=version<0 ? "INSERT INTO stock_balance(on_hand,reserved,row_version,tenant_id,warehouse_id,part_id) VALUES(?,0,0,?,?,?)"
                        : "UPDATE stock_balance SET on_hand=?,row_version=row_version+1 WHERE tenant_id=? AND warehouse_id=? AND part_id=?";
                try(PreparedStatement q=db.prepareStatement(sql)){q.setLong(1,after);q.setLong(2,actor.tenantId());q.setLong(3,command.warehouseId());q.setLong(4,command.partId());if(q.executeUpdate()!=1)throw new SQLException("BALANCE_WRITE_FAILED");}
                Instant now;try(Statement q=db.createStatement();ResultSet rs=q.executeQuery("SELECT CURRENT_TIMESTAMP(3)")){rs.next();now=rs.getTimestamp(1,utc()).toInstant();}
                Timestamp stamp=Timestamp.from(now);
                try(PreparedStatement q=db.prepareStatement("INSERT INTO stock_operation VALUES(?,?,?,?,?,?,?,?,?)")) {
                    q.setLong(1,actor.tenantId());q.setString(2,command.operationId().toString());q.setLong(3,actor.userId());q.setString(4,hash);q.setLong(5,command.warehouseId());q.setLong(6,command.partId());q.setLong(7,command.quantity());q.setLong(8,after);q.setTimestamp(9,stamp,utc());q.executeUpdate();
                }
                try(PreparedStatement q=db.prepareStatement("INSERT INTO stock_movement VALUES(?,?,?,?,?,?,?,?,?,?)")) {
                    q.setLong(1,actor.tenantId());q.setString(2,command.operationId().toString());q.setLong(3,command.warehouseId());q.setLong(4,command.partId());q.setLong(5,actor.userId());q.setLong(6,command.quantity());q.setString(7,command.source());q.setString(8,command.batchNote());q.setString(9,command.evidenceFileId().toString());q.setTimestamp(10,stamp,utc());q.executeUpdate();
                }
                try(PreparedStatement q=db.prepareStatement("INSERT INTO stock_audit VALUES(?,?,?,?,?)")) {
                    q.setLong(1,actor.tenantId());q.setString(2,command.operationId().toString());q.setLong(3,actor.userId());q.setString(4,"STOCK_RECEIVED");q.setTimestamp(5,stamp,utc());q.executeUpdate();
                }
                // Notification/coordination transport is separate; publish only committed owner facts.
                UUID eventId=UUID.nameUUIDFromBytes(("stock.received:"+actor.tenantId()+":"+command.operationId()).getBytes(StandardCharsets.UTF_8));
                try(PreparedStatement q=db.prepareStatement("INSERT INTO stock_outbox(tenant_id,event_id,operation_id,event_type,recorded_at) VALUES(?,?,?,?,?)")) {
                    q.setLong(1,actor.tenantId());q.setString(2,eventId.toString());q.setString(3,command.operationId().toString());q.setString(4,"STOCK_RECEIVED");q.setTimestamp(5,stamp,utc());q.executeUpdate();
                }
                commitAttempted=true;db.commit();return new Receipt(command.operationId(),after,now);
            } catch(SQLException|RuntimeException ex) {
                boolean rollbackFailed=false;
                try{db.rollback();}catch(SQLException rollback){rollbackFailed=true;ex.addSuppressed(rollback);}
                if(commitAttempted || rollbackFailed) {
                    safeToClose=false;
                    // A failed pool-wrapper abort must never fall through to wrapper.close/reset-auto-commit.
                    try{db.abort(Runnable::run);}catch(SQLException abort){ex.addSuppressed(abort);quarantined.add(db);}
                }
                // Never restore auto-commit after rollback uncertainty or reuse this connection.
                throw ex;
            }
        }finally{if(safeToClose)db.close();}
    }
    private static Calendar utc(){return Calendar.getInstance(TimeZone.getTimeZone("UTC"));}
    private static void key(PreparedStatement q,Actor a,Command c)throws SQLException{q.setLong(1,a.tenantId());q.setLong(2,c.warehouseId());q.setLong(3,c.partId());}
    private static void active(Connection db,String table,long tenant,long id)throws SQLException {
        try(PreparedStatement q=db.prepareStatement("SELECT active FROM "+table+" WHERE tenant_id=? AND id=? FOR UPDATE")) {
            q.setLong(1,tenant);q.setLong(2,id);try(ResultSet rs=q.executeQuery()){if(!rs.next()||!rs.getBoolean(1))throw new SecurityException("TARGET_NOT_ACCESSIBLE");}
        }
    }
    private static String hash(Command c) {
        try {
            // Length-prefix avoids delimiter collisions in human text.
            StringBuilder data=new StringBuilder();
            for(Object value:List.of(c.warehouseId(),c.partId(),c.quantity(),c.source(),c.batchNote(),c.evidenceFileId())) {
                String s=value.toString();data.append(s.length()).append(':').append(s);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data.toString().getBytes(StandardCharsets.UTF_8)));
        }catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
}
