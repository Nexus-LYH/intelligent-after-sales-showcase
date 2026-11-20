<script setup lang="ts">
import {computed,onUnmounted,reactive,watch} from 'vue'
import {ApprovalWork} from './approval-work'
import {parseApproval,type ApprovalOwner,type ApprovalTransport,type SubmitInput,type ApprovalAction,type ChargeMode} from './approval-client'
export interface ApprovalTicketContext {ticketId:string;assignmentId:string;departmentId:string;label:string}
export interface CatalogOption {id:string;name:string;code?:string;model?:string;unit?:string}
const props=defineProps<{owner:ApprovalOwner|null;contextKey:string;transport:ApprovalTransport;ticketContext?:ApprovalTicketContext|null;warehouses?:CatalogOption[];parts?:CatalogOption[];catalogLoading?:boolean;applicationId?:string;allowSubmit?:boolean;selection?:SubmitInput;allowedActions?:ApprovalAction[]}>()
const emit=defineEmits<{warehouse:[id:string];changed:[id:string]}>()
let mounted=true,epoch=0
const boundary:ApprovalTransport={request(method,path,body,headers,current=()=>true){const slot=epoch,valid=()=>mounted&&slot===epoch&&current();return (props.transport.scoped?.(valid)??props.transport).request(method,path,body,headers,valid)}}
const work=reactive(new ApprovalWork(boundary,()=>props.owner))
const form=reactive({warehouseId:'',partId:'',quantity:1,purpose:'',chargeMode:'' as ChargeMode|''}),detail=reactive({reason:''}),local=reactive({error:''})
const stateLabels:Record<string,string>={WAITING_CONFIGURATION:'等待审批配置',PENDING:'待审核',APPROVED:'已批准（未出库）',REJECTED:'已拒绝',WITHDRAWN:'已撤回'}
const actions=computed(()=>work.phase==='IDLE'?(props.allowedActions?props.allowedActions.flatMap(a=>a==='decision'?['approve','reject']:a==='withdraw'?['withdraw']:[]):work.detail?.allowedActions??[]):[])
let alive=true,leaseSlot=0,leaseUntil=0
function clearDetail(){leaseUntil=0;work.detail=null}
async function refresh(){const id=work.detail?.id||props.applicationId;if(!id||!props.owner)return;const slot=++leaseSlot,stamp=JSON.stringify(props.owner),context=props.contextKey,current=()=>alive&&slot===leaseSlot&&stamp===JSON.stringify(props.owner)&&context===props.contextKey
 try{const r=await (props.transport.scoped?.(current)??props.transport).request('GET',`/api/approval/parts/${id}`,undefined,undefined,current);if(!current())return;if(r.status!==200){clearDetail();local.error=`申请访问未确认（HTTP_${r.status}）`;return};const value=parseApproval(r.body);if(value.id!==id||value.submission.tenantId!==props.owner.tenantId)throw Error('APPROVAL_OWNER_MISMATCH');work.detail=value;if(!work.pending)work.phase='IDLE';leaseUntil=Date.now()+30000;local.error=''}catch(error){if(current()){clearDetail();local.error=error instanceof Error?error.message:'申请访问未确认'}}}
