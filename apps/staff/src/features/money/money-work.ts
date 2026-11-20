import {ApiError} from '../../api'
import {receipt,moneyActions,type MoneyAction} from './money-client'
export interface PendingMoney{group:string;action:MoneyAction;body:Record<string,unknown>;key:string}
export const retainedMoney=new Map<string,{pending:PendingMoney|null;lease:number}>()
export class MoneyWork{
 pending:PendingMoney|null=null;result:ReturnType<typeof receipt>|null=null;phase='IDLE';error='';busy=false
 private alive=true;private epoch=0;private lease=0;private slot='';private stamp=''
 constructor(private send:(method:'GET'|'POST',group:string,suffix:string,body?:Record<string,unknown>,current?:()=>boolean)=>Promise<unknown>,private owner:()=>{slot:string;stamp:string}|null){}
 bind(){this.epoch++;this.result=null;this.pending=null;this.busy=false;this.error='';this.phase='IDLE';const o=this.owner();this.stamp=o?.stamp??'';this.slot=o?.slot??'';if(!o)return;let s=retainedMoney.get(o.slot);if(!s){s={pending:null,lease:0};retainedMoney.set(o.slot,s)}this.lease=++s.lease;this.pending=s.pending;if(this.pending)this.phase='UNKNOWN'}
 dispose(){this.alive=false;this.epoch++}
 async start(group:string,action:MoneyAction,body:Record<string,unknown>){const s=retainedMoney.get(this.slot);if(!this.alive||this.busy||this.pending||!s||s.lease!==this.lease||this.owner()?.stamp!==this.stamp)return;const key=crypto.randomUUID();this.pending=Object.freeze({group,action,key,body:JSON.parse(JSON.stringify({...body,operationId:key}))});s.pending=this.pending;await this.perform(false)}
 async query(){if(this.pending)await this.perform(true)}
 async retry(){if(this.phase==='NOT_OBSERVED'&&this.pending)await this.perform(false)}
 next(){if(this.phase==='SUCCEEDED'){this.result=null;this.phase='IDLE'}}
 private async perform(query:boolean){const p=this.pending;if(!p||this.busy||!this.alive)return;const n=++this.epoch,stamp=this.stamp,slot=this.slot,lease=this.lease,current=()=>this.alive&&n===this.epoch&&this.owner()?.stamp===stamp&&retainedMoney.get(slot)?.lease===lease;this.busy=true;this.error='';try{const r=await this.send(query?'GET':'POST',p.group,query?'/operations/'+p.key:'/'+p.action,query?undefined:p.body,current);if(!current())return;const confirmed=receipt(r,p.group,p.key);if(confirmed.action!==moneyActions[p.action])throw Error('原动作不匹配');this.result=confirmed;this.phase='SUCCEEDED';this.pending=null;retainedMoney.get(slot)!.pending=null}catch(e){if(current()){this.result=null;this.phase=query&&e instanceof ApiError&&e.status===404?'NOT_OBSERVED':e instanceof ApiError&&[401,403,409].includes(e.status)?'DENIED':'UNKNOWN';this.error=e instanceof Error?e.message:'结果未知，请查询原UUID'}}finally{if(current())this.busy=false}}
}
