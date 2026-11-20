import {ApiError,request} from './api'
import type {TicketDetail} from './ticket-api'
import type {Candidate} from './ticket-action-api'
import type {PendingTicketAction} from './ticket-action-work'
export interface ReopenCheck {ticketId:string;sourceRoundId:string;sourceClosureOperationId:string|null;closedAt:string|null;reopenDeadline:string|null;serverNow:string;canRequest:boolean;blockers:string[]}
export interface ReopenRequest {reopenRequestId:string;ticketId:string;sourceRoundId:string;sourceClosureOperationId:string;requestedBy:string;requestedAt:string;reopenDeadline:string;reason:string;state:'PENDING_REVIEW'|'APPROVED'|'REJECTED'|'NO_LONGER_APPLICABLE';decision?:{decision:string;reason:string;decidedBy:string;decidedAt:string;sameFault?:boolean;priority?:string;newRoundId?:string|null}|null;newRoundId?:string|null}
export interface RoundSummary {roundId:string;ticketId?:string;roundNo:string|number;startedAt:string;status:string;priority?:string;ruleVersion?:string;current:boolean;endedAt?:string|null;firstResponseAt?:string|null;closedAt?:string|null}
const positiveOrdinal=(v:unknown)=>typeof v==='string'?/^[1-9]\d{0,18}$/.test(v):typeof v==='number'&&Number.isSafeInteger(v)&&v>0
const text=(v:unknown):v is string=>typeof v==='string'&&v.length>0
export const reopenAction=(name:string)=>name.startsWith('reopen-')||name==='round-response'
export const sourceRound=(p:PendingTicketAction)=>p.body.sourceRoundId||p.body.roundId||p.binding?.roundId
export function validReopen(r:ReopenRequest,t:TicketDetail){return !!r&&r.ticketId===t.id&&r.sourceRoundId===t.roundId&&[r.reopenRequestId,r.sourceClosureOperationId,r.requestedBy,r.requestedAt,r.reopenDeadline,r.reason].every(text)&&['PENDING_REVIEW','APPROVED','REJECTED'].includes(r.state)&& (r.state==='PENDING_REVIEW'?r.decision===null:!!r.decision&&r.decision.decision===(r.state==='APPROVED'?'APPROVE':'REJECT')&&[r.decision.reason,r.decision.decidedBy,r.decision.decidedAt].every(text)&& (r.state==='APPROVED'?text(r.decision.newRoundId)&&['P1','P2','P3'].includes(r.decision.priority||''):!r.decision.newRoundId))&&!['qualificationSnapshot','internalBody','reviewDepartmentIds','audit'].some(k=>k in r)}
export function validRound(r:RoundSummary,id:string){return !!r&&(r.ticketId===undefined||r.ticketId===id)&&text(r.roundId)&&positiveOrdinal(r.roundNo)&&Number.isFinite(Date.parse(r.startedAt))&&text(r.status)&&typeof r.current==='boolean'&&!['qualificationSnapshot','internalBody','audit'].some(k=>k in r)}
export const reopenApi={
 check:(id:string)=>request<ReopenCheck>(`/ticket/tickets/${encodeURIComponent(id)}/reopen-check`),
 history:(id:string,page=1)=>request<{items:ReopenRequest[];total:number;page:number;size:number}>(`/ticket/tickets/${encodeURIComponent(id)}/reopen-requests?page=${page}&size=20`),
 candidates:(id:string,departmentId:string,keyword:string,page=1)=>request<{items:Candidate[];total:number;page:number;size:number;departmentId:string;checkedAt:string}>(`/ticket/tickets/${encodeURIComponent(id)}/reopen-candidates?${new URLSearchParams({departmentId,keyword,page:String(page),size:'20'})}`),
 rounds:(id:string,page=1)=>request<{items:RoundSummary[];total:number;page:number;size:number}>(`/ticket/tickets/${encodeURIComponent(id)}/rounds?page=${page}&size=20`),
 round:(id:string,roundId:string)=>request<TicketDetail>(`/ticket/tickets/${encodeURIComponent(id)}/rounds/${encodeURIComponent(roundId)}`),
}
export function verifyReopenResult(r:import('./ticket-action-api').ActionResult,p:PendingTicketAction){
 const fail=()=>{throw new ApiError(503,'REOPEN_RESPONSE_MISMATCH','回执与原重开申请、关闭事实或轮次不符，请保留原UUID查询')}
 if(r.operationId!==p.key||r.ticketId!==p.ticketId||!text(r.rowVersion))fail()
 if(p.action==='round-response'){if(r.action!=='round-response'||r.state!=='COMPLETED'||r.roundId!==sourceRound(p)||!r.recordId||!r.firstResponseAt||typeof r.firstResponse!=='boolean')fail();return}
 if(r.sourceRoundId!==p.body.sourceRoundId||r.sourceClosureOperationId!==p.body.sourceClosureOperationId||!r.reopenRequestId)fail()
 if(p.action==='reopen-request'){if(r.action!=='reopen-request'||r.state!=='PENDING_REVIEW'||r.status!=='CLOSED'||!r.requestedAt||!r.reopenDeadline||r.newRoundId)fail();return}
 const requestId=decodeURIComponent(p.path.split('/reopen-requests/')[1]?.split('/')[0]||'')
 if(r.action!=='reopen-decision'||r.reopenRequestId!==requestId||r.state!==(p.body.decision==='APPROVE'?'APPROVED':'REJECTED'))fail()
 if(p.body.decision==='APPROVE'){if(!r.newRoundId||r.newRoundId===p.body.sourceRoundId||!r.roundStartedAt||r.roundId&&r.roundId!==r.newRoundId||r.status!==(p.body.targetEngineerUserId?'ASSIGNED':'WAITING_ASSIGNMENT')||(p.body.targetEngineerUserId?!r.assignmentId||r.assignmentId===p.binding?.sourceAssignmentId:!!r.assignmentId))fail()}
 else if(r.status!=='CLOSED'||r.newRoundId||r.roundId&&r.roundId!==p.body.sourceRoundId)fail()
}

export function validRoundProjection(t:TicketDetail,id:string,selected?:string){
 if(t.id!==id||!t.roundId||t.currentSolution&&t.currentSolution.roundId!==t.roundId)return false
 const a=t.assignment;if(a!=null&&(!a||typeof a!=='object'||![a.assignmentId,a.engineerUserId,a.departmentId,a.state].every(text)))return false
 if(t.roundNo!==undefined&&!positiveOrdinal(t.roundNo))return false
 if(selected)return t.roundId===selected&&t.isHistorical===true&&!!t.currentRoundId&&t.currentRoundId!==t.roundId&&Array.isArray(t.allowedActions)&&t.allowedActions.length===0
 return !t.isHistorical&&(!t.currentRoundId||t.currentRoundId===t.roundId)
}
