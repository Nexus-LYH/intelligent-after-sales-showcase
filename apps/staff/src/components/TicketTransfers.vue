<script setup lang="ts">
import { ref, watch, inject } from 'vue'
import { useRoute } from 'vue-router'
import type { TicketDetail } from '../ticket-api'
import { transferApi, transferStates, validTransfer, type TransferRequest } from '../ticket-transfer-api'
import { useTicketWork } from '../ticket-work'
import { customerPortal } from '../portal'
import { ApiError } from '../api'
import { ticketReadDenied,isObjectReadDenied } from '../ticket-access'
const props=defineProps<{ticket:TicketDetail}>(), route=useRoute(), work=useTicketWork(()=>route.fullPath), denyObject=inject(ticketReadDenied,undefined)
const items=ref<TransferRequest[]>([]), total=ref(0), page=ref(1)
const stamp=()=>JSON.stringify([props.ticket.id,props.ticket.roundId,props.ticket.rowVersion,props.ticket.assignment?.assignmentId,props.ticket.currentTransfer?.transferRequestId])
async function load(next=1){
 if(customerPortal){items.value=props.ticket.transferHistory || [];total.value=items.value.length;return}
 const context=stamp(), scope=work.begin(()=>context===stamp());items.value=[];total.value=0
 try{const r=await transferApi.history(props.ticket.id,next,props.ticket.roundId);if(!scope.current())return
  if(!Array.isArray(r.items)||r.items.some(t=>!validTransfer(t,props.ticket.id,props.ticket.roundId)))throw new ApiError(503,'TRANSFER_PROJECTION_INVALID','转派历史投影无法确认')
  items.value=r.items;total.value=r.total;page.value=r.page
 }catch(e){if(scope.current()&&isObjectReadDenied(e))denyObject?.();work.fail(e,scope)}finally{scope.finish()}
}
watch(stamp,()=>void load(),{immediate:true})
</script>
<template><section class="panel ticket-transfers"><h2>{{customerPortal?'公开交接信息':'转派与协调'}}</h2>
 <p v-if="customerPortal && ticket.currentEngineerUserId">{{ticket.readOnly?'本历史轮处理人':'当前处理人'}} {{ticket.currentEngineerUserId}} · 部门 {{ticket.currentDepartmentId}}；以服务端当前归属为准，交接不代表已修好。</p>
 <section v-if="!customerPortal && ticket.currentTransfer" class="notice current-transfer"><h3>转派待确认，原负责人继续负责</h3><p>原工程师 {{ticket.currentTransfer.sourceEngineerUserId}} · 源分派 {{ticket.currentTransfer.sourceAssignmentId}}</p><p>指定目标 {{ticket.currentTransfer.targetEngineerUserId}} · 部门 {{ticket.currentTransfer.targetDepartmentId}}</p><p>服务端期限 {{ticket.currentTransfer.expiresAt}} · {{transferStates[ticket.currentTransfer.effectiveState || ticket.currentTransfer.state]}}</p><p v-if="ticket.currentTransfer.expiredAtPending">服务器已报告到期，超时持久事实待系统落账；查询不写入，也不恢复旧负责人权限。</p><p style="white-space:pre-wrap">{{ticket.currentTransfer.reason}}</p><p>接受前不更换当前负责人或授予目标完整工单/附件权。拒绝、撤回、超时不回退工单当前状态。</p></section>
 <section v-if="!customerPortal && ticket.transferCoordination?.required" class="notice transfer-coordination"><h3>转派待协调{{ticket.transferCoordination.pendingRequestId?'（新请求确认中）':''}}</h3><p>原因 {{ticket.transferCoordination.reasons.join('、')}} · 原负责人当前资格 {{ticket.transferCoordination.sourceQualified?'有效':'失效，不恢复权限'}}</p><p>由当前授权客服/主管重新选择合格目标；新请求不等于交接完成。</p></section>
 <p v-if="work.message.value" role="alert">{{work.message.value}} {{work.trace.value}}</p>
 <article v-for="t in items" :key="t.transferRequestId" class="record-entry transfer-history"><h3>{{transferStates[t.effectiveState || t.state] || '交接状态待核对'}}</h3><p>源负责人 {{t.sourceEngineerUserId}} → 目标 {{t.targetEngineerUserId}} · {{t.requestedAt}} · 确认/终止 {{t.decision?.decidedAt || '尚未确认'}}</p><p v-if="!customerPortal" style="white-space:pre-wrap">内部转派理由：{{t.reason}}</p><p v-if="!customerPortal && t.decision?.reason" style="white-space:pre-wrap">决定或终止原因：{{t.decision.reason}}</p><p v-if="t.state!=='ACCEPTED'">该记录不表示已换人，也不将工单恢复为发起时状态。</p></article>
 <template v-if="!customerPortal"><el-pagination :current-page="page" :total="total" :page-size="20" layout="prev, pager, next, total" @current-change="load"/><el-button :disabled="work.busy.value" @click="load(page)">重新查询转派历史</el-button></template>
</section></template>
