<script setup lang="ts">
import { ref, watch } from 'vue'
import { inject } from 'vue'
import { ticketReadDenied, isObjectReadDenied } from '../ticket-access'
import { useRoute } from 'vue-router'
import { actionApi, type ProcessingRecord } from '../ticket-action-api'
import { useTicketWork } from '../ticket-work'
import { customerPortal } from '../portal'
import { ApiError } from '../api'
import TicketRecordAudit from './TicketRecordAudit.vue'
const denyObject = inject(ticketReadDenied,undefined)
const props = defineProps<{ ticketId: string; version: string; roundId?:string }>(), route = useRoute(), work = useTicketWork(() => route.fullPath)
const items = ref<ProcessingRecord[]>([]), total = ref(0), page = ref(1)
async function load(next = 1) {
  const id = props.ticketId, roundId=props.roundId, version = props.version, scope = work.begin(() => props.ticketId === id && props.version === version && props.roundId===roundId)
  items.value = []; total.value = 0
  try {
    const r = await actionApi.records(id, next,roundId); if (!scope.current()) return
    if(r.items.some(row=>row.roundId!==undefined&&row.roundId!==roundId))throw new ApiError(503,'RECORD_ROUND_MISMATCH','记录不属于所选轮次')
    if (customerPortal && r.items.some(row => row.visibility !== 'CUSTOMER_VISIBLE' || 'workHours' in row)) throw new ApiError(503, 'PUBLIC_PROJECTION_INVALID', '公开记录响应无法确认，已停止显示')
    items.value = r.items; total.value = r.total; page.value = r.page
  } catch (e) { if (scope.current() && isObjectReadDenied(e)) denyObject?.(); work.fail(e, scope) } finally { scope.finish() }
}
watch(() => [props.ticketId, props.version,props.roundId], () => void load(), { immediate: true })
</script>
<template><section class="panel"><h2>{{ customerPortal ? '公开处理记录' : '处理记录' }}</h2><p v-if="work.message.value" role="alert">{{ work.message.value }} {{ work.trace.value }}</p><p v-if="work.busy.value">正在核验记录访问权限…</p><p v-else-if="!items.length && !work.message.value">暂无可见处理记录。</p><article v-for="r in items" :key="r.recordId" class="record-entry"><p>{{ r.occurredAt }} · {{ customerPortal ? '公开记录' : '处理记录' }} <span v-if="!customerPortal">· {{ r.visibility === 'INTERNAL' ? '内部记录' : '客户可见' }}</span></p><p style="white-space:pre-wrap">{{ r.body }}</p><p v-if="!customerPortal && r.workHours !== undefined">单条工时：{{ r.workHours }} 小时</p><TicketRecordAudit :record="r"/></article><el-pagination :current-page="page" :total="total" :page-size="20" layout="prev, pager, next, total" @current-change="load"/></section></template>

