package com.ias.ticket;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MaintenanceScopeTest {
    private JdbcTemplate db;
    private Maintenance maintenance;
    private final Instant now = Instant.now();

    @BeforeEach
    void prepare() {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:maintenance_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        db = new JdbcTemplate(source);
        db.execute("CREATE TABLE ticket_create_operation(operation_id VARCHAR(36) PRIMARY KEY, state VARCHAR(20), created_at TIMESTAMP, updated_at TIMESTAMP, lease_until TIMESTAMP)");
        db.execute("CREATE TABLE ticket_file(id VARCHAR(36) PRIMARY KEY, submission_id VARCHAR(36), state VARCHAR(20), created_at TIMESTAMP, updated_at TIMESTAMP, lease_until TIMESTAMP)");
        db.execute("CREATE TABLE ticket_file_reference(file_id VARCHAR(36))");
        db.execute("CREATE TABLE ticket_finance_voucher(file_id VARCHAR(36))");
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        maintenance = new Maintenance(new Tickets(db, transactions), null);
    }

    @AfterEach
    void close() {
        db.execute("SHUTDOWN");
    }

    @Test
    void scopedActiveUploadIsFoundAfterOneThousandOlderFiles() {
        olderFiles();
        String target = UUID.randomUUID().toString();
        insertFile(target, "UPLOADING", now, now.plusSeconds(120));

        var actions = maintenance.run(now.plusSeconds(86401), false, Set.of(target));

        assertAction(actions, "fileId", target, "KEEP_ACTIVE_UPLOAD");
    }

    @Test
    void scopedCommittedOperationIsFoundAfterOneThousandOlderOperations() {
        for (int index = 0; index < 1000; index++) {
            insertOperation(UUID.randomUUID().toString(), now.minusSeconds(86400 + index));
        }
        String target = UUID.randomUUID().toString();
        insertOperation(target, now);

        var actions = maintenance.run(now, false, Set.of(target));

        assertAction(actions, "operationId", target, "CONFIRM_COMMITTED");
    }

    @Test
    void scopedReadyFileIsReconciledAfterOneThousandOlderFiles() {
        olderFiles();
        String target = UUID.randomUUID().toString();
        insertFile(target, "READY", now, null);

        var actions = maintenance.run(now, false, Set.of(target));

        assertAction(actions, "fileId", target, "MISSING_BINARY_REQUIRES_RECONCILIATION");
    }

    @Test
    void explicitScopeCanSelectMoreThanOneThousandOperations() {
        var selected = new HashSet<String>();
        for (int index = 0; index < 1001; index++) {
            String id = UUID.randomUUID().toString();
            selected.add(id);
            insertOperation(id, now.minusSeconds(index));
        }

        var actions = maintenance.run(now, false, selected);

        assertEquals(1001, actions.stream().filter(value -> "CONFIRM_COMMITTED".equals(value.get("action"))).count());
    }

    @Test
    void unscopedDiscoveryRetainsItsOneThousandOperationBatch() {
        for (int index = 0; index < 1001; index++) {
            insertOperation(UUID.randomUUID().toString(), now.minusSeconds(index));
        }

        var actions = maintenance.run(now, false, Set.of());

        assertEquals(1000, actions.stream().filter(value -> "CONFIRM_COMMITTED".equals(value.get("action"))).count());
    }

    private void olderFiles() {
        for (int index = 0; index < 1000; index++) {
            insertFile(UUID.randomUUID().toString(), "READY", now.minusSeconds(86400 + index), null);
        }
    }

    private void insertFile(String id, String state, Instant created, Instant lease) {
        db.update("INSERT INTO ticket_file VALUES(?,?,?,?,?,?)", id, UUID.randomUUID().toString(), state,
                Timestamp.from(created), Timestamp.from(created), lease == null ? null : Timestamp.from(lease));
    }

    private void insertOperation(String id, Instant created) {
        db.update("INSERT INTO ticket_create_operation VALUES(?,'COMMITTED',?,?,NULL)", id,
                Timestamp.from(created), Timestamp.from(created));
    }

    private void assertAction(List<Map<String, Object>> actions, String field, String id, String action) {
        assertTrue(actions.stream().anyMatch(value -> id.equals(value.get(field)) && action.equals(value.get("action"))),
                "Explicitly selected target must be processed beyond the discovery batch");
    }
}
