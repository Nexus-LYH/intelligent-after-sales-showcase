import { ApiError, request, type Identity, type Csrf } from '../../api'
import { captureRequestScope, type AuthSource } from '../../request-scope'
import {uuid,type ApprovalOwner,type ApprovalTransport} from './approval-client'
export interface ApprovalSession extends AuthSource {phase:string;csrf:Csrf|null;handleFailure(e:unknown):unknown}
// Entry is not a grant to read/submit/approve: server checks each object and action.
export const canOpenApprovals=(me:Identity|null)=>!!me&&me.realm==='TENANT'&&me.identityType==='EMPLOYEE'&&!!me.tenant
export function approvalOwner(auth:ApprovalSession):ApprovalOwner|null{
  const me=auth.identity
  return auth.phase==='NORMAL'&&canOpenApprovals(me)?{tenantId:me!.tenant!.id,actorId:me!.userId,generation:auth.generation,namespace:'TENANT',attemptId:me!.loginAttemptId}:null
}
export function approvalTransport(auth:ApprovalSession,active:()=>boolean=()=>true,send:typeof request=request):ApprovalTransport{return{scoped:(current)=>approvalTransport(auth,()=>active()&&current(),send),async request(method,path,body,headers,current=()=>true){
  const scope=captureRequestScope(auth,()=>active()&&current()),owner=approvalOwner(auth)
  if(!owner||!active())return{status:401,body:{code:'NORMAL_EMPLOYEE_REQUIRED'}}
  let allowed=/^\/api\/approval\/parts(?:\/[0-9a-f-]{36}(?:\/(?:decisions|withdrawals))?|\/operations\/[0-9a-f-]{36})?$/.test(path)
  if(method==='GET'&&path.startsWith('/api/approval/parts?')){const q=new URLSearchParams(path.slice(path.indexOf('?')+1));allowed=q.size===3&&q.getAll('ticketId').length===1&&uuid(q.get('ticketId'))&&q.getAll('page').length===1&&/^[1-9][0-9]{0,4}$/.test(q.get('page')??'')&&q.getAll('size').length===1&&['20','50','100'].includes(q.get('size')??'')}
  if(method==='GET'&&/^\/api\/stock\/warehouses(?:\/[1-9][0-9]*\/parts)?\?/.test(path)){const q=new URLSearchParams(path.slice(path.indexOf('?')+1));allowed=q.size===4&&uuid(q.get('ticketId'))&&uuid(q.get('assignmentId'))&&/^[1-9][0-9]{0,5}$/.test(q.get('page')??'')&&Number(q.get('page'))<=100000&&q.get('size')==='20'&&['ticketId','assignmentId','page','size'].every(k=>q.getAll(k).length===1)}
  if(!allowed)throw Error('APPROVAL_PATH_NOT_ALLOWED')
  const csrf=auth.csrf
  if(method==='POST'&&(!csrf||csrf.contextKind!=='AUTH'||csrf.loginAttemptId!==auth.identity?.loginAttemptId||!Number.isFinite(Date.parse(csrf.expiresAt))||Date.parse(csrf.expiresAt)<=Date.now()))return{status:403,body:{code:'CSRF_CONTEXT_UNCONFIRMED'}}
  if(method==='POST'&&(!body||headers?.['Idempotency-Key']!==body.operationId))throw Error('APPROVAL_OPERATION_MISMATCH')
  try{
    const result=await send<unknown>(path.slice(4),{method,...(method==='POST'?{body:JSON.stringify(body),headers:{'Idempotency-Key':String(body!.operationId),'X-CSRF-TOKEN':csrf!.token}}:{})})
    if(!scope.current())return{status:503,body:{code:'STALE_CONTEXT'}}
    return{status:200,body:result}
  }catch(e){
    if(!scope.current())return{status:503,body:{code:'STALE_CONTEXT'}}
    if(e instanceof ApiError){if(e.status===401||e.code==='CSRF_INVALID')auth.handleFailure(e);return{status:e.status||503,body:{code:e.code}}}
    throw e
  }
}}}
