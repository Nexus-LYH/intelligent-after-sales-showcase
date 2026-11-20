package com.ias.approval;

import tools.jackson.databind.json.JsonMapper;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import com.ias.notification.DomainEvent;
import com.ias.notification.JdbcDelivery;
import java.util.function.Function;
import static com.ias.approval.PartsApproval.*;

/** No restore-autocommit on error. Uncertain commit/rollback physically invalidates its connection. */
public final class JdbcApprovalStore implements ApprovalStore {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final DateTimeFormatter UTC_MILLIS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);
    private final DataSource source;
    public JdbcApprovalStore(DataSource source) { this.source = source; }

    @Override public <T> T transact(Function<Transaction, T> work) {
        Connection connection;
        try { connection = source.getConnection(); }
        catch (SQLException e) { throw new ApprovalFailure(503, "DEPENDENCY_UNAVAILABLE"); }
        boolean started = false;
        boolean unusable = false;
        try {
            connection.setAutoCommit(false); started = true;
            T result = work.apply(new JdbcTransaction(connection));
            try { connection.commit(); }
            catch (SQLException e) {
                unusable = true;
                throw new ApprovalFailure(503, "OPERATION_RESULT_UNKNOWN");
            }
            return result;
        } catch (RuntimeException | Error failure) {
            if (started && !unusable) {
                try { if (!connection.isClosed()) connection.rollback(); }
                catch (SQLException rollbackFailure) { unusable = true; }
            } else unusable = true;
            throw failure;
        } catch (SQLException failure) {
            unusable = true;
            throw new ApprovalFailure(503, "DEPENDENCY_UNAVAILABLE");
        } finally {
            if (unusable) invalidate(connection);
            else try { connection.close(); } catch (SQLException closeFailure) { invalidate(connection); }
        }
    }
    private void invalidate(Connection connection) {
        if (source instanceof HikariDataSource hikari) {
            try { hikari.evictConnection(connection); return; }
            catch (RuntimeException ignored) { /* fall through to JDBC physical abort */ }
        }
        try { connection.abort(Runnable::run); }
        catch (SQLException | RuntimeException ignored) { /* quarantine: never return this connection via close */ }
    }

    private static final class JdbcTransaction implements Transaction {
        private final Connection connection;
        JdbcTransaction(Connection connection) { this.connection = connection; }
        @Override public Receipt peekReceipt(String tenant,String actor,UUID operation){return readReceipt(tenant,actor,operation,false);}
        @Override public Receipt receipt(String tenant, String actor, UUID operation) {
            return readReceipt(tenant,actor,operation,true);
        }
        private Receipt readReceipt(String tenant,String actor,UUID operation,boolean lock){
            try (var statement = prepare("SELECT command_hash,action,target_id,receipt FROM approval_operation WHERE tenant_id=? AND actor_id=? AND operation_id=?"+(lock?" FOR UPDATE":""),
                    tenant, actor, operation)) {
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) return null;
                    if (rows.getString("receipt") == null) throw new ApprovalFailure(503, "OPERATION_RESULT_UNKNOWN");
                    return new Receipt(rows.getString("command_hash"), rows.getString("action"),
                            UUID.fromString(rows.getString("target_id")), JSON.readValue(rows.getString("receipt"), Application.class));
                }
            } catch (SQLException e) { throw databaseFailure(); }
        }
        @Override public void guardMutation(String tenant,String actor,UUID operation,String digest,String action,String ticket,String mode,UUID target){
            if(target!=null){
                // Policy/source bindings are immutable. Locate scope before acquiring application locks.
                try(var p=prepare("SELECT e.charge_mode,s.ticket_id,a.snapshot FROM parts_application a LEFT JOIN approval_execution_policy e ON e.tenant_id=a.tenant_id AND e.application_id=a.id LEFT JOIN approval_source_reference s ON s.tenant_id=a.tenant_id AND s.application_id=a.id WHERE a.tenant_id=? AND a.id=?",tenant,target);var r=p.executeQuery()){
                    if(!r.next())throw new ApprovalFailure(404,"TARGET_NOT_ACCESSIBLE");mode=r.getString(1);ticket=r.getString(2);
                    if(ticket==null)ticket=JSON.readValue(r.getString(3),Application.class).submission().ticketId();
                }catch(SQLException e){throw databaseFailure();}
            }
            if("FREE".equals(mode))return;
            if(mode!=null&&!"CUSTOMER_PAID".equals(mode)||ticket==null)throw new ApprovalFailure(503,"EXECUTION_POLICY_UNRESOLVED");
            try{ApprovalFinanceGeneration.lockCurrentMutation(connection,tenant,UUID.fromString(ticket),actor,operation,action,digest,mode==null?"UNKNOWN":mode);}
            catch(SQLException e){throw databaseFailure();}
            catch(IllegalArgumentException e){throw new ApprovalFailure(503,"SOURCE_BINDING_UNRESOLVED");}
        }
        @Override public void reserve(String tenant, String actor, UUID operation, String hash, String action) {
            execute("INSERT INTO approval_operation(tenant_id,actor_id,operation_id,command_hash,action,created_at) VALUES(?,?,?,?,?,UTC_TIMESTAMP(3))",
                    tenant, actor, operation, hash, action);
        }
        @Override public Application application(String tenant, UUID id) {
            try (var statement = prepare("SELECT snapshot FROM parts_application WHERE tenant_id=? AND id=? FOR UPDATE", tenant, id)) {
                try (var rows = statement.executeQuery()) {
                    if (!rows.next()) throw new ApprovalFailure(404, "TARGET_NOT_ACCESSIBLE");
                    return JSON.readValue(rows.getString("snapshot"), Application.class);
                }
            } catch (SQLException e) { throw databaseFailure(); }
        }
        @Override public void insert(Application application) {
            execute("INSERT INTO parts_application(id,tenant_id,applicant_id,root_application_id,revision_number,previous_application_id,state,row_version,snapshot,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))",
                    application.id(), application.submission().tenantId(), application.submission().applicantId(),
                    application.revision().rootApplicationId(), application.revision().number(), application.revision().previousApplicationId(),
                    application.state().name(), application.version(), JSON.writeValueAsString(application));
        }
        @Override public void bindSource(Application a,ApprovalAccess.SourceReference reference,String digest){
            execute("INSERT INTO approval_source_reference(tenant_id,application_id,operation_id,actor_id,ticket_id,round_id,assignment_id,company_id,department_id,command_digest,recorded_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                a.submission().tenantId(),a.id(),a.submission().operationId(),a.submission().applicantId(),a.submission().ticketId(),reference.roundId(),a.submission().assignmentId(),reference.companyId(),a.submission().departmentId(),digest,java.time.LocalDateTime.ofInstant(Instant.parse(reference.recordedAt()),ZoneOffset.UTC));
        }
        @Override public void bindExecutionPolicy(Application a,String mode){if(mode!=null)execute("INSERT INTO approval_execution_policy(tenant_id,application_id,charge_mode,operation_id,recorded_at) VALUES(?,?,?,?,UTC_TIMESTAMP(3))",a.submission().tenantId(),a.id(),mode,a.submission().operationId());}
        @Override public void update(Application previous, Application result) {
            int count = execute("UPDATE parts_application SET state=?,row_version=?,snapshot=?,updated_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND id=? AND row_version=?",
                    result.state().name(), result.version(), JSON.writeValueAsString(result), previous.submission().tenantId(), previous.id(), previous.version());
            if (count != 1) throw new ApprovalFailure(409, "VERSION_CONFLICT");
        }
        @Override public void complete(String tenant, String actor, UUID operation, String hash, String action,
                                       Application result, String eventType) {
            int updated = execute("UPDATE approval_operation SET target_id=?,receipt=? WHERE tenant_id=? AND actor_id=? AND operation_id=? AND command_hash=? AND receipt IS NULL",
                    result.id(), JSON.writeValueAsString(result), tenant, actor, operation, hash);
            if (updated != 1) throw new ApprovalFailure(409, "IDEMPOTENCY_CONFLICT");
            execute("INSERT INTO approval_audit(audit_id,tenant_id,actor_id,operation_id,target_id,action,resulting_version,command_hash,occurred_at) VALUES(?,?,?,?,?,?,?,?,UTC_TIMESTAMP(3))",
                    UUID.randomUUID(), tenant, actor, operation, result.id(), action, result.version(), hash);
            Instant occurredAt = result.withdrawnAt() != null ? result.withdrawnAt()
                    : result.decidedAt() != null ? result.decidedAt() : result.submittedAt();
            UUID eventId = UUID.randomUUID();
            Map<String, Object> envelope = Map.of("eventId", eventId.toString(), "type", eventType,
                    "schemaVersion", 1, "tenantId", tenant, "aggregateId", result.id().toString(),
                    "aggregateVersion", Long.toString(result.version()), "occurredAt", UTC_MILLIS.format(occurredAt),
                    "producer", "approval", "correlationId", operation.toString(),
                    "payload", Map.of("ticketId", result.submission().ticketId(), "requestId", result.id().toString()));
            DomainEvent event = DomainEvent.decode(JSON.writeValueAsString(envelope));
            execute("INSERT INTO approval_outbox(event_id,tenant_id,aggregate_id,aggregate_version,event_type,envelope,occurred_at) VALUES(?,?,?,?,?,?,?)",
                    eventId, tenant, result.id(), result.version(), eventType, event.encode(),
                    java.time.LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
            try {
                JdbcDelivery.appendOnOwnerTransaction(connection, event, eventType.equals("approval.submitted")?Set.of("notification"):Set.of("notification","ticket"));
            } catch (SQLException failure) { throw databaseFailure(); }
        }
        private PreparedStatement prepare(String sql, Object... arguments) throws SQLException {
            PreparedStatement statement = connection.prepareStatement(sql);
            statement.setQueryTimeout(2);
            try {
                for (int i = 0; i < arguments.length; i++) {
                    Object value = arguments[i];
                    if (value instanceof UUID uuid) value = uuid.toString();
                    statement.setObject(i + 1, value);
                }
                return statement;
            } catch (SQLException failure) { statement.close(); throw failure; }
        }
        private int execute(String sql, Object... arguments) {
            try (var statement = prepare(sql, arguments)) { return statement.executeUpdate(); }
            catch (SQLException failure) { throw databaseFailure(); }
        }
        private ApprovalFailure databaseFailure() { return new ApprovalFailure(503, "OPERATION_RESULT_UNKNOWN"); }
    }
}
