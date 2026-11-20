<script setup lang="ts">
import { ref, watch } from 'vue'
import { inject } from 'vue'
import { ticketReadDenied, isObjectReadDenied } from '../ticket-access'
import { useRoute } from 'vue-router'
import { ApiError } from '../api'
import type { TicketDetail } from '../ticket-api'
import { solutionApi, validSolution, type SolutionAttempt } from '../ticket-solution-api'
import { useTicketWork } from '../ticket-work'
import { customerPortal } from '../portal'
const denyObject = inject(ticketReadDenied,undefined)
const props = defineProps<{ ticket: TicketDetail }>(), route = useRoute(), work = useTicketWork(() => route.fullPath)
const items = ref<SolutionAttempt[]>([]), total = ref(0), page = ref(1)
const stamp = () => JSON.stringify([props.ticket.id,props.ticket.rowVersion,props.ticket.roundId,props.ticket.assignment?.assignmentId,props.ticket.currentSolution?.solutionId])
async function load(next = 1) {
  const context = stamp(), id = props.ticket.id, scope = work.begin(() => context === stamp())
  items.value = []; total.value = 0
  try {
    const r = await solutionApi.history(id,next,props.ticket.roundId); if (!scope.current()) return
    if (!Array.isArray(r.items) || r.items.some(row => !validSolution(row,customerPortal)||row.roundId!==props.ticket.roundId)) throw new ApiError(503,'SOLUTION_PROJECTION_INVALID','方案公开投影无法确认，已停止显示')
    items.value = r.items; total.value = r.total; page.value = r.page
  } catch (e) { if (scope.current() && isObjectReadDenied(e)) denyObject?.(); work.fail(e,scope) } finally { scope.finish() }
}
watch(stamp, () => void load(), { immediate: true })
</script>
<template>
  <section class="panel"><h2>解决方案与尝试历史</h2><p v-if="work.message.value" role="alert">{{ work.message.value }} {{ work.trace.value }}</p><p v-if="work.busy.value">正在核对方案访问权限…</p>
    <p v-if="!ticket.currentSolution">当前尚无已提交的解决方案。处理记录不等于客户已确认解决。</p>
    <section v-else class="notice current-solution"><h3>{{ticket.readOnly?'本历史轮最终解决方案':'当前解决方案'}}</h3><p>方案 {{ ticket.currentSolution.solutionId }} · 第 {{ ticket.currentSolution.attemptNo }} 次尝试 · 轮次 {{ ticket.currentSolution.roundId }}</p><dl class="details"><dt>故障原因</dt><dd style="white-space:pre-wrap">{{ ticket.currentSolution.cause }}</dd><dt>解决方式</dt><dd style="white-space:pre-wrap">{{ ticket.currentSolution.resolutionMethod }}</dd><dt>客户说明</dt><dd style="white-space:pre-wrap">{{ ticket.currentSolution.customerMessage }}</dd><dt>实际完成时间</dt><dd>{{ ticket.currentSolution.completedAt }}</dd><dt>确认累计工时</dt><dd>{{ ticket.currentSolution.confirmedWorkHours }} 小时</dd></dl><p v-if="ticket.currentSolution.termination">因工单取消终止 · {{ ticket.currentSolution.termination.terminatedAt }}；不计为已解决。</p><p v-if="ticket.currentSolution.decision">客户已{{ ticket.currentSolution.decision.decision === 'RETURN' ? '反馈未解决' : '确认已解决' }} · {{ ticket.currentSolution.decision.decidedAt }}</p><p v-if="ticket.currentSolution.decision?.reason">未解决理由：{{ ticket.currentSolution.decision.reason }}</p></section>
    <p v-if="!work.busy.value && !items.length && !work.message.value">暂无可见方案历史。</p>
    <article v-for="s in items" :key="s.solutionId" class="record-entry solution-attempt"><h3>第 {{ s.attemptNo }} 次方案{{ !ticket.readOnly && s.solutionId === ticket.currentSolution?.solutionId ? '（当前）' : '（历史，只读）' }}</h3>
      <p>方案 {{ s.solutionId }} · 轮次 {{ s.roundId }}</p><dl class="details"><dt>故障原因</dt><dd style="white-space:pre-wrap">{{ s.cause }}</dd><dt>解决方式</dt><dd style="white-space:pre-wrap">{{ s.resolutionMethod }}</dd><dt>客户说明</dt><dd style="white-space:pre-wrap">{{ s.customerMessage }}</dd><dt>实际完成时间</dt><dd>{{ s.completedAt }}</dd><dt>服务端提交时间</dt><dd>{{ s.submittedAt }}</dd><dt>本方案确认工时</dt><dd>{{ s.confirmedWorkHours }} 小时（累计快照，不与旧方案叠加）</dd><dt>原附件证据引用</dt><dd>{{ s.evidenceFileIds.length ? s.evidenceFileIds.join('、') : '未选择；不强制图片' }}</dd></dl>
      <p v-if="s.decision">原报修人决定：{{ s.decision.decision === 'RETURN' ? '反馈未解决' : '确认已解决' }} · {{ s.decision.decidedAt }}</p><p v-if="s.decision?.reason" style="white-space:pre-wrap">退回理由：{{ s.decision.reason }}</p><p v-if="s.termination">因工单取消终止 · {{ s.termination.terminatedAt }}；没有客户确认或未解决决定。</p><p v-if="!s.decision && !s.termination">该尝试尚无客户决定，不能自动视为满意或已关闭。</p>
    </article><el-pagination :current-page="page" :total="total" :page-size="20" layout="prev, pager, next, total" @current-change="load"/><el-button :disabled="work.busy.value" @click="load(page)">重新查询方案历史</el-button>
  </section>
</template>