watch(()=>[props.owner?.namespace,props.owner?.tenantId,props.owner?.actorId,props.owner?.generation,props.owner?.attemptId,props.contextKey],()=>{epoch++;++leaseSlot;leaseUntil=0;work.bind(props.contextKey);local.error='';detail.reason='';Object.assign(form,{warehouseId:'',partId:'',quantity:1,purpose:'',chargeMode:''});if(props.applicationId)void refresh()},{immediate:true,flush:'sync'})
watch(()=>form.warehouseId,id=>{form.partId='';emit('warehouse',id)})
watch(()=>props.applicationId,()=>{++leaseSlot;clearDetail();if(props.applicationId)void refresh()})
const timer=setInterval(()=>{if(leaseUntil&&Date.now()>=leaseUntil){clearDetail();void refresh()}else if(work.detail&&!work.busy&&leaseUntil-Date.now()<5000)void refresh()},1000)
onUnmounted(()=>{mounted=false;epoch++;alive=false;++leaseSlot;clearInterval(timer);work.dispose()})
const originalSelectionMatches=computed(()=>{const p=work.pending;if(!p)return false;if(p.action!=='submit')return p.applicationId===props.applicationId;return !!props.selection&&Object.entries(p.body).every(([k,v])=>k==='operationId'||(props.selection as unknown as Record<string,unknown>)[k]===v)})
async function invoke(action:()=>unknown){local.error='';try{await action();if(work.phase==='SUCCEEDED'&&work.detail)emit('changed',work.detail.id)}catch(error){local.error=error instanceof Error?error.message:'操作校验失败'}}
function submit(){
 if(props.selection&&props.allowedActions?.includes('submit'))return invoke(()=>work.submit(props.selection!))
 const context=props.ticketContext,chargeMode=form.chargeMode
 if(!context||!props.allowSubmit)return
 if(!chargeMode){local.error='请选择费用承担方式';return}
 if(!props.warehouses?.some(w=>w.id===form.warehouseId)||!props.parts?.some(p=>p.id===form.partId)){
  local.error='请从当前授权目录选择仓库和备件'
  return
 }
 const command:SubmitInput={...form,chargeMode,ticketId:context.ticketId,assignmentId:context.assignmentId,departmentId:context.departmentId}
 return invoke(()=>work.submit(command))
}
function decide(outcome:'APPROVE'|'REJECT'){const current=work.detail;if(current&&actions.value.includes(outcome==='APPROVE'?'approve':'reject'))return invoke(()=>work.decide(current.id,{expectedVersion:current.version,outcome,reason:detail.reason}))}
function withdraw(){const current=work.detail;if(current&&actions.value.includes('withdraw'))return invoke(()=>work.withdraw(current.id,{expectedVersion:current.version,reason:detail.reason}))}
</script>
<template>
 <section class="panel" aria-label="备件申请">
  <h2>备件申请</h2>
  <p v-if="ticketContext">当前工单：{{ticketContext.label}}。审批通过后才可申请预留，审批不表示已经出库。</p>
  <form v-if="!selection&&ticketContext&&allowSubmit" @submit.prevent="submit">
   <fieldset :disabled="!owner||work.busy||!!work.pending||catalogLoading">
    <label>仓库<select v-model="form.warehouseId" required><option value="">请选择授权仓库</option><option v-for="w in warehouses" :key="w.id" :value="w.id">{{w.name}}</option></select></label>
    <label>备件<select v-model="form.partId" required><option value="">请选择备件</option><option v-for="p in parts" :key="p.id" :value="p.id">{{p.code}} {{p.name}} {{p.model}}（{{p.unit}}）</option></select></label>
    <label>费用承担方式<select v-model="form.chargeMode" required><option value="">请选择费用承担方式</option><option value="FREE">服务商承担</option><option value="CUSTOMER_PAID">客户付费</option></select></label><label>数量<input v-model.number="form.quantity" type="number" min="1" max="2147483647" step="1" required></label>
    <label>用途<textarea v-model="form.purpose" required maxlength="1000" /></label>
    <button type="submit">提交备件申请</button>
   </fieldset>
  </form>
  <form v-if="selection" @submit.prevent="submit"><fieldset :disabled="!owner||work.busy||!!work.pending||!allowedActions?.includes('submit')"><p>已核验选择：{{selection.chargeMode==='FREE'?'服务商承担':selection.chargeMode==='CUSTOMER_PAID'?'客户付费':'费用承担方式未明确'}} · 数量 {{selection.quantity}} · 用途 {{selection.purpose}}</p><button type="submit">提交已确认申请</button></fieldset></form>
  <p role="status">{{work.phase==='UNKNOWN'?'结果待核对':work.phase==='SENDING'?'正在提交':work.phase==='NOT_OBSERVED'?'尚未观察到原操作':work.phase==='SUCCEEDED'?'原操作已完成':''}}</p>
  <p v-if="local.error||work.error" role="alert">{{local.error||work.error}}</p>
  <section v-if="work.pending" aria-label="原操作恢复"><p>本次申请结果待核对，请查询原结果，不要另建申请。</p><p>原操作 {{work.pending.operationId}} · 原工单 {{work.pending.body.ticketId || work.pending.applicationId}} · 原数量 {{work.pending.body.quantity}} · 原用途/说明 {{work.pending.body.purpose || work.pending.body.reason}}</p><button :disabled="work.busy||!owner" @click="invoke(()=>work.queryOriginal())">查询原操作</button><button v-if="work.phase==='NOT_OBSERVED'" :disabled="work.busy||!owner||!originalSelectionMatches||!allowedActions?.includes(work.pending.action)" @click="invoke(()=>work.retryOriginal())">确认原参数重交</button></section>
  <section v-if="work.detail" aria-label="申请详情">
   <dl><dt>状态</dt><dd>{{stateLabels[work.detail.state] || work.detail.state}}</dd><dt>版本/修订</dt><dd>{{work.detail.version}} / {{work.detail.revision.number}}</dd><dt>费用承担方式</dt><dd>{{work.detail.chargeMode==='FREE'?'服务商承担':work.detail.chargeMode==='CUSTOMER_PAID'?'客户付费':'未明确，请重新查询当前申请核实'}}</dd><dt>数量</dt><dd>{{work.detail.submission.quantity}}</dd><dt>用途</dt><dd>{{work.detail.submission.purpose}}</dd></dl>
   <p v-if="work.phase==='SUCCEEDED'">原操作回执不代表当前审批状态，请刷新后继续。</p>
   <button :disabled="work.busy||!owner" @click="refresh">重新核对当前申请</button>
   <fieldset v-if="actions.length" :disabled="work.busy||!!work.pending||!owner"><label>处理说明<textarea v-model="detail.reason" maxlength="1000" /></label><button v-if="actions.includes('approve')" @click="decide('APPROVE')">同意</button><button v-if="actions.includes('reject')" @click="decide('REJECT')">拒绝</button><button v-if="actions.includes('withdraw')" @click="withdraw">撤回申请</button></fieldset>
  </section>
 </section>
</template>

<style scoped>label{display:block;margin:12px 0}input,select,textarea,button{font:inherit}input,select,textarea{max-width:100%;padding:8px;border:1px solid #cbd5d2;border-radius:6px}textarea{display:block;width:100%;box-sizing:border-box;min-height:80px;margin-top:6px}button{padding:8px 14px;margin:6px 8px 6px 0;border:1px solid #b9ccc7;border-radius:6px;background:#f6faf8;color:#125e53;cursor:pointer}button:disabled{opacity:.5;cursor:default}fieldset{border:1px solid #dfe8e4;border-radius:8px;padding:16px}dt{font-weight:600;margin-top:12px}dd{margin:4px 0 12px;overflow-wrap:anywhere}</style>
