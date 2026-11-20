<script setup lang="ts">
import {computed,onBeforeUnmount,reactive,ref,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {useSession} from '../../session'
import {ObjectLease} from '../../object-lease'
import {uuid} from '../finance/finance-client'
import {canWriteMoney,cents,yuan} from './money-client'
import {canReadReviews,CorrectionReviewWork,parseReviewSummary,reviewTransport} from './money-review'
import {MoneyVoucher} from './money-voucher'

const route=useRoute(),router=useRouter(),auth=useSession()
const group=computed(()=>String(route.params.groupId??'')),reviewInput=ref(''),consent=ref(false),error=ref('')
let alive=true
const owner=()=>auth.phase==='NORMAL'&&canReadReviews(auth.identity)?{
 slot:JSON.stringify(['review-actions',auth.identity!.tenant!.id,auth.identity!.userId,group.value]),
 stamp:JSON.stringify([auth.identity!.tenant!.id,auth.identity!.userId,auth.generation,auth.identity!.loginAttemptId,group.value])
}:null
const context=()=>owner()?.stamp??'',send=reviewTransport(auth,()=>alive)
const work=reactive(new CorrectionReviewWork(send,owner))
const vouchers=reactive(new MoneyVoucher(auth,()=>{const value=owner();return value?{...value,slot:value.slot+':evidence'}:null}))
const detail=reactive(new ObjectLease(auth,()=>context()+reviewInput.value,async()=>parseReviewSummary(await send('GET',group.value,reviewInput.value),reviewInput.value)))
const form=reactive({reviewId:String(crypto.randomUUID()),kind:'REDUCTION',accountVersion:'',groupVersion:'',billId:'',lineId:'',paymentId:'',amount:'',coverageDigest:'',reason:'',ticketId:''})
const file=ref<File|null>(null)
const mayCreate=computed(()=>canWriteMoney(auth.identity,form.kind==='REDUCTION'?'lower':'reverse'))
const customer=computed(()=>auth.identity?.identityType==='CUSTOMER')
const mayApprove=computed(()=>detail.value?.kind==='REDUCTION'&&canWriteMoney(auth.identity,'lower')&&!!detail.value.applicantUserId&&detail.value.applicantUserId!==auth.identity?.userId&&detail.value.events.includes('CUSTOMER_CONFIRMED')&&!detail.value.events.includes('FINANCE_APPROVED'))
async function load(){consent.value=false;detail.clear();if(owner()&&uuid(reviewInput.value))await detail.refresh()}
async function submit(){
 if(!consent.value||!mayCreate.value||work.pending)return
 error.value=''
 try{
  const correction=form.kind==='CORRECTION',evidence=vouchers.current?.fact
  if(correction&&(!evidence||evidence.state!=='READY'||evidence.businessId!==form.reviewId||evidence.purpose!=='FINANCE_CORRECTION'))throw Error('请先完成本申请的调查凭证上传')
  const reviewId=form.reviewId
  await work.startProposal(group.value,{reviewId,kind:form.kind,accountVersion:form.accountVersion,groupVersion:form.groupVersion,billId:form.billId,subjectId:correction?form.paymentId:form.lineId,lineId:correction?null:form.lineId,proposedCents:cents(form.amount),coverageDigest:correction?null:form.coverageDigest,evidenceFileId:correction?evidence!.fileId:null,evidenceDigest:correction?evidence!.sha256:null,reason:form.reason})
  if(work.phase==='SUCCEEDED')await router.push('/money-reviews/'+group.value+'/'+reviewId)
 }catch(e){error.value=e instanceof Error?e.message:'申请字段无法确认'}
 consent.value=false
}
async function decide(action:'confirm'|'approve'){
 if(!consent.value||!detail.value||work.pending)return
 error.value=''
 try{await work.startDecision(group.value,detail.value,action);if(work.phase==='SUCCEEDED')await load()}
 catch(e){error.value=e instanceof Error?e.message:'审批无法确认'}
 consent.value=false
}
function pick(event:Event){file.value=(event.target as HTMLInputElement).files?.[0]??null;consent.value=false}
async function prepare(){if(file.value)await vouchers.prepare(form.ticketId,group.value,form.reviewId,'FINANCE_CORRECTION',file.value)}
function resetForm(){
 Object.assign(form,{reviewId:crypto.randomUUID(),kind:'REDUCTION',accountVersion:'',groupVersion:'',billId:'',lineId:'',paymentId:'',amount:'',coverageDigest:'',reason:'',ticketId:''})
 file.value=null;consent.value=false
}
function nextEvidence(){if(!work.pending&&!work.busy&&vouchers.current?.fact?.state==='READY'){vouchers.clearReady();resetForm()}}
watch(context,resetForm,{flush:'sync'})
watch(()=>[context(),route.fullPath],()=>{
 work.bind();vouchers.bind();detail.clear();consent.value=false;error.value='';file.value=null
 if(vouchers.current){form.reviewId=vouchers.current.command.businessId;form.ticketId=vouchers.current.ticket;form.kind='CORRECTION'}
 reviewInput.value=String(route.params.reviewId??'')
 if(uuid(reviewInput.value))void load()
},{immediate:true})
watch(reviewInput,()=>{detail.clear();consent.value=false},{flush:'sync'})
watch(()=>JSON.stringify(form),()=>{consent.value=false})
function visibility(){detail.clear();consent.value=false}
document.addEventListener('visibilitychange',visibility)
const timer=setInterval(()=>detail.expire(),1000)
onBeforeUnmount(()=>{alive=false;work.dispose();vouchers.dispose();detail.dispose();clearInterval(timer);document.removeEventListener('visibilitychange',visibility)})
</script>

<template>
 <section class="page-heading"><h1>应收调整与错录调查</h1><p>申请和审批保留原资金记录。实际降低应收、退款或更正须单独执行。</p></section>
 <section class="panel">
  <p>费用组 {{group}}</p>
  <label>申请UUID<input v-model="reviewInput" :disabled="!!work.pending"></label>
  <button :disabled="!uuid(reviewInput)||detail.busy||work.busy" @click="load">查询当前申请</button>
  <p v-if="detail.error" role="alert">{{detail.error}}</p>
  <template v-if="detail.value">
   <h2>{{detail.value.kind==='REDUCTION'?'应收降低申请':'错录调查申请'}}</h2>
   <p>申请 {{detail.value.reviewId}} · 账单 {{detail.value.billId}}</p>
   <p v-if="detail.value.lineId">批准行 {{detail.value.lineId}}</p>
   <p>申请金额 {{yuan(detail.value.proposedCents)}} 元 · 资金版本 {{detail.value.accountVersion}} · 费用组版本 {{detail.value.groupVersion}}</p>
   <p>{{detail.value.reason}}</p><p>已记录 {{detail.value.events.join('、')}}</p>
   <p v-if="!customer&&detail.value.kind==='REDUCTION'">客户确认链接 /customer/money-reviews/{{group}}/{{detail.value.reviewId}}</p>
   <template v-if="detail.value.kind==='REDUCTION'&&!work.pending">
    <label v-if="customer||mayApprove"><input v-model="consent" type="checkbox">已核对本次应收降低申请和原始依据</label>
    <button v-if="customer&&!detail.value.events.includes('CUSTOMER_CONFIRMED')" :disabled="!consent||work.busy" @click="decide('confirm')">客户确认本次应收降低</button>
    <p v-if="detail.value.applicantUserId===auth.identity?.userId">申请人不能批准自己的调整申请。</p>
    <button v-if="mayApprove" :disabled="!consent||work.busy" @click="decide('approve')">独立批准应收降低</button>
   </template>
   <RouterLink v-if="!customer" :to="'/money/'+group">返回资金登记与错录独立复核</RouterLink>
  </template>
 </section>
 <section v-if="!customer&&!route.params.reviewId" class="panel">
  <h2>新建申请</h2><p>申请 {{form.reviewId}}</p>
  <label>申请类型<select v-model="form.kind" :disabled="!!vouchers.current||!!work.pending"><option value="REDUCTION">降低应收</option><option value="CORRECTION">错录调查</option></select></label>
  <label>当前资金账户版本<input v-model="form.accountVersion" inputmode="numeric"></label>
  <label>当前费用组版本<input v-model="form.groupVersion" inputmode="numeric"></label>
  <label>账单UUID<input v-model="form.billId"></label>
  <label v-if="form.kind==='REDUCTION'">批准行UUID<input v-model="form.lineId"></label>
  <label v-else>原收款UUID<input v-model="form.paymentId"></label>
  <label>{{form.kind==='REDUCTION'?'本行降低后金额（元）':'申请核实的实际金额（元）'}}<input v-model="form.amount" inputmode="decimal"></label>
  <label v-if="form.kind==='REDUCTION'">权威覆盖摘要<input v-model="form.coverageDigest"></label>
  <template v-else>
   <label>原工单UUID<input v-model="form.ticketId" :disabled="!!vouchers.current"></label>
   <input type="file" accept="image/png,image/jpeg,application/pdf" :disabled="!!vouchers.current" @change="pick">
   <button :disabled="!file||vouchers.busy||!!vouchers.current" @click="prepare">准备调查凭证</button>
   <p v-if="vouchers.error" role="alert">{{vouchers.error}}</p>
   <template v-if="vouchers.current">
    <p>原上传 {{vouchers.current.command.operationId}} · {{vouchers.current.phase}}</p>
    <button :disabled="vouchers.busy" @click="vouchers.perform('query')">查询原调查凭证上传</button>
    <button v-if="['PREPARED','NOT_OBSERVED'].includes(vouchers.current.phase)" :disabled="vouchers.busy" @click="vouchers.perform('register')">登记原调查凭证</button>
    <button v-if="vouchers.current.fact?.state==='UPLOADING'" :disabled="vouchers.busy" @click="vouchers.perform('upload')">上传原调查凭证内容</button>
    <template v-if="vouchers.current.fact?.state==='READY'"><p>调查凭证已就绪</p><button :disabled="!!work.pending||work.busy" @click="nextEvidence">明确准备另一份调查申请</button></template>
   </template>
  </template>
  <label>申请依据与原因<textarea v-model="form.reason" maxlength="500"/></label>
  <label><input v-model="consent" type="checkbox">确认按当前原始事实提交本申请</label>
  <button :disabled="!consent||!mayCreate||work.busy||!!work.pending" @click="submit">提交资金审批申请</button>
 </section>
 <section class="panel">
  <p v-if="error||work.error" role="alert">{{error||work.error}}</p>
  <p v-if="work.phase==='SUCCEEDED'" role="status">原审批动作已确认，请重新查询当前申请。</p>
  <template v-if="work.pending">
   <p>原申请 {{work.pending.review}} · 原操作 {{work.pending.key}} · {{work.phase}}</p>
   <button :disabled="work.busy" @click="work.query()">只查询原审批操作</button>
   <button v-if="work.phase==='NOT_OBSERVED'" :disabled="work.busy" @click="work.retry()">明确原UUID原参数继续审批操作</button>
  </template>
 </section>
</template>

<style scoped>label{display:block;margin:12px 0}input:not([type=checkbox]),textarea,select{display:block;width:520px;max-width:100%;padding:8px;font:inherit;box-sizing:border-box}button{margin:6px;padding:8px;font:inherit}p{overflow-wrap:anywhere}</style>
