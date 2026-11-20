package com.ias.stock;

import com.ias.trust.Problem;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/** Reads immutable owner movements using the current tenant and warehouse scope. */
@Repository
public class StockLedgerQueries {
    private final JdbcTemplate db;

    public StockLedgerQueries(JdbcTemplate db) {
        this.db = db;
    }

    public Map<String, Object> movements(StockAccess.Principal actor, long warehouse,
                                         Long part, String order, int page, int size) {
        if (!actor.warehouseIds().contains(warehouse)
                || db.queryForObject("SELECT COUNT(*) FROM stock_warehouse WHERE tenant_id=? AND id=? AND active=TRUE",
                Long.class, actor.tenantId(), warehouse) != 1) {
            throw new Problem(404, "TARGET_NOT_ACCESSIBLE");
        }

        String partFilter = part == null ? "" : " AND m.part_id=?";
        String inbound = """
                SELECT 'INBOUND' source_kind,m.operation_id,NULL order_id,m.part_id,m.actor_id,
                       'INBOUND' action_name,m.quantity on_hand_delta,0 reserved_delta,0 used_delta,m.recorded_at
                  FROM stock_movement m
                 WHERE m.tenant_id=? AND m.warehouse_id=?
                """ + partFilter + (order == null ? "" : " AND 1=0");
        String execution = """
                SELECT 'EXECUTION' source_kind,m.operation_id,o.order_id,m.part_id,o.actor_id,
                       o.action_name,m.on_hand_delta,m.reserved_delta,m.used_delta,o.recorded_at
                  FROM stock_execution_movement m
                  JOIN stock_execution_operation o ON o.tenant_id=m.tenant_id AND o.operation_id=m.operation_id
                  JOIN stock_execution_order w ON w.tenant_id=o.tenant_id AND w.order_id=o.order_id
                 WHERE m.tenant_id=? AND w.warehouse_id=?
                """ + partFilter + (order == null ? "" : " AND o.order_id=?");
        String union = "(" + inbound + " UNION ALL " + execution + ") ledger";
        List<Object> args = new ArrayList<>(List.of(actor.tenantId(), warehouse));
        if (part != null) args.add(part);
        args.add(actor.tenantId());
        args.add(warehouse);
        if (part != null) args.add(part);
        if (order != null) args.add(order);
        long total = db.queryForObject("SELECT COUNT(*) FROM " + union, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        var items = db.query("SELECT * FROM " + union
                        + " ORDER BY recorded_at DESC,operation_id DESC,part_id,source_kind LIMIT ? OFFSET ?",
                (row, number) -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("source", row.getString("source_kind"));
                    item.put("operationId", row.getString("operation_id"));
                    item.put("orderId", row.getString("order_id"));
                    item.put("partId", Long.toString(row.getLong("part_id")));
                    item.put("actorUserId", Long.toString(row.getLong("actor_id")));
                    item.put("action", row.getString("action_name"));
                    item.put("onHandDelta", Long.toString(row.getLong("on_hand_delta")));
                    item.put("reservedDelta", Long.toString(row.getLong("reserved_delta")));
                    item.put("usedDelta", Long.toString(row.getLong("used_delta")));
                    item.put("recordedAt", row.getTimestamp("recorded_at",
                            Calendar.getInstance(TimeZone.getTimeZone("UTC"))).toInstant().toString());
                    return item;
                }, args.toArray());
        return Map.of("items", items, "total", total, "page", page, "size", size);
    }
}
