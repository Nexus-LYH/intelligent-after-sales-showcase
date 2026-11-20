<script setup lang="ts">
import {canReadFees} from '../features/fee-approval/fee-client'
import {canReadFinance} from '../features/finance/finance-client'
import { ref, watch, computed, provide, onBeforeUnmount } from 'vue'
import {currentPartsAssignment} from '../features/approval/approval-context'
import { ticketReadDenied } from '../ticket-access'
import { actorKey } from '../ticket-work'
import { forgetUnauthorizedTicketBody } from '../ticket-action-work'
import { useRoute } from 'vue-router'
import { tickets, type TicketDetail } from '../ticket-api'
import { useTicketWork } from '../ticket-work'
import { ApiError } from '../api'
import { customerPortal } from '../portal'
import { categories, statusLabel } from '../ticket-flow'
import RetainedRoundDrafts from '../components/RetainedRoundDrafts.vue'
import TicketRounds from '../components/TicketRounds.vue'
import TicketReopens from '../components/TicketReopens.vue'
import {reopenApi,validReopen,validRoundProjection} from '../ticket-reopen-api'
import TicketAttachments from '../components/TicketAttachments.vue'
import TicketActions from '../components/TicketActions.vue'
import TicketRecords from '../components/TicketRecords.vue'
import TicketRecordAudit from '../components/TicketRecordAudit.vue'
import TicketTransfers from '../components/TicketTransfers.vue'
import { validTransfer } from '../ticket-transfer-api'
import TicketCancellations from '../components/TicketCancellations.vue'
import { validCancellation } from '../ticket-cancellation-api'
import TicketSupplements from '../components/TicketSupplements.vue'
import { validSupplement } from '../ticket-supplement-api'
import TicketSolutions from '../components/TicketSolutions.vue'
import { duration, resolutionOverdue, validSolution } from '../ticket-solution-api'
const route = useRoute(), work = useTicketWork(() => route.fullPath), ticket = ref<TicketDetail | null>(null), notice = ref('')
const historical=computed(()=>typeof route.params.roundId==='string')
const getDetail=(id:string)=>historical.value?reopenApi.round(id,String(route.params.roundId)):tickets.detail(id)
function hideUnauthorized() { lease.begin().finish(); const id = ticket.value?.id || String(route.params.id); if (!customerPortal) forgetUnauthorizedTicketBody(actorKey(work.auth),id); ticket.value = null; notice.value = '当前工单访问权限已失效，已清除正文、处理草稿和附件预览。请重新查询当前授权，不能依据旧负责人或旧回执继续处理。' }
provide(ticketReadDenied,hideUnauthorized)
const lease = useTicketWork(() => route.fullPath)
let active = true
async function checkObjectLease() {
  const original = ticket.value
  if (!original || work.busy.value || lease.busy.value || work.auth.phase !== 'NORMAL') return
  const scope = lease.begin(() => active && ticket.value === original)
  try { const r = await getDetail(original.id); if (!scope.current()) return
    if (r.id !== original.id) throw new ApiError(503,'TICKET_RESPONSE_MISMATCH','当前对象响应不符')
    if (r.rowVersion !== original.rowVersion || r.roundId !== original.roundId || r.assignment?.assignmentId !== original.assignment?.assignmentId || JSON.stringify([r.allowedActions,r.qualificationStatus,r.transferCoordination,r.currentTransfer,r.transferDepartments]) !== JSON.stringify([original.allowedActions,original.qualificationStatus,original.transferCoordination,original.currentTransfer,original.transferDepartments])) { scope.finish(); void load() }
  } catch(e) { if (!scope.current()) return; if (e instanceof ApiError && (e.status === 403 || e.status === 404)) hideUnauthorized(); else { ticket.value = null; work.fail(e,work.begin()) } } finally { scope.finish() }
}
const leaseTimer = setInterval(() => void checkObjectLease(),30000)
function visibilityChanged() { if (document.visibilityState === 'hidden') { work.begin().finish(); lease.begin().finish(); ticket.value = null } else if (active && work.auth.phase === 'NORMAL') void load() }
document.addEventListener('visibilitychange',visibilityChanged)
onBeforeUnmount(() => { active = false; clearInterval(leaseTimer); document.removeEventListener('visibilitychange',visibilityChanged) })
const timeline = computed(() => (ticket.value?.timeline || []).filter(e => !customerPortal || e.type === 'SUBMITTED' || e.visibility === 'CUSTOMER_VISIBLE'))
async function load() {
  const scope = work.begin(); ticket.value = null
  try {
    const r = await getDetail(String(route.params.id)); if (!scope.current()) return
    if(!validRoundProjection(r,String(route.params.id),historical.value?String(route.params.roundId):undefined))throw new ApiError(503,'HISTORICAL_ROUND_MISMATCH','历史轮次投影无法确认，不开放写入口')
    if(r.currentReopen&&(!validReopen(r.currentReopen,r)||r.currentReopen.state!=='PENDING_REVIEW'||r.status!=='CLOSED'))throw new ApiError(503,'REOPEN_PROJECTION_INVALID','当前重开申请与源关闭轮次不符')
    if (r.currentTransfer && (!validTransfer(r.currentTransfer,r.id,r.roundId) || r.currentTransfer.sourceAssignmentId !== r.assignment?.assignmentId || !['ASSIGNED','IN_PROGRESS'].includes(r.status)) || customerPortal && (r.currentTransfer || r.transferCoordination || (r.transferHistory || []).some(t => !validTransfer(t,r.id,r.roundId,true))) || !validCancellation(r) || r.id !== String(route.params.id) || r.status === 'WAITING_INFORMATION' && (!r.currentSupplement || r.currentSupplement.state !== 'OPEN') || r.currentSupplement && (r.status !== 'WAITING_INFORMATION' || !validSupplement(r.currentSupplement,r) || !customerPortal && r.currentSupplement.resumeState === 'IN_PROGRESS' && r.currentSupplement.assignmentId !== r.assignment?.assignmentId) || r.currentSolution && (!validSolution(r.currentSolution,customerPortal)||r.currentSolution.roundId!==r.roundId) || customerPortal && r.timeline.some(e => e.visibility === 'INTERNAL')) throw new ApiError(503,'TICKET_PROJECTION_INVALID','当前工单投影无法确认，已停止显示')
    ticket.value = historical.value?{...r,readOnly:true}:r
  } catch (e) { if (scope.current() && e instanceof ApiError && (e.status === 403 || e.status === 404)) hideUnauthorized(); work.fail(e,scope) } finally { scope.finish() }
}
function changed(message: string) { notice.value = message; void load() }
watch(() => [route.params.id,route.params.roundId], () => { notice.value = ''; void load() }, { immediate: true })
const impact: Record<string,string> = { UNUSABLE:'无法使用',AFFECTED:'使用受影响',CONSULTATION:'一般咨询' }
const eventNames: Record<string,string> = { REOPEN_REQUEST:'重开申请（尚未重开）',REOPEN_DECISION:'重开审核决定',ROUND_RESPONSE:'本轮人工接手回复',ROUND_FIRST_RESPONSE:'本轮首次人工响应','REOPEN-REQUEST':'重开申请（尚未重开）','REOPEN-DECISION':'重开审核决定','ROUND-RESPONSE':'本轮人工接手回复', TRANSFER_ACCEPTED:'工程师交接已接受',TRANSFER_REQUEST:'转派请求',TRANSFER_DECISION:'交接决定',TRANSFER_WITHDRAW:'转派撤回', CANCEL_DIRECT:'直接取消',CANCEL_REQUEST:'取消申请',CANCEL_DECISION:'取消审核','CANCEL-DIRECT':'直接取消','CANCEL-REQUEST':'取消申请','CANCEL-DECISION':'取消审核', SUPPLEMENT_REQUEST:'公开补充要求',SUPPLEMENT_REPLY:'原报修人补充回复','SUPPLEMENT-REQUEST':'公开补充要求','SUPPLEMENT-REPLY':'原报修人补充回复', ACCEPT:'人工受理',REJECT:'驳回说明',RECORDS:'处理记录',ASSIGN:'分派记录','ASSIGNMENT-ACCEPT':'工程师接单','RELEASE-INVALID-ASSIGNMENT':'释放失效候选',SOLVE:'工程师提交解决方案',CUSTOMER_DECISION:'原报修人决定',CLOSE:'客服收尾关闭',SUBMITTED:'客户提交报修',ACCEPTED:'人工受理',ACCEPTANCE:'人工受理',REJECTED:'驳回说明',REJECTION:'驳回说明',PROCESSING:'处理记录',RECORD:'处理记录' }
</script>
<template>
  <section class="page-heading"><RouterLink to="/tickets">← 工单列表</RouterLink><RouterLink v-if="!historical&&!customerPortal&&canReadFees(work.auth.identity)" :to="'/tickets/'+route.params.id+'/fee-approvals'">内部费用审批</RouterLink><RouterLink v-if="!historical&&canReadFinance(work.auth.identity)" :to="'/tickets/'+route.params.id+'/finance'">此工单费用报价</RouterLink><h1>{{ customerPortal ? '报修详情' : '工单详情' }}</h1><p>保留原始报修及设备快照；解决与关闭以当前方案、客户决定和服务端事实为准。</p></section>
  <section class="panel"><p v-if="notice" role="status">{{ notice }}</p><p v-if="work.message.value" role="alert">{{ work.message.value }} {{ work.trace.value }}</p><p v-if="work.busy.value">正在核对当前工单权限…</p>
    <template v-if="ticket"><h2>{{ ticket.ticketNo }} · {{ ticket.title }}</h2><p class="ticket-status">{{ statusLabel(ticket.status) }}<span v-if="ticket.priority"> · {{ ticket.priority }}</span></p><p v-if="ticket.status === 'IN_PROGRESS'">工程师已接单，正在处理，尚无维修完成结果。</p><p v-if="ticket.rejectionReason" class="notice">驳回理由：{{ ticket.rejectionReason }}</p>
      <p v-if="ticket.status === 'PENDING_CUSTOMER_CONFIRMATION'" class="notice">工程师已提交方案，待原报修人确认。未确认前不视为已解决，不自动超时关闭。</p>
      <p v-if="ticket.status === 'RESOLVED'" class="notice">原报修人已确认当前方案，已解决，待授权客服核验关闭。</p>
      <section v-if="ticket.closure" class="notice"><h3>已关闭事实</h3><p>方案 {{ ticket.closure.solutionId }} · 服务端关闭时间 {{ ticket.closure.closedAt }}</p><p style="white-space:pre-wrap">{{ ticket.closure.closeMessage }}</p></section>
      <section v-if="ticket.timing" class="notice ticket-timing"><h3>本轮服务端计时事实</h3><p v-if="ticket.timing.clockStopped && ticket.timing.terminalReason === 'CANCELLED'">取消终止于 {{ ticket.timing.terminatedAt }}，两类等待与有效历时已冻结；没有客户CONFIRM，不计为已解决或最终解决达标。</p><p>本轮起点：{{ ticket.timing.roundStartedAt }}</p><p>首次提交方案：{{ ticket.timing.firstSolutionSubmittedAt || '尚未提交' }} · 首交耗时 {{ duration(ticket.timing.firstSubmissionElapsedMs) }}<span v-if="ticket.timing.firstSubmissionOverdue !== null && ticket.timing.firstSubmissionEffectiveMs == null">（{{ ticket.timing.firstSubmissionOverdue ? '超目标' : '目标内' }}）</span></p><p v-if="ticket.timing.firstSubmissionEffectiveMs != null">首交有效耗时（扣除首方案前合法补充等待）：{{ duration(ticket.timing.firstSubmissionEffectiveMs) }}<span v-if="ticket.timing.firstSubmissionOverdue !== null">（{{ ticket.timing.firstSubmissionOverdue ? '超目标' : '目标内' }}）</span></p><p v-if="ticket.timing.supplementDeductionLimitMs">累计待补充：{{ duration(ticket.timing.supplementWaitMs) }} · 合法补充扣除：{{ duration(ticket.timing.excludedSupplementMs) }} · 剩余补充额度：{{ duration(ticket.timing.remainingSupplementDeductionMs) }} · 当前请求等待：{{ duration(ticket.timing.currentSupplementWaitMs) }}</p><p>累计待确认：{{ duration(ticket.timing.confirmationWaitMs) }} · 合法累计扣除：{{ duration(ticket.timing.excludedConfirmationMs) }} · 剩余扣除额度：{{ duration(ticket.timing.remainingConfirmationDeductionMs) }}</p><p>{{ ticket.timing.terminalReason === 'CANCELLED' ? '取消终结有效历时（已停止，不计已解决）' : ticket.timing.finalFrozen ? '客户确认已冻结最终有效解决时长' : '当前有效解决时长（尚未最终确认）' }}：{{ duration(ticket.timing.effectiveResolutionMs) }} · 本轮目标 {{ duration(ticket.timing.resolutionTargetMs) }}<strong v-if="resolutionOverdue(ticket.timing) !== null">（{{ ticket.timing.finalFrozen ? '最终确认' : '当前有效时长' }}{{ resolutionOverdue(ticket.timing) ? '超目标' : '目标内' }}）</strong></p><p>首次提交时效与最终客户确认时效分别保留；实际完成时间不提前结束计时，关闭不延长已冻结结果。</p><p v-if="ticket.timing.needsCoordination" role="status">{{ ticket.timing.supplementDeductionLimitMs ? '本轮当前等待额度已耗尽，请协调原报修人；补充与方案确认各有独立72小时累计额度，超过额度继续计时，不自动回复、恢复状态、确认或关闭。此提示未发送通知。' : '本轮待确认累计已达72小时扣除上限，请协调原报修人；超过额度继续计时，不自动确认或关闭。此提示未发送通知。' }}</p></section>
      <dl class="details"><dt>设备快照</dt><dd>{{ ticket.deviceSnapshot.code }} · {{ ticket.deviceSnapshot.name }} · {{ ticket.deviceSnapshot.serialNumber }} · {{ ticket.deviceSnapshot.modelName }}</dd><dt>企业快照</dt><dd>{{ ticket.companySnapshot.code }} · {{ ticket.companySnapshot.name }}</dd><dt>原始故障描述</dt><dd style="white-space:pre-wrap">{{ ticket.description }}</dd><dt>客户影响程度</dt><dd>{{ impact[ticket.impact] }}（与最终优先级分别保留）</dd><dt>故障类别</dt><dd>{{ categories[ticket.categoryCode] || '待核对' }}</dd><dt>联系人</dt><dd>{{ ticket.contactName }} · {{ ticket.contactPhone }}</dd><dt>提交时间</dt><dd>{{ ticket.submittedAt }}</dd><template v-if="!customerPortal && ticket.sla"><dt>响应目标（24×7）</dt><dd>{{ ticket.sla.responseDueAt }}</dd><dt>解决目标（非完成事实）</dt><dd>{{ ticket.sla.resolutionDueAt }}</dd></template><template v-if="!customerPortal && ticket.firstResponseAt"><dt>首条人工响应</dt><dd>{{ ticket.firstResponseAt }} · 首响历史等级 {{ ticket.firstResponsePriority || '待核对' }}（后续受理调级不覆盖）</dd></template></dl>
      <h2>可见时间线</h2><article v-for="(event,i) in timeline" :key="i" class="record-entry"><p>{{ event.at || event.occurredAt }} · {{ eventNames[event.type] || '服务记录' }}</p><p v-if="event.body" style="white-space:pre-wrap">{{ event.body }}</p><TicketRecordAudit :record="event"/></article><TicketAttachments :files="ticket.attachments"/>
    </template><el-button :disabled="work.busy.value" @click="load">重新查询</el-button>
  </section><template v-if="ticket"><section v-if="!customerPortal && !historical && currentPartsAssignment(ticket,work.auth.identity)" class="panel"><RouterLink :to="`/tickets/${ticket.id}/parts`">申请维修备件</RouterLink></section><TicketRounds :ticket="ticket" :key="ticket.roundId"/><RetainedRoundDrafts :ticket="ticket"/><TicketReopens :ticket="ticket" :key="'reopen'+ticket.roundId"/><TicketTransfers :ticket="ticket"/><TicketCancellations :ticket="ticket"/><TicketSupplements :ticket="ticket"/><TicketSolutions :ticket="ticket"/><TicketActions v-if="!historical" :ticket="ticket" @changed="changed"/><TicketRecords :ticket-id="ticket.id" :version="ticket.rowVersion" :round-id="ticket.roundId"/></template>
</template>





