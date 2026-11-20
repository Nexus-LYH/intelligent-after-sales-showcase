import { request } from './api'
import type { TicketFile } from './ticket-api'
export interface SupplementReply { replyId: string; body: string; repliedAt: string; operationId: string; repliedBy: string; attachments: TicketFile[] }
export interface SupplementRequest {
  requestId: string; ticketId: string; roundId: string; assignmentId: string | null
  question: string; state: 'OPEN' | 'REPLIED' | 'CANCELLED'; termination?: { reason:'CANCELLED'; terminatedAt:string } | null; resumeState: 'PENDING_ACCEPTANCE' | 'IN_PROGRESS'
  requestedAt: string; requestedBy: string; reply: SupplementReply | null
}
export const supplementApi = {
  history: (id: string, page = 1, roundId?:string) => request<{items: SupplementRequest[]; total: number; page: number; size: number}>(`/ticket/tickets/${encodeURIComponent(id)}/supplement-requests?page=${page}&size=20${roundId?'&roundId='+encodeURIComponent(roundId):''}`),
}
export const supplementStamp = (t: import('./ticket-api').TicketDetail) => JSON.stringify([t.id,t.rowVersion,t.roundId,t.assignment?.assignmentId,t.currentSupplement?.requestId,t.currentSupplement?.state,t.status])
export function validSupplement(s: SupplementRequest, ticket: import('./ticket-api').TicketDetail) {
  const privateFields = (value: object) => ['internalBody','qualificationSnapshot','privatePath','storagePath','workHours'].some(k => k in value)
  if (!s || s.ticketId !== ticket.id || s.roundId !== ticket.roundId || !s.requestId || typeof s.question !== 'string' || typeof s.requestedAt !== 'string' || typeof s.requestedBy !== 'string' || !['OPEN','REPLIED','CANCELLED'].includes(s.state) || !['PENDING_ACCEPTANCE','IN_PROGRESS'].includes(s.resumeState) || privateFields(s)) return false
  if (s.resumeState === 'PENDING_ACCEPTANCE' ? s.assignmentId !== null : typeof s.assignmentId !== 'string' || !s.assignmentId) return false
  if (s.state === 'CANCELLED') return s.reply === null && s.termination?.reason === 'CANCELLED' && Number.isFinite(Date.parse(s.termination.terminatedAt))
  if (s.termination) return false
  if (s.state === 'OPEN') return s.reply === null
  const r = s.reply
  return !!r && typeof r.replyId === 'string' && typeof r.operationId === 'string' && typeof r.body === 'string' && typeof r.repliedBy === 'string' && typeof r.repliedAt === 'string' && !privateFields(r) && Array.isArray(r.attachments) && r.attachments.length <= 5 && r.attachments.every(f => f && !privateFields(f) && typeof f.fileId === 'string' && typeof f.name === 'string' && ['image/png','image/jpeg','application/pdf'].includes(f.mediaType) && Number.isSafeInteger(f.size) && f.size > 0 && f.size <= 20971520 && /^[0-9a-f]{64}$/.test(f.sha256) && f.state === 'READY')
}
