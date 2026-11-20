export interface ApprovalOwner { tenantId: string; actorId: string; generation: number; namespace: string; attemptId?:string }
export interface ApprovalSubmission {
  operationId: string; tenantId: string; applicantId: string; ticketId: string; assignmentId: string;
  departmentId: string; warehouseId: string; partId: string; quantity: number; purpose: string
}
export type ChargeMode = 'FREE'|'CUSTOMER_PAID'
export interface ApprovalView {
  id: string; submission: ApprovalSubmission; state: 'WAITING_CONFIGURATION'|'PENDING'|'APPROVED'|'REJECTED'|'WITHDRAWN';
  version: string; submittedAt: string;
  decision: null|{ operationId: string; tenantId: string; actorId: string; expectedVersion: string; outcome: 'APPROVE'|'REJECT'; reason: string };
  decidedAt: string|null;
  revision: { rootApplicationId: string; number: string; previousApplicationId: string|null };
  withdrawal: null|{ operationId: string; tenantId: string; actorId: string; expectedVersion: string; reason: string };
  withdrawnAt: string|null; allowedActions?:string[]; chargeMode?:ChargeMode|'UNKNOWN'
}
export type SubmitInput = Omit<ApprovalSubmission,'operationId'|'tenantId'|'applicantId'> & { previousApplicationId?: string; chargeMode?:ChargeMode }
export type DecisionInput = { expectedVersion: string; outcome:'APPROVE'|'REJECT'; reason?:string }
export type WithdrawInput = { expectedVersion:string; reason?:string }
export type ApprovalAction = 'submit'|'decision'|'withdraw'
export interface WireResponse { status:number; body:unknown }
/** Integration must supply the existing authenticated/CSRF-aware transport; never a raw permissive fetch. */
export interface ApprovalTransport { scoped?(current:()=>boolean):ApprovalTransport; request(method:'GET'|'POST', path:string, body?:Readonly<Record<string,unknown>>, headers?:Readonly<Record<string,string>>, current?:()=>boolean):Promise<WireResponse> }
export const uuid = (v:unknown):v is string => typeof v==='string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(v)
export function decimal(v:unknown, positive=false):v is string {
  return typeof v==='string' && (positive ? /^[1-9][0-9]*$/ : /^(0|[1-9][0-9]*)$/).test(v) && BigInt(v)<=9223372036854775807n
}
const object=(v:unknown):v is Record<string,unknown> => !!v && typeof v==='object' && !Array.isArray(v)
const instant=(v:unknown):v is string => typeof v==='string' && /^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(v) && Number.isFinite(Date.parse(v))
const text=(v:unknown,min:number,max:number):v is string => typeof v==='string' && [...v].length>=min && [...v].length<=max
const department=(v:unknown):v is string => decimal(v,true)
export function parseApproval(value:unknown):ApprovalView {
  if(!object(value)||!uuid(value.id)||!decimal(value.version)||!instant(value.submittedAt)||!object(value.submission)||!object(value.revision))throw Error('INVALID_APPROVAL_PROJECTION')
  const s=value.submission,r=value.revision
  if(!uuid(s.operationId)||!decimal(s.tenantId,true)||!decimal(s.applicantId,true)||!uuid(s.ticketId)||!uuid(s.assignmentId)||!department(s.departmentId)||!decimal(s.warehouseId,true)||!decimal(s.partId,true)||!Number.isInteger(s.quantity)||Number(s.quantity)<1||Number(s.quantity)>2147483647||!text(s.purpose,5,500)||!uuid(r.rootApplicationId)||!decimal(r.number,true)||(r.previousApplicationId!==null&&!uuid(r.previousApplicationId)))throw Error('INVALID_APPROVAL_PROJECTION')
  if(!['WAITING_CONFIGURATION','PENDING','APPROVED','REJECTED','WITHDRAWN'].includes(String(value.state)))throw Error('INVALID_APPROVAL_PROJECTION')
  if(value.chargeMode!==undefined&&value.chargeMode!=='FREE'&&value.chargeMode!=='CUSTOMER_PAID'&&value.chargeMode!=='UNKNOWN')throw Error('INVALID_APPROVAL_PROJECTION')
  const decision=value.decision,withdrawal=value.withdrawal
  const fact=(v:unknown)=>object(v)&&uuid(v.operationId)&&v.tenantId===s.tenantId&&decimal(v.actorId,true)&&decimal(v.expectedVersion)&&text(v.reason,0,500)
  if(decision!==null&&(!fact(decision)||!object(decision)||!['APPROVE','REJECT'].includes(String(decision.outcome))||(decision.outcome==='REJECT'&&!text(decision.reason,5,500))))throw Error('INVALID_APPROVAL_PROJECTION')
  if(withdrawal!==null&&!fact(withdrawal))throw Error('INVALID_APPROVAL_PROJECTION')
  if((decision===null)!==(value.decidedAt===null)||(value.decidedAt!==null&&!instant(value.decidedAt))||(withdrawal===null)!==(value.withdrawnAt===null)||(value.withdrawnAt!==null&&!instant(value.withdrawnAt)))throw Error('INVALID_APPROVAL_PROJECTION')
  if((value.state==='APPROVED'||value.state==='REJECTED')!==(decision!==null)||(value.state==='WITHDRAWN')!==(withdrawal!==null)||decision!==null&&withdrawal!==null)throw Error('INVALID_APPROVAL_PROJECTION')
  if(object(decision)&&((value.state==='APPROVED')!==(decision.outcome==='APPROVE')))throw Error('INVALID_APPROVAL_PROJECTION')
  if(value.allowedActions!==undefined&&(!Array.isArray(value.allowedActions)||!value.allowedActions.every(v=>typeof v==='string'&&['approve','reject','withdraw','revise'].includes(v))))throw Error('INVALID_APPROVAL_PROJECTION')
  return value as unknown as ApprovalView
}
export function validateCommand(action:ApprovalAction, body:Record<string,unknown>) {
  if(!uuid(body.operationId))throw Error('INVALID_OPERATION')
  const required=action==='submit'?['operationId','ticketId','assignmentId','departmentId','warehouseId','partId','quantity','purpose']:action==='decision'?['operationId','expectedVersion','outcome']:['operationId','expectedVersion']
  const allowed=[...required,...(action==='submit'?['previousApplicationId','chargeMode']:['reason'])]
  if(required.some(k=>!(k in body))||Object.keys(body).some(k=>!allowed.includes(k)))throw Error('INVALID_COMMAND')
  if(action==='submit') {
    if(body.chargeMode!==undefined&&body.chargeMode!=='FREE'&&body.chargeMode!=='CUSTOMER_PAID')throw Error('INVALID_COMMAND')
    if(!uuid(body.ticketId)||!uuid(body.assignmentId)||!department(body.departmentId)||!decimal(body.warehouseId,true)||!decimal(body.partId,true)||!Number.isInteger(body.quantity)||Number(body.quantity)<1||Number(body.quantity)>2147483647||!text(body.purpose,5,500)||body.previousApplicationId!==undefined&&!uuid(body.previousApplicationId))throw Error('INVALID_COMMAND')
  }else if(!decimal(body.expectedVersion)||body.reason!==undefined&&!text(body.reason,0,500)||action==='decision'&&(!['APPROVE','REJECT'].includes(String(body.outcome))||body.outcome==='REJECT'&&!text(body.reason,5,500)))throw Error('INVALID_COMMAND')
}
