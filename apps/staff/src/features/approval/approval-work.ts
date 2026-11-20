import { parseApproval, uuid, validateCommand, type ApprovalAction, type ApprovalOwner, type ApprovalTransport, type ApprovalView, type SubmitInput, type DecisionInput, type WithdrawInput } from './approval-client'
export type Phase='IDLE'|'SENDING'|'UNKNOWN'|'NOT_OBSERVED'|'SUCCEEDED'|'DENIED'|'CONFLICT'|'ERROR'
export interface PendingApproval { readonly action:ApprovalAction; readonly applicationId:string|null; readonly operationId:string; readonly body:Readonly<Record<string,unknown>>; readonly owner:string }
export interface RetainedApproval { pending:PendingApproval|null; lease:number }
/** Memory only, no cookies/tokens. Host may supply its own store and clear it on explicit account removal. */
export const retainedApprovalOperations=new Map<string,RetainedApproval>()
/** One instance per mounted page. UI context changes MUST call bind before another operation. */
export class ApprovalWork {
  phase:Phase='IDLE'; detail:ApprovalView|null=null; pending:PendingApproval|null=null; error=''; busy=false
  private epoch=0;private alive=true;private owner='';private boundIdentity='';private lease=0;private slotKey='';private slotLease=0
  constructor(private transport:ApprovalTransport,private identity:()=>ApprovalOwner|null,private key=()=>crypto.randomUUID(),private retained=retainedApprovalOperations){}
  private stamp(){return JSON.stringify(this.identity())}
  bind(context:string){const owner=this.stamp()+':'+context;if(owner!==this.owner){this.owner=owner;this.boundIdentity=this.stamp();this.epoch++;this.lease++;this.phase='IDLE';this.detail=null;this.pending=null;this.error='';this.busy=false;const identity=this.identity();this.slotKey=JSON.stringify([identity?.namespace,identity?.tenantId,identity?.actorId,context]);let slot=this.retained.get(this.slotKey);if(!slot){slot={pending:null,lease:0};this.retained.set(this.slotKey,slot)}this.slotLease=++slot.lease;if(slot.pending){this.pending=Object.freeze({...slot.pending,owner:this.stamp()});slot.pending=this.pending;this.phase='UNKNOWN'}}}
  dispose(){this.alive=false;this.epoch++;this.lease++;this.detail=null;this.pending=null;this.busy=false}
  private scope(){const epoch=this.epoch,identity=this.stamp(),lease=++this.lease,slotLease=this.slotLease;return()=>this.alive&&epoch===this.epoch&&identity===this.stamp()&&lease===this.lease&&this.retained.get(this.slotKey)?.lease===slotLease}
  async load(id:string){if(!this.alive||this.boundIdentity!==this.stamp()||!this.identity()||!uuid(id)||this.busy||this.pending)return;const current=this.scope();this.busy=true;try{const r=await this.transport.request('GET',`/api/approval/parts/${id}`,undefined,undefined,current);if(!current())return;if(r.status===200){const view=parseApproval(r.body);if(view.id!==id||view.submission.tenantId!==this.identity()?.tenantId)throw Error('RESULT_OWNER_MISMATCH');this.detail=view;this.error='';this.phase='IDLE'}else{this.detail=null;this.error=`HTTP_${r.status}`;if(r.status===401||r.status===403)this.phase='DENIED'}}catch(e){if(current()){this.detail=null;this.error=e instanceof Error?e.message:'LOAD_FAILED'}}finally{if(current())this.busy=false}}
  submit(input:SubmitInput){return this.start('submit',null,input)}
  decide(id:string,input:DecisionInput){return this.start('decision',id,input)}
  withdraw(id:string,input:WithdrawInput){return this.start('withdraw',id,input)}
  private async start(action:ApprovalAction,applicationId:string|null,input:SubmitInput|DecisionInput|WithdrawInput){
    if(!this.alive||this.boundIdentity!==this.stamp()||this.busy||this.pending||!this.identity())return
    const slot=this.retained.get(this.slotKey);if(!slot||slot.lease!==this.slotLease)return
    if(slot.pending){this.pending=slot.pending;this.phase='UNKNOWN';return}
    if(action!=='submit'&&!uuid(applicationId))throw Error('INVALID_APPLICATION')
    const body=structuredClone({...input,operationId:this.key()}) as Record<string,unknown>;validateCommand(action,body)
    this.pending=Object.freeze({action,applicationId,operationId:String(body.operationId),body:Object.freeze(body),owner:this.stamp()});slot.pending=this.pending;await this.post(this.pending)
  }
  private path(p:PendingApproval){return p.action==='submit'?'/api/approval/parts':`/api/approval/parts/${p.applicationId}/${p.action==='decision'?'decisions':'withdrawals'}`}
  private valid(p:PendingApproval){return this.pending===p&&p.owner===this.stamp()}
  private matches(p:PendingApproval,view:ApprovalView){
    if(view.submission.tenantId!==this.identity()?.tenantId||p.applicationId!==null&&view.id!==p.applicationId)throw Error('RESULT_OWNER_MISMATCH')
    const fact=p.action==='submit'?view.submission:p.action==='decision'?view.decision:view.withdrawal
    if(!fact||fact.operationId!==p.operationId)throw Error('RESULT_OPERATION_MISMATCH')
    const record=fact as unknown as Record<string,unknown>
    // Cost policy is stored separately from historical submission receipts.
    for(const [k,v]of Object.entries(p.body))if(k!=='previousApplicationId'&&k!=='chargeMode'&&record[k]!==v&&!(k==='reason'&&v===undefined&&record[k]===''))throw Error('RESULT_PARAMETERS_MISMATCH')
    if(p.action==='submit'&&view.submission.applicantId!==this.identity()?.actorId||p.action!=='submit'&&record.actorId!==this.identity()?.actorId)throw Error('RESULT_ACTOR_MISMATCH')
    if(p.action==='submit'&&(p.body.previousApplicationId??null)!==view.revision.previousApplicationId)throw Error('RESULT_REVISION_MISMATCH')
  }
  private async perform(p:PendingApproval,query:boolean){
    if(this.busy||!this.valid(p))return;const current=this.scope();this.busy=true;this.error='';this.phase=query?'UNKNOWN':'SENDING'
    try{const response=await this.transport.request(query?'GET':'POST',query?`/api/approval/parts/operations/${p.operationId}`:this.path(p),query?undefined:p.body,query?undefined:{'Idempotency-Key':p.operationId},()=>current()&&this.valid(p));if(!current()||!this.valid(p))return
      if(response.status===200){
        const view=parseApproval(response.body)
        this.matches(p,view)
        if(p.action==='submit'&&p.body.chargeMode!==undefined){
          const policyResponse=await this.transport.request('GET',`/api/approval/parts/${view.id}`,undefined,undefined,()=>current()&&this.valid(p))
          if(!current()||!this.valid(p))return
          if(policyResponse.status!==200)throw Error('COST_POLICY_UNCONFIRMED')
          const policy=parseApproval(policyResponse.body)
          if(policy.id!==view.id||policy.submission.tenantId!==view.submission.tenantId||policy.submission.operationId!==p.operationId||policy.chargeMode!==p.body.chargeMode)throw Error('COST_POLICY_MISMATCH')
          view.chargeMode=policy.chargeMode
        }
        this.detail=view;this.phase='SUCCEEDED';this.pending=null
        const slot=this.retained.get(this.slotKey);if(slot?.lease===this.slotLease)slot.pending=null
      }
      else if(response.status===401||response.status===403){this.detail=null;this.phase='DENIED';this.error=`HTTP_${response.status}`}
      else if(response.status===503){this.phase='UNKNOWN';this.error='结果未确认，请查询原操作。'}
      else if(query&&response.status===404&&typeof response.body==='object'&&response.body!==null&&'code'in response.body&&response.body.code==='OPERATION_NOT_OBSERVED'){this.phase='NOT_OBSERVED';this.error='暂未观察到原操作；核对后可显式原键重交。'}
      else {this.phase=response.status===409?'CONFLICT':'ERROR';this.error=`HTTP_${response.status}`}
    }catch(e){if(current()&&this.valid(p)){this.detail=null;this.phase='UNKNOWN';this.error=e instanceof Error?e.message:'RESULT_UNKNOWN'}}finally{if(current())this.busy=false}
  }
  private post(p:PendingApproval){return this.perform(p,false)}
  queryOriginal(){if(this.pending)return this.perform(this.pending,true)}
  retryOriginal(){if(this.phase==='NOT_OBSERVED'&&this.pending)return this.post(this.pending)}
}
