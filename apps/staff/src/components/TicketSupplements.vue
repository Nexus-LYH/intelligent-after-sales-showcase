<script setup lang="ts">
import { ref, watch } from 'vue'
import { inject } from 'vue'
import { ticketReadDenied, isObjectReadDenied } from '../ticket-access'
import { useRoute } from 'vue-router'
import { ApiError } from '../api'
import type { TicketDetail } from '../ticket-api'
import { supplementApi, supplementStamp, validSupplement, type SupplementRequest } from '../ticket-supplement-api'
import { useTicketWork } from '../ticket-work'
import { statusLabel } from '../ticket-flow'
import TicketAttachments from './TicketAttachments.vue'
const denyObject = inject(ticketReadDenied,undefined)
const props = defineProps<{ticket:TicketDetail}>(), route = useRoute(), work = useTicketWork(() => route.fullPath)
const items = ref<SupplementRequest[]>([]), total = ref(0), page = ref(1)
async function load(next = 1) {
  const stamp = supplementStamp(props.ticket), scope = work.begin(() => stamp === supplementStamp(props.ticket)); items.value = []; total.value = 0
  try { const r = await supplementApi.history(props.ticket.id,next,props.ticket.roundId); if (!scope.current()) return; if (!Array.isArray(r.items) || r.items.some(s => !validSupplement(s,props.ticket))) throw new ApiError(503,'SUPPLEMENT_PROJECTION_INVALID','补充公开投影无法确认，已停止显示'); items.value = r.items; total.value = r.total; page.value = r.page } catch(e) { if (scope.current() && isObjectReadDenied(e)) denyObject?.();work.fail(e,scope)} finally {scope.finish()}
}
watch(() => supplementStamp(props.ticket), () => void load(), {immediate:true})
</script>
<template><section class="panel ticket-supplements"><h2>补充资料与回复历史</h2><section v-if="ticket.currentSupplement" class="notice current-supplement"><h3>当前待回复的补充要求</h3><p>请求 {{ ticket.currentSupplement.requestId }} · {{ ticket.currentSupplement.requestedAt }}</p><p style="white-space:pre-wrap">{{ ticket.currentSupplement.question }}</p><p>回复后恢复{{ statusLabel(ticket.currentSupplement.resumeState) }}；等待补充不是已受理、已解决或等待方案确认。请原报修人按当前服务端权限回复。</p></section><p v-if="work.message.value" role="alert">{{ work.message.value }} {{ work.trace.value }}</p><p v-if="work.busy.value">正在核对补充历史访问权限…</p><p v-if="!work.busy.value && !items.length && !work.message.value">暂无可见补充请求。</p><article v-for="s in items" :key="s.requestId" class="record-entry supplement-entry"><h3>{{ s.state === 'CANCELLED' ? '因工单取消终止（未伪造回复） · ' + s.termination?.terminatedAt : s.state === 'OPEN' ? '等待原报修人补充' : '已回复，恢复' + statusLabel(s.resumeState) }}</h3><p>请求 {{ s.requestId }} · {{ s.requestedAt }} · 发起人 {{ s.requestedBy }} · 原状态 {{ statusLabel(s.resumeState) }}</p><p style="white-space:pre-wrap">{{ s.question }}</p><template v-if="s.reply"><p>回复 {{ s.reply.replyId }} · {{ s.reply.repliedAt }} · 原报修人 {{ s.reply.repliedBy }}</p><p style="white-space:pre-wrap">{{ s.reply.body }}</p><TicketAttachments :files="s.reply.attachments"/></template></article><el-pagination :current-page="page" :total="total" :page-size="20" layout="prev, pager, next, total" @current-change="load"/><el-button :disabled="work.busy.value" @click="load(page)">重新查询补充历史</el-button></section></template>
