import { ApiError, request } from './api'
import type { ActionResult, Candidate } from './ticket-action-api'
import type { PendingTicketAction } from './ticket-action-work'
export const transferStates: Record<string,string> = {PENDING_CONFIRMATION:'待目标工程师确认',ACCEPTED:'已接受交接',REJECTED:'目标已拒绝',WITHDRAWN:'已撤回',EXPIRED:'已超时，待协调',NO_LONGER_APPLICABLE:'已不再适用'}
export interface TransferRequest {
  transferRequestId:string; ticketId:string; roundId:string; sourceAssignmentId:string
  sourceDepartmentId:string; sourceEngineerUserId:string; targetDepartmentId:string; targetEngineerUserId:string
  state:string; effectiveState?:string; expiredAtPending?:boolean; reason?:string; requestedAt:string; expiresAt:string
  decision?: {state:string; reason?:string|null; decidedBy:string|null; decidedAt:string; assignmentId:string|null}|null
}
export interface TransferInbox extends TransferRequest {
  ticketNo:string; rowVersion:string; status:string; effectiveState:string; expiredAtPending:boolean; reason:string
  handoffSummary:{title:string;deviceName:string;faultSummary:string}; allowedActions:string[]
}
export interface TransferCoordination {required:boolean; reasons:string[]; sourceQualified:boolean; pendingRequestId:string|null}
const str = (v:unknown):v is string => typeof v === 'string' && v.length>0
export const transferStamp = (r:TransferInbox) => JSON.stringify([r.ticketId,r.roundId,r.rowVersion,r.sourceAssignmentId,r.transferRequestId,r.targetDepartmentId,r.targetEngineerUserId,r.state,r.effectiveState])
export function validTransfer(r:TransferRequest,ticketId:string,roundId:string|undefined,publicOnly=false) {
  return !!r && r.ticketId===ticketId && r.roundId===roundId && [r.transferRequestId,r.sourceAssignmentId,r.sourceDepartmentId,r.sourceEngineerUserId,r.targetDepartmentId,r.targetEngineerUserId,r.requestedAt,r.expiresAt].every(str)
    && !!transferStates[r.state] && (!r.effectiveState || !!transferStates[r.effectiveState]) && !['qualificationSnapshot','internalBody','attachments','contactPhone','contactName'].some(k=>k in r)
    && (!publicOnly || r.state==='ACCEPTED' && typeof r.reason!=='string' && typeof r.decision?.reason!=='string')
}
export function validInbox(r:TransferInbox,userId:string) {
  const keys=['transferRequestId','ticketId','ticketNo','roundId','rowVersion','status','state','effectiveState','requestedAt','expiresAt','expiredAtPending','sourceAssignmentId','sourceDepartmentId','sourceEngineerUserId','targetDepartmentId','targetEngineerUserId','reason','handoffSummary','allowedActions']
  return validTransfer(r,r?.ticketId,r?.roundId) && r.targetEngineerUserId===userId && Object.keys(r).every(k=>keys.includes(k)) && [r.rowVersion,r.ticketNo,r.status,r.reason].every(str)
    && !!r.handoffSummary && Object.keys(r.handoffSummary).every(k=>['title','deviceName','faultSummary'].includes(k)) && [r.handoffSummary.title,r.handoffSummary.deviceName,r.handoffSummary.faultSummary].every(v=>typeof v==='string') && Array.from(r.handoffSummary.faultSummary).length<=200
    && Array.isArray(r.allowedActions) && r.allowedActions.every(a=>['transfer-accept','transfer-reject'].includes(a)) && typeof r.expiredAtPending==='boolean' && !!transferStates[r.effectiveState]
}
export const transferApi = {
  inbox:(page=1)=>request<{items:TransferInbox[];total:number;page:number;size:number}>(`/ticket/transfer-inbox?page=${page}&size=20`),
  summary:(id:string)=>request<TransferInbox>(`/ticket/transfer-inbox/${encodeURIComponent(id)}`),
  history:(id:string,page=1,roundId?:string)=>request<{items:TransferRequest[];total:number;page:number;size:number}>(`/ticket/tickets/${encodeURIComponent(id)}/transfer-requests?page=${page}&size=20${roundId?'&roundId='+encodeURIComponent(roundId):''}`),
  candidates:(id:string,departmentId:string,keyword:string,page=1)=>request<{items:Candidate[];total:number;page:number;size:number;departmentId:string;checkedAt:string}>(`/ticket/tickets/${encodeURIComponent(id)}/transfer-candidates?${new URLSearchParams({departmentId,keyword,page:String(page),size:'20'})}`),
}
export function verifyTransferResult(r:ActionResult,p:PendingTicketAction) {
  const requestId = p.path.split('/transfer-requests/')[1]?.split('/')[0]
  const expectedState = p.action==='transfer-request'?'PENDING_CONFIRMATION':p.action==='transfer-withdraw'?'WITHDRAWN':p.body.decision==='ACCEPT'?'ACCEPTED':'REJECTED'
  if (p.binding && (r.targetDepartmentId!==p.binding.targetDepartmentId || r.targetEngineerUserId!==p.binding.targetEngineerUserId) || r.operationId!==p.key || r.ticketId!==p.ticketId || r.roundId!==p.body.roundId || r.sourceAssignmentId!==p.body.sourceAssignmentId || r.action!==(['transfer-accept','transfer-reject'].includes(p.action)?'transfer-decision':p.action) || r.state!==expectedState || !r.rowVersion || !r.transferRequestId || requestId && r.transferRequestId!==decodeURIComponent(requestId) || p.action==='transfer-request' && (!r.requestedAt || !r.expiresAt || r.targetDepartmentId!==p.body.targetDepartmentId || r.targetEngineerUserId!==p.body.targetEngineerUserId) || p.action!=='transfer-request' && !r.decidedAt || expectedState==='ACCEPTED' && (!r.assignmentId || r.assignmentId===p.body.sourceAssignmentId || !r.acceptedAt || r.status!=='IN_PROGRESS')) throw new ApiError(503,'TRANSFER_RESPONSE_MISMATCH','转派回执与原请求、源分派或目标不符，请保留原UUID查询')
}
