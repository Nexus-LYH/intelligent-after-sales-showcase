<script setup lang="ts">
import {computed,onBeforeUnmount,ref,watch} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import {useSession} from '../session'
import {tickets} from '../ticket-api'
import {actionApi} from '../ticket-action-api'
import {parsePartBinding} from '../features/finance/quote-draft'
import {feeTransport,parseFee,type FeeApplication} from '../features/fee-approval/fee-client'
import FeeExecutionPage from '../features/fee-execution/FeeExecutionPage.vue'
import {feeExecutionOwner,feeExecutionTransport,parseContext,type FeeExecutionContext} from '../features/fee-execution/fee-execution-client'
const auth=useSession(),route=useRoute(),router=useRouter(),ticketInput=ref(''),applicationInput=ref(''),context=ref<FeeExecutionContext|null>(null),error=ref(''),busy=ref(false)
let alive=true,lease=0
const ticketId=computed(()=>String(route.params.id??'')),applicationId=computed(()=>String(route.params.applicationId??'')),owner=computed(()=>feeExecutionOwner(auth.identity,auth.generation))
const uuid=(v:string)=>/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(v)
async function open(){if(uuid(ticketInput.value)&&uuid(applicationInput.value))await router.push('/tickets/'+ticketInput.value+'/fee-executions/'+applicationInput.value)}
async function load(){const mine=++lease;context.value=null;error.value='';if(!uuid(ticketId.value)||!uuid(applicationId.value)||!owner.value)return;busy.value=true;try{
 const feeWire=feeTransport(auth,()=>alive&&mine===lease),[raw,ticket,records]=await Promise.all([
  feeWire.send<FeeApplication>('GET','/approval/fees/'+applicationId.value,undefined,()=>mine===lease),tickets.detail(ticketId.value),actionApi.records(ticketId.value,1,undefined)
 ]);if(!alive||mine!==lease)return;const application=parseFee(raw,ticketId.value,applicationId.value)
 if(application.state!=='COMPLETED'||!application.completionId||!ticket.currentRoundId||ticket.currentRoundId!==application.roundId||!ticket.assignment?.assignmentId||ticket.assignment.state!=='IN_PROGRESS'||ticket.assignment.engineerUserId!==owner.value.actor)throw Error('当前审批、轮次或分派尚不能执行收费')
 const selected=records.items.filter(r=>r.type==='RECORDS'&&r.actorUserId===owner.value!.actor&&r.assignmentId===ticket.assignment!.assignmentId&&(!r.roundId||r.roundId===application.roundId)).map(r=>({id:r.recordId,ticketId:ticketId.value,roundId:application.roundId,assignmentId:ticket.assignment!.assignmentId,actorUserId:r.actorUserId,type:'RECORDS' as const,body:r.body,createdAt:r.occurredAt}))
 context.value=parseContext({companyId:application.companyId,ticketId:ticketId.value,ticketVersion:ticket.rowVersion,roundId:application.roundId,assignmentId:ticket.assignment.assignmentId,groupId:application.groupId,completionId:application.completionId,quoteId:application.quoteId,quoteVersion:application.quoteVersion,generation:application.generation,coverageDigest:application.coverageDigest,lines:application.lines.map(l=>({id:l.id,ticketId:l.ticketId,roundId:l.roundId,kind:l.kind as 'LABOR'|'OTHER'|'PART',amountCents:l.amountCents,...('partBinding'in l?{partBinding:parsePartBinding(l.partBinding)}:{})})),records:selected},owner.value)
 }catch(e){if(alive&&mine===lease)error.value=e instanceof Error?e.message:'收费执行上下文无法确认'}finally{if(alive&&mine===lease)busy.value=false}}
watch(()=>[route.fullPath,auth.generation],()=>void load(),{immediate:true});onBeforeUnmount(()=>{alive=false;lease++})
</script>
<template>
 <section class="page-heading"><RouterLink to="/tickets">← 工单</RouterLink><h1>收费执行</h1><p>批准、已执行和真实剩余金额/数量均来自服务端权威投影；事实不完整时页面隐藏余额并关闭提交。</p></section>
 <section class="panel"><label>工单UUID<input v-model="ticketInput"></label><label>已完成审批申请UUID<input v-model="applicationInput"></label><el-button :disabled="!uuid(ticketInput)||!uuid(applicationInput)" @click="open">核验并打开</el-button><el-button v-if="uuid(ticketId)&&uuid(applicationId)" :loading="busy" @click="load">重新核验当前上下文</el-button><p v-if="error" role="alert">{{error}}</p><p v-if="busy">正在核验当前审批、分派和真实处理记录…</p></section>
 <FeeExecutionPage v-if="context" :context="context" :owner="owner" :transport="feeExecutionTransport(auth,()=>alive)"/>
</template>
