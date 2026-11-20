import {uuid} from '../approval/approval-client'
import {parseReadReceipt,type NotificationOwner,type NotificationTransport,type ReadReceipt} from './notification-client'
export interface PendingRead{readonly operationId:string;readonly messageId:string}
export interface ReadSlot{pending:PendingRead|null;lease:number}
/** No credentials or message bodies are retained; unresolved writes keep their original UUID in memory. */
export const retainedNotificationReads=new Map<string,ReadSlot>()
export class NotificationReads{
 phase:'IDLE'|'SENDING'|'UNKNOWN'|'NOT_OBSERVED'|'SUCCEEDED'|'DENIED'|'ERROR'='IDLE'
 pending:PendingRead|null=null;result:ReadReceipt|null=null;error='';busy=false
 private alive=true;private epoch=0;private stamp='';private slotKey='';private slotLease=0
 constructor(private transport:NotificationTransport,private owner:()=>NotificationOwner|null,private key=()=>crypto.randomUUID(),private retained=retainedNotificationReads){}
 bind(){const stamp=JSON.stringify(this.owner());if(stamp===this.stamp)return;this.stamp=stamp;this.epoch++;this.pending=null;this.result=null;this.error='';this.busy=false;this.phase='IDLE';const owner=this.owner();if(!owner)return;this.slotKey=JSON.stringify([owner.tenantId,owner.actorId]);let slot=this.retained.get(this.slotKey);if(!slot){slot={pending:null,lease:0};this.retained.set(this.slotKey,slot)}this.slotLease=++slot.lease;this.pending=slot.pending;if(this.pending)this.phase='UNKNOWN'}
 dispose(){this.alive=false;this.epoch++;this.pending=null;this.result=null;this.busy=false}
 async start(messageId:string){if(!uuid(messageId))throw Error('INVALID_MESSAGE');if(!this.alive||!this.owner()||this.busy||this.pending||this.stamp!==JSON.stringify(this.owner()))return;const slot=this.retained.get(this.slotKey);if(!slot||slot.lease!==this.slotLease)return;if(slot.pending){this.pending=slot.pending;this.phase='UNKNOWN';return}const operationId=this.key();if(!uuid(operationId))throw Error('INVALID_OPERATION');this.pending=Object.freeze({operationId,messageId});slot.pending=this.pending;await this.perform(false)}
 query(){if(this.pending)return this.perform(true)}
 retry(){if(this.phase==='NOT_OBSERVED'&&this.pending)return this.perform(false)}
 private async perform(query:boolean){
  const pending=this.pending;if(!pending||!this.alive||!this.owner()||this.busy||this.stamp!==JSON.stringify(this.owner()))return
  const epoch=++this.epoch,stamp=this.stamp,slotKey=this.slotKey,lease=this.slotLease,current=()=>this.alive&&epoch===this.epoch&&stamp===JSON.stringify(this.owner())&&this.retained.get(slotKey)?.lease===lease&&this.pending===pending
  this.busy=true;this.error='';this.phase=query?'UNKNOWN':'SENDING'
  try{
   const r=await this.transport.request(query?'GET':'POST',query?'/api/notification/read-operations/'+pending.operationId:'/api/notification/messages/'+pending.messageId+'/read',query?undefined:pending.operationId,current)
   if(!current())return
   if(r.status===200){this.result=parseReadReceipt(r.body,pending.operationId,pending.messageId);this.phase='SUCCEEDED';this.pending=null;const slot=this.retained.get(slotKey);if(slot?.lease===lease)slot.pending=null}
   else if(r.status===401||r.status===403){this.phase='DENIED';this.result=null;this.error='当前身份或权限无法确认，请重新核验。'}
   else if(query&&r.status===404&&!!r.body&&typeof r.body==='object'&&'code' in r.body&&r.body.code==='OPERATION_NOT_OBSERVED'){this.phase='NOT_OBSERVED';this.error='本次未观察到原操作；可显式用原操作重新标记。'}
   else if(r.status===0||r.status>=500){this.phase='UNKNOWN';this.error='标记结果未知，请查询原操作。'}
   else{this.phase='ERROR';this.result=null;this.error='当前操作无法继续（HTTP '+r.status+'）。'}
  }catch(e){if(current()){this.phase='UNKNOWN';this.result=null;this.error=e instanceof Error?e.message:'标记结果未知。'}}finally{if(this.alive&&epoch===this.epoch&&stamp===JSON.stringify(this.owner())&&this.retained.get(slotKey)?.lease===lease)this.busy=false}
 }
}
