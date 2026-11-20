package com.ias.ticket;

import com.ias.trust.Problem;

/** Call only while holding the corresponding ticket_record row lock. */
final class FeePartPermitBarrier {
 private FeePartPermitBarrier() {}
 static void guard(Tickets tickets, Object ticket) {
  Long open = tickets.db.queryForObject("SELECT COUNT(*) FROM ticket_fee_part_permit p LEFT JOIN ticket_fee_part_permit_result r ON r.operation_id=p.operation_id WHERE p.ticket_id=? AND r.operation_id IS NULL", Long.class, ticket);
  if (open == null || open > 0) throw new Problem(409, "FEE_PART_EXECUTION_PENDING");
 }
}
