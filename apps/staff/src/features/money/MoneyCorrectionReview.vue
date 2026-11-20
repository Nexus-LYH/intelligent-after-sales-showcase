<script setup lang="ts">
import {computed,onBeforeUnmount,reactive,ref,watch} from 'vue'
import {useSession} from '../../session'
import {ObjectLease} from '../../object-lease'
import {uuid} from '../finance/finance-client'
import {canReadMoney,canWriteMoney,cents,yuan} from './money-client'
import {CorrectionReviewWork,correctionReview,reviewTransport} from './money-review'
const props=defineProps<{group:string}>(),auth=useSession(),reviewId=ref(''),verifiedAmount=ref(''),note=ref(''),consent=ref(false),error=ref('')
let alive=true
const owner=()=>auth.phase==='NORMAL'&&canReadMoney(auth.identity)?{slot:JSON.stringify([auth.identity!.tenant!.id,auth.identity!.userId,props.group]),stamp:JSON.stringify([auth.identity!.tenant!.id,auth.identity!.userId,auth.generation,auth.identity!.loginAttemptId,props.group])}:null
const context=()=>owner()?.stamp??'',send=reviewTransport(auth,()=>alive),work=reactive(new CorrectionReviewWork(send,owner))
const detail=reactive(new ObjectLease(auth,()=>context()+reviewId.value,async()=>correctionReview(await send('GET',props.group,reviewId.value),reviewId.value)))
const mayVerify=computed(()=>!!detail.value&&canWriteMoney(auth.identity,'reverse')&&detail.value.applicantUserId!==auth.identity?.userId&&!detail.value.events.includes('CORRECTION_VERIFIED')&&!detail.value.context.originalReversed&&detail.value.accountVersion===detail.value.context.currentAccountVersion)
watch(context,()=>{work.bind();detail.clear();reviewId.value=work.pending?.review??'';verifiedAmount.value='';note.value='';consent.value=false;error.value=''},{immediate:true,flush:'sync'})
watch(reviewId,()=>{detail.clear();verifiedAmount.value='';note.value='';consent.value=false;error.value=''},{flush:'sync'})
watch(()=>[verifiedAmount.value,note.value],()=>{consent.value=false},{flush:'sync'})
async function load(){detail.clear();consent.value=false;if(uuid(props.group)&&uuid(reviewId.value)&&owner())await detail.refresh()}
async function verify(){if(!mayVerify.value||!consent.value||!detail.value||work.pending)return;try{await work.start(props.group,detail.value,cents(verifiedAmount.value),note.value);if(work.phase==='SUCCEEDED')await load()}catch(e){error.value=e instanceof Error?e.message:'复核无法确认'}finally{consent.value=false}}
function hidden(){detail.clear();consent.value=false;verifiedAmount.value='';note.value=''}
document.addEventListener('visibilitychange',hidden);const timer=setInterval(()=>detail.expire(),1000)
onBeforeUnmount(()=>{alive=false;clearInterval(timer);detail.dispose();work.dispose();document.removeEventListener('visibilitychange',hidden)})
const evidenceUrl=(ticket:string,file:string)=>'/api/ticket/tickets/'+ticket+'/finance-voucher-files/'+file+'/content'
</script>
<template>
 <section v-if="canReadMoney(auth.identity)&&uuid(group)" class="panel">
  <h2>错录调查独立复核</h2>
  <label>调查申请编号<input v-model="reviewId" :disabled="!!work.pending" autocomplete="off"></label>
  <button :disabled="!uuid(reviewId)||detail.busy||work.busy" @click="load">查询申请与原收款证据</button>
  <p role="alert">{{error||detail.error||work.error}}</p>
  <template v-if="detail.value">
   <p>原收款 {{detail.value.originalPaymentId}}：{{yuan(detail.value.context.originalAmountCents)}} 元；申请核实金额 {{yuan(detail.value.proposedCents)}} 元。</p>
   <p>{{detail.value.context.originalDate}} · {{detail.value.context.originalMethod}} · {{detail.value.context.originalReference}}</p>
   <p>申请说明：{{detail.value.reason}}</p>
   <p><a :href="evidenceUrl(detail.value.context.ticketId,detail.value.context.originalEvidenceFileId)" target="_blank" rel="noopener">鉴权下载原收款凭证</a> · <a :href="evidenceUrl(detail.value.context.ticketId,detail.value.evidenceFileId)" target="_blank" rel="noopener">鉴权下载本次调查证据</a></p>
   <p v-if="detail.value.applicantUserId===auth.identity?.userId">申请人不能复核自己的调查申请。</p>
   <p v-if="detail.value.events.includes('CORRECTION_VERIFIED')">该申请已有复核记录，不能覆盖或再次审批。</p>
   <p v-if="detail.value.accountVersion!==detail.value.context.currentAccountVersion||detail.value.context.originalReversed">原资金状态已变化，请重新核查并按当前事实处理。</p>
   <template v-if="mayVerify">
    <label>我独立核实的实际金额（元）<input v-model="verifiedAmount" inputmode="decimal" :disabled="!!work.pending" autocomplete="off"></label>
    <label>核查依据与结论<textarea v-model="note" maxlength="500" :disabled="!!work.pending"/></label>
    <p>金额与申请有差异时请重新形成调查申请；已实际到账的款项不能仅凭错录申请抹除。</p>
    <label><input type="checkbox" v-model="consent" :disabled="!!work.pending">我已阅读原收款凭证和调查证据，确认填写的是独立核实的实际金额。</label>
    <button :disabled="!consent||work.busy||!!work.pending" @click="verify">提交独立复核记录</button>
   </template>
  </template>
  <p v-if="work.phase==='SUCCEEDED'" role="status">原复核操作已确认成功。资金执行仍须单独核验。</p>
  <template v-if="work.pending">
   <p>复核结果待确认，原操作编号 {{work.pending.key}}。请保留原请求并查询。</p>
   <button :disabled="work.busy" @click="work.query">查询原复核操作</button>
   <button v-if="work.phase==='NOT_OBSERVED'" :disabled="work.busy" @click="work.retry">明确按原编号和原参数重试</button>
  </template>
 </section>
</template>
<style scoped>label{display:block;margin:12px 0}input:not([type=checkbox]),textarea{width:440px;max-width:100%;padding:8px}button{padding:8px;margin:8px}p{overflow-wrap:anywhere}</style>
