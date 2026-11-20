import { request } from './api'
export interface DepartmentOption { id: string; name: string }
export interface AssignmentInfo { assignmentId: string; departmentId: string; engineerUserId: string; state: string }
export type ActionBody = Readonly<Record<string, string | boolean | readonly string[]>>
export interface ProcessingRecord { operationId?:string; roundId?:string; assignmentId?:string|null; recordId: string; body: string; visibility: 'INTERNAL' | 'CUSTOMER_VISIBLE'; workHours?: string; occurredAt: string; actorUserId: string; type: string; rowVersion: string }
export interface RecordPage { items: ProcessingRecord[]; total: number; page: number; size: number }
export interface ActionResult { operationId: string; action: string; state: 'COMPLETED' | 'PENDING_REVIEW' | 'PENDING_CONFIRMATION' | 'ACCEPTED' | 'REJECTED' | 'WITHDRAWN' | 'APPROVED'; sourceRoundId?:string; sourceClosureOperationId?:string; reopenRequestId?:string; reopenDeadline?:string; newRoundId?:string; roundStartedAt?:string; firstResponse?:boolean; firstResponseAt?:string; ticketId: string; status: string; rowVersion: string; roundId: string; sourceAssignmentId?: string; transferRequestId?: string; targetDepartmentId?: string; targetEngineerUserId?: string; acceptedAt?: string; expiresAt?: string; cancelRequestId?: string; cancellationState?: string; cancelledAt?: string; requestedAt?: string; assignmentId?: string | null; requestId?: string; replyId?: string; resumeState?: string; recordId?: string; solutionId?: string; attemptNo?: number; decision?: string; submittedAt?: string; decidedAt?: string; closedAt?: string }
export type ActionOperation = ActionResult
export interface Candidate { userId: string; displayName: string; departmentId: string; departmentName?: string }
export const actionApi = {
  send: (path: string, key: string, body: ActionBody, token: string) => request<ActionResult>(path, { method: 'POST', headers: { 'Idempotency-Key': key, 'X-CSRF-TOKEN': token }, body: JSON.stringify(body) }),
  operation: (key: string) => request<ActionOperation>(`/ticket/action-operations/${encodeURIComponent(key)}`),
  records: (id: string, page = 1, roundId?:string) => request<RecordPage>(`/ticket/tickets/${encodeURIComponent(id)}/records?page=${page}&size=20${roundId?'&roundId='+encodeURIComponent(roundId):''}`),
  candidates: (id: string, departmentId: string, keyword: string, page = 1) => request<{ items: Candidate[]; total: number; page: number; size: number }>(`/ticket/tickets/${encodeURIComponent(id)}/assignment-candidates?${new URLSearchParams({ departmentId, keyword, page: String(page), size: '20' })}`),
}

