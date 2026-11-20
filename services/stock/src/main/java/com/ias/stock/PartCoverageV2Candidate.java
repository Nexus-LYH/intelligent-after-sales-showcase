package com.ias.stock;

import java.util.Map;
import java.util.Set;

/** Candidate-only binding validation. This does not establish authority or open paid execution. */
final class PartCoverageV2Candidate {
 private static final Set<String> LINE = Set.of("id", "ticketId", "roundId", "kind", "amountCents", "partBinding");
 private static final Set<String> BINDING = Set.of("approvalId", "approvalVersion", "warehouseId", "partId", "quantity", "unitCode", "allocationRule");

 record Expected(String lineId, String ticketId, String roundId, String approvalId,
                 long approvalVersion, long warehouseId, long partId, long quantity,
                 String unitCode, long amountCents) {}

 static boolean matches(Object raw, Expected expected) {
  if (!(raw instanceof Map<?, ?> line) || expected == null || !line.keySet().equals(LINE)) return false;
  if (!(line.get("partBinding") instanceof Map<?, ?> binding) || !binding.keySet().equals(BINDING)) return false;
  try {
   return FinancePartExecution.uuid(line.get("id")).equals(expected.lineId())
    && FinancePartExecution.uuid(line.get("ticketId")).equals(expected.ticketId())
    && FinancePartExecution.uuid(line.get("roundId")).equals(expected.roundId())
    && "PART".equals(line.get("kind"))
    && FinancePartExecution.number(line.get("amountCents"), false) == expected.amountCents()
    && FinancePartExecution.uuid(binding.get("approvalId")).equals(expected.approvalId())
    && FinancePartExecution.number(binding.get("approvalVersion"), false) == expected.approvalVersion()
    && FinancePartExecution.number(binding.get("warehouseId"), false) == expected.warehouseId()
    && FinancePartExecution.number(binding.get("partId"), false) == expected.partId()
    && FinancePartExecution.number(binding.get("quantity"), false) == expected.quantity()
    && binding.get("unitCode") instanceof String unit && !unit.isBlank() && unit.equals(unit.strip()) && unit.length() <= 20 && unit.chars().noneMatch(Character::isISOControl) && unit.equals(expected.unitCode())
    && "FULL_LINE_SINGLE_USE_V1".equals(binding.get("allocationRule"));
  } catch (RuntimeException malformed) { return false; }
 }
 private PartCoverageV2Candidate() {}
}
