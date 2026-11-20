import {ApiError,request,type Identity,type Csrf} from '../../api'
import {captureRequestScope,type AuthSource} from '../../request-scope'
import {decimal,uuid} from '../approval/approval-client'

export interface NotificationSession extends AuthSource{phase:string;csrf:Csrf|null;handleFailure(e:unknown):unknown}
export interface NotificationOwner{tenantId:string;actorId:string;generation:number;attemptId:string}
export interface Message{
 id:string;type:string;title:string;body:string;createdAt:string;occurredAt:string;read:boolean;readAt:string|null;
 object:{type:'APPROVAL_PARTS'|'FEE'|'TICKET';id:string;route:string}
}
export interface MessagePage{items:Message[];total:number;page:number;size:number}
export interface ReadReceipt{operationId:string;messageId:string;state:'READ';readAt:string}
export interface NotificationTransport{request(method:'GET'|'POST',path:string,operationId?:string,current?:()=>boolean):Promise<{status:number;body:unknown}>}
export const canOpenNotifications=(identity:Identity|null)=>!!identity&&identity.realm==='TENANT'&&['EMPLOYEE','CUSTOMER'].includes(identity.identityType)&&!!identity.tenant
export function notificationOwner(auth:NotificationSession):NotificationOwner|null{
 const identity=auth.identity
 return auth.phase==='NORMAL'&&canOpenNotifications(identity)&&decimal(identity!.tenant!.id,true)&&decimal(identity!.userId,true)
  ?{tenantId:identity!.tenant!.id,actorId:identity!.userId,generation:auth.generation,attemptId:identity!.loginAttemptId}:null
}
const object=(x:unknown):x is Record<string,unknown>=>!!x&&typeof x==='object'&&!Array.isArray(x)
const instant=(x:unknown):x is string=>typeof x==='string'&&/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z$/.test(x)&&Number.isFinite(Date.parse(x))
export function parseMessage(value:unknown):Message{
 if(!object(value)||!uuid(value.id)||typeof value.title!=='string'||!value.title||value.title.length>500||typeof value.body!=='string'||value.body.length>4000||!instant(value.createdAt)||!instant(value.occurredAt)||typeof value.read!=='boolean'||(value.readAt!==null&&!instant(value.readAt))||value.read!==(value.readAt!==null)||!object(value.object)||!uuid(value.object.id))throw Error('站内信响应无法确认。')
 const part=['approval.submitted','approval.approved','approval.rejected','approval.withdrawn','approval.terminated'].includes(String(value.type))
 const fee=['approval.fee_completed','ticket.fee_execution_recorded','finance.collection_finalized','finance.refund_finalized','inventory.consumed'].includes(String(value.type))
 const ticket=['ticket.accepted','ticket.resolution_submitted','ticket.closed'].includes(String(value.type))
 if(!(part&&value.object.type==='APPROVAL_PARTS'&&value.object.route==='/approvals/parts/'+value.object.id||fee&&value.object.type==='FEE'&&value.object.route==='/fees/'+value.object.id||ticket&&value.object.type==='TICKET'&&value.object.route==='/tickets/'+value.object.id))throw Error('站内信业务深链无法确认。')
 return value as unknown as Message
}
export function parseMessagePage(value:unknown,page:number,size=20):MessagePage{
 if(!object(value)||!Array.isArray(value.items)||!Number.isSafeInteger(value.total)||Number(value.total)<0||value.page!==page||value.size!==size||value.items.length>size||value.items.length>Number(value.total))throw Error('站内信列表无法确认。')
 const items=value.items.map(parseMessage)
 if(new Set(items.map(m=>m.id)).size!==items.length)throw Error('站内信列表重复。')
 return {items,total:Number(value.total),page,size}
}
export function parseReadReceipt(value:unknown,operationId:string,messageId:string):ReadReceipt{
 if(!object(value)||value.operationId!==operationId||value.messageId!==messageId||value.state!=='READ'||!instant(value.readAt))throw Error('原标记操作归属无法确认。')
 return value as unknown as ReadReceipt
}
export function notificationTransport(auth:NotificationSession,active:()=>boolean,context:()=>string,send:typeof request=request):NotificationTransport{
 return {async request(method,path,operationId,current=()=>true){
  const stamp=context(),scope=captureRequestScope(auth,()=>active()&&current()&&context()===stamp)
  if(!notificationOwner(auth)||!active())return {status:401,body:{code:'NORMAL_EMPLOYEE_REQUIRED'}}
  let allowed=false
  if(method==='GET'){
   allowed=/^\/api\/notification\/(?:messages|read-operations)\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(path)
   if(path.startsWith('/api/notification/messages?')){const q=new URLSearchParams(path.slice(path.indexOf('?')+1));allowed=q.size===2&&q.getAll('page').length===1&&/^[1-9][0-9]{0,4}$/.test(q.get('page')??'')&&q.getAll('size').length===1&&['20','50','100'].includes(q.get('size')??'')}
  }else allowed=/^\/api\/notification\/messages\/[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\/read$/.test(path)&&uuid(operationId)
  if(!allowed)throw Error('NOTIFICATION_PATH_NOT_ALLOWED')
  const csrf=auth.csrf
  if(method==='POST'&&(!csrf||csrf.contextKind!=='AUTH'||csrf.loginAttemptId!==auth.identity?.loginAttemptId||!Number.isFinite(Date.parse(csrf.expiresAt))||Date.parse(csrf.expiresAt)<=Date.now()))return {status:403,body:{code:'CSRF_CONTEXT_UNCONFIRMED'}}
  try{
   const body=await send<unknown>(path.slice(4),{method,...(method==='POST'?{body:'{}',headers:{'Idempotency-Key':operationId!,'X-CSRF-TOKEN':csrf!.token}}:{})})
   return scope.current()?{status:200,body}:{status:503,body:{code:'STALE_CONTEXT'}}
  }catch(e){
   if(!scope.current())return {status:503,body:{code:'STALE_CONTEXT'}}
   if(e instanceof ApiError){if(e.status===401||e.code==='CSRF_INVALID')auth.handleFailure(e);return {status:e.status||503,body:{code:e.code}}}
   throw e
  }
 }}
}
