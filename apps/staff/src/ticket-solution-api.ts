import { request } from './api'
export interface CustomerDecision { decision: 'CONFIRM' | 'RETURN'; reason?: string | null; actorUserId: string; decidedAt: string }
export interface SolutionAttempt {
  solutionId: string; attemptNo: number; roundId: string; assignmentId: string; engineerUserId: string
  cause: string; resolutionMethod: string; customerMessage: string; completedAt: string; confirmedWorkHours: string
  termination?: {reason:'CANCELLED'; terminatedAt:string} | null; evidenceFileIds: string[]; submittedAt: string; decision?: CustomerDecision | null
}
export interface SolutionPage { items: SolutionAttempt[]; total: number; page: number; size: number }
export interface CloseCheck {
  ticketId: string; roundId: string; solutionId: string; rowVersion: string; canClose: boolean
  approvalIntentCount?:string; externalOwnerCleanupVerified?:boolean;
  blockers: string[]; scope: string; relatedWorkflows: string; customerConfirmed: boolean; materialsComplete: boolean
}
export interface TicketTiming {
  clockStopped?: boolean; terminalReason?: string | null; terminatedAt?: string | null; terminatedEffectiveResolutionMs?: string | null;
  supplementWaitMs?: string; excludedSupplementMs?: string; remainingSupplementDeductionMs?: string; supplementDeductionLimitMs?: string; currentSupplementWaitMs?: string; currentSupplementWaitOver72Hours?: boolean; firstSubmissionEffectiveMs?: string | null;
  roundStartedAt: string; firstSolutionSubmittedAt: string | null; firstSubmissionElapsedMs: string | null; firstSubmissionOverdue: boolean | null
  confirmationWaitMs: string; excludedConfirmationMs: string; remainingConfirmationDeductionMs: string; effectiveResolutionMs: string
  confirmationDeductionLimitMs: string; currentWaitOver72Hours: boolean; needsCoordination: boolean; finalFrozen: boolean; finalResolutionOverdue: boolean | null; resolutionTargetMs: string
}
export interface TicketClosure { operationId?: string; solutionId: string; closedBy: string; closedAt: string; closeMessage: string }
export function validSolution(value: SolutionAttempt, customer = false) {
  return !!value && (!value.termination || value.termination.reason === 'CANCELLED' && Number.isFinite(Date.parse(value.termination.terminatedAt)) && !value.decision) && ['solutionId','roundId','assignmentId','engineerUserId','submittedAt','cause','resolutionMethod','customerMessage','completedAt','confirmedWorkHours'].every(key => typeof value[key as keyof SolutionAttempt] === 'string') && Number.isInteger(value.attemptNo) && value.attemptNo > 0 && Array.isArray(value.evidenceFileIds) && value.evidenceFileIds.length <= 5 && value.evidenceFileIds.every(id => typeof id === 'string') && (!value.decision || ['CONFIRM','RETURN'].includes(value.decision.decision)) && (!customer || !['qualificationSnapshot','internalBody','workHours'].some(key => key in value))
}
export function duration(ms: string | null | undefined) {
  if (!ms || !/^\d+$/.test(ms)) return '待核对'
  const minutes = BigInt(ms) / 60000n
  return `${minutes / 60n}小时${minutes % 60n}分钟`
}
export function resolutionOverdue(timing: TicketTiming) {
  if (timing.terminalReason === 'CANCELLED') return null
  if (timing.finalFrozen) return typeof timing.finalResolutionOverdue === 'boolean' ? timing.finalResolutionOverdue : null
  if (!/^\d+$/.test(timing.effectiveResolutionMs) || !/^\d+$/.test(timing.resolutionTargetMs)) return null
  return BigInt(timing.effectiveResolutionMs) > BigInt(timing.resolutionTargetMs)
}
export function closeCheckMatches(check: CloseCheck | null, ticket: import('./ticket-api').TicketDetail) {
  return !!check && check.ticketId === ticket.id && check.roundId === ticket.roundId && check.solutionId === ticket.currentSolution?.solutionId && check.rowVersion === ticket.rowVersion
}
export function relatedApprovalNotice(check:CloseCheck){
 if(check.relatedWorkflows==='APPROVAL_OR_PARTS_PENDING'||check.relatedWorkflows==='APPROVAL_INTENT_REGISTERED')return '存在审批或备件关联意图'+(typeof check.approvalIntentCount==='string'&&/^[1-9][0-9]*$/.test(check.approvalIntentCount)?'（'+check.approvalIntentCount+'项）':'')+'；外部清理未核实，是否可收尾以当前服务端阻塞事实为准，不表示费用已结清。'
 if(check.relatedWorkflows==='APPROVAL_AND_PARTS_CLEARED'&&check.externalOwnerCleanupVerified===true)return '备件执行收尾已核验；费用是否结算以费用记录为准。'
 return check.relatedWorkflows==='NOT_ENABLED'?'审批、库存、费用子流程未启用；不表示已向远端查得全部清零或已结算。':'有关联业务未受当前范围支持，请核对服务端阻塞。'
}
export function checkAllowsClose(check: CloseCheck | null, ticket: import('./ticket-api').TicketDetail) {
  return closeCheckMatches(check,ticket) && check!.canClose === true && check!.customerConfirmed === true && check!.materialsComplete === true && check!.scope === 'ORDINARY_REPAIR_ONLY' && (check!.relatedWorkflows === 'NOT_ENABLED' || (check!.relatedWorkflows === 'APPROVAL_AND_PARTS_CLEARED' && check!.externalOwnerCleanupVerified === true)) && Array.isArray(check!.blockers) && check!.blockers.length === 0
}
export const solutionApi = {
  history: (id: string, page = 1, roundId?:string) => request<SolutionPage>(`/ticket/tickets/${encodeURIComponent(id)}/solutions?page=${page}&size=20${roundId?'&roundId='+encodeURIComponent(roundId):''}`),
  closeCheck: (id: string) => request<CloseCheck>(`/ticket/tickets/${encodeURIComponent(id)}/close-check`),
}

