package com.ias.ticket;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProcessingRecordProjectionTest {
    private final Workflow workflow = new Workflow(null, null);

    private Map<String, Object> record() {
        var row = new HashMap<String, Object>();
        row.put("id", "10000000-0000-4000-8000-000000000001");
        row.put("operation_id", "20000000-0000-4000-8000-000000000001");
        row.put("round_id", "30000000-0000-4000-8000-000000000001");
        row.put("assignment_id", "40000000-0000-4000-8000-000000000001");
        row.put("actor_user_id", 9007199254740993L);
        row.put("type", "RECORDS");
        row.put("body", "已完成运行检查，设备通信恢复。");
        row.put("visibility", "CUSTOMER_VISIBLE");
        row.put("work_hours", new BigDecimal("0.50"));
        row.put("qualification_json", "{\"departmentId\":\"25\"}");
        row.put("row_version", 9007199254740994L);
        row.put("occurred_at", LocalDateTime.of(2026, 11, 14, 2, 3, 4));
        return row;
    }

    @Test
    void staffCanTraceTheStoredOperationAndRoundWithoutLosingBigintPrecision() {
        var row = record();
        var view = workflow.recordView(row, false);

        assertEquals(row.get("operation_id"), view.get("operationId"));
        assertEquals(row.get("round_id"), view.get("roundId"));
        assertEquals(row.get("id"), view.get("recordId"));
        assertEquals("9007199254740993", view.get("actorUserId"));
        assertEquals("9007199254740994", view.get("rowVersion"));
        assertEquals("2026-11-14T02:03:04Z", view.get("occurredAt"));
        assertEquals(row.get("assignment_id"), view.get("assignmentId"));
        assertEquals("0.50", view.get("workHours"));
        assertFalse(row.containsKey("operationId"));
    }

    @Test
    void customerKeepsTheExistingPublicProjection() {
        var view = workflow.recordView(record(), true);

        assertEquals("CUSTOMER_VISIBLE", view.get("visibility"));
        assertEquals("9007199254740993", view.get("actorUserId"));
        for (String field : new String[]{"operationId", "roundId", "assignmentId", "workHours", "qualificationSnapshot"}) {
            assertFalse(view.containsKey(field), field);
        }
    }

    @Test
    void systemRecordsKeepTheirActualActorAndNullableAssignment() {
        var row = record();
        row.put("actor_user_id", 0L);
        row.put("assignment_id", null);

        var view = workflow.recordView(row, false);

        assertEquals("0", view.get("actorUserId"));
        assertTrue(view.containsKey("assignmentId"));
        assertNull(view.get("assignmentId"));
        assertEquals(row.get("operation_id"), view.get("operationId"));
    }
}
