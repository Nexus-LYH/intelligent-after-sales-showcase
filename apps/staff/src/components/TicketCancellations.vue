<script setup lang="ts">
import { ref, watch } from 'vue'
import { inject } from 'vue'
import { ticketReadDenied, isObjectReadDenied } from '../ticket-access'
import { useRoute } from 'vue-router'
import type { TicketDetail } from '../ticket-api'
import { cancellationApi, validCancellationRequest, type CancellationRequest } from '../ticket-cancellation-api'
import { useTicketWork } from '../ticket-work'
import { ApiError } from '../api'
import { statusLabel } from '../ticket-flow'
const denyObject = inject(ticketReadDenied,undefined)
const props=defineProps<{ticket:TicketDetail}>(), route=useRoute(), work=useTicketWork(()=>route.fullPath)
const items=ref<CancellationRequest[]>([]), total=ref(0), page=ref(1)
const labels:Record<string,string>={PENDING_REVIEW:'待审核（尚未取消）',APPROVED:'批准并已取消',REJECTED:'申请被拒绝',NO_LONGER_APPLICABLE:'不再适用（客户已确认解决）'}
const stamp=()=>JSON.stringify([props.ticket.id,props.ticket.rowVersion,props.ticket.roundId,props.ticket.currentCancellation?.cancelRequestId,props.ticket.status])
async function load(next=1) {
  const context=stamp(), scope=work.begin(()=>context===stamp()); items.value=[];total.value=0
  try { const r=await cancellationApi.history(props.ticket.id,next,props.ticket.roundId); if(!scope.current())return
    if(!Array.isArray(r.items)||r.items.some(row=>!validCancellationRequest(row,props.ticket)))throw new ApiError(503,'CANCELLATION_PROJECTION_INVALID','取消申请公开投影无法确认，已停止显示')
    items.value=r.items;total.value=r.total;page.value=r.page
  } catch(e){if(scope.current() && isObjectReadDenied(e))denyObject?.();work.fail(e,scope)}finally{scope.finish()}
}
watch(stamp,()=>void load(),{immediate:true})
</script>
<template><section class="panel ticket-cancellations"><h2>取消申请与结果</h2>
  <section v-if="ticket.currentCancellation" class="notice current-cancellation"><h3>取消申请待审核，尚未取消</h3><p>当前主状态：{{ statusLabel(ticket.status) }}。原工程师责任和合法处理继续，服务计时不因申请停止。</p><p>当前申请 {{ ticket.currentCancellation.cancelRequestId }} · {{ ticket.currentCancellation.requestedAt }}</p><p style="white-space:pre-wrap">{{ ticket.currentCancellation.reason }}</p></section>
  <section v-if="ticket.cancellation" class="notice cancellation-fact"><h3>已取消事实（非维修完成）</h3><p>{{ ticket.cancellation.source === 'DIRECT' ? '原报修人直接取消' : '授权审核批准取消' }} · {{ ticket.cancellation.cancelledAt }} · 操作人 {{ ticket.cancellation.cancelledBy }}</p><p style="white-space:pre-wrap">{{ ticket.cancellation.reason || '未填写可选取消原因' }}</p><p>取消前状态：{{ statusLabel(ticket.cancellation.previousStatus) }}。活动处理责任已终止，原轮次、分派及工时历史保留。</p><p>仅普通维修本地取消；关联子流程未启用不表示库存、费用已清理或已结算。历史及已引用附件仍按当前权限读取。</p></section>
  <p v-if="ticket.assignment">{{ ticket.readOnly || ticket.status === 'CANCELLED' ? '历史分派' : '当前工程师责任（取消待审不移除）' }}：{{ ticket.assignment.engineerUserId }} · 部门 {{ ticket.assignment.departmentId }} · 分派 {{ ticket.assignment.assignmentId }}</p>
  <p v-if="work.message.value" role="alert">{{work.message.value}} {{work.trace.value}}</p><p v-if="work.busy.value">正在核对取消历史权限…</p>
  <article v-for="r in items" :key="r.cancelRequestId" class="record-entry cancellation-entry"><h3>{{labels[r.state]}}</h3><p>申请 {{r.cancelRequestId}} · {{r.requestedAt}} · 申请人 {{r.requestedBy}}</p><p>提交时状态（仅历史）：{{statusLabel(r.submittedStatus)}}；当前状态：{{statusLabel(ticket.status)}}。拒绝不回退到历史状态。</p><p style="white-space:pre-wrap">{{r.reason}}</p><p v-if="r.decision" style="white-space:pre-wrap">公开审核说明：{{r.decision.reason}} · {{r.decision.decidedBy}} · {{r.decision.decidedAt}}</p></article>
  <el-pagination :current-page="page" :total="total" :page-size="20" layout="prev, pager, next, total" @current-change="load"/><el-button :disabled="work.busy.value" @click="load(page)">重新查询取消历史</el-button>
</section></template>
