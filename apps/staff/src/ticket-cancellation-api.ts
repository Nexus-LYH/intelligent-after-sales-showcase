import { request } from './api'
import type { TicketDetail } from './ticket-api'
export interface CancellationRequest {
  cancelRequestId: string; ticketId: string; roundId: string; assignmentId: string | null
  requestedBy: string; requestedAt: string; reason: string; submittedStatus: string
  state: 'PENDING_REVIEW' | 'APPROVED' | 'REJECTED' | 'NO_LONGER_APPLICABLE'
  decision: {decision:'APPROVED'|'REJECTED'|'NO_LONGER_APPLICABLE'; reason:string; decidedBy:string; decidedAt:string} | null
}
export interface CancellationFact {
  source:'DIRECT'|'APPROVED'; cancelRequestId:string|null; cancelledBy:string; cancelledAt:string
  reason:string|null; previousStatus:string; roundId:string; assignmentId:string|null
  check:{scope:string; relatedWorkflows:string}
}
const text = (s: unknown): s is string => typeof s === 'string' && !!s
export function validCancellationRequest(r: CancellationRequest, t: TicketDetail) {
  return !!r && r.ticketId === t.id && r.roundId === t.roundId && [r.cancelRequestId,r.requestedBy,r.requestedAt,r.reason,r.submittedStatus].every(text)
    && ['PENDING_REVIEW','APPROVED','REJECTED','NO_LONGER_APPLICABLE'].includes(r.state)
    && !['qualificationSnapshot','internalBody','privateCost','audit'].some(k => k in r)
    && (r.state !== 'PENDING_REVIEW' ? !!r.decision && r.decision.decision === r.state && [r.decision.reason,r.decision.decidedBy,r.decision.decidedAt].every(text) : r.decision === null)
}
export function validCancellation(t: TicketDetail) {
  if (t.currentCancellation && (!validCancellationRequest(t.currentCancellation,t) || t.currentCancellation.state !== 'PENDING_REVIEW' || ['CANCELLED','RESOLVED','CLOSED','REJECTED'].includes(t.status))) return false
  const c = t.cancellation
  if (t.status !== 'CANCELLED') return !c
  return !!c && ['DIRECT','APPROVED'].includes(c.source) && c.roundId === t.roundId && text(c.cancelledBy) && Number.isFinite(Date.parse(c.cancelledAt))
    && c.check?.scope === 'ORDINARY_REPAIR_ONLY' && c.check.relatedWorkflows === 'NOT_ENABLED' && !t.currentCancellation && !t.currentSupplement
    && !(t.allowedActions || []).length && t.timing?.clockStopped === true && t.timing.terminalReason === 'CANCELLED'
    && t.timing.finalFrozen === false && t.timing.finalResolutionOverdue === null && t.timing.needsCoordination === false
    && t.timing.terminatedAt === c.cancelledAt && /^\d+$/.test(t.timing.terminatedEffectiveResolutionMs || '') && t.timing.effectiveResolutionMs === t.timing.terminatedEffectiveResolutionMs
}
export const cancellationApi = {
  history: (id:string,page=1,roundId?:string) => request<{items:CancellationRequest[];total:number;page:number;size:number}>(`/ticket/tickets/${encodeURIComponent(id)}/cancellation-requests?page=${page}&size=20${roundId?'&roundId='+encodeURIComponent(roundId):''}`),
}
