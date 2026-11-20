<script setup lang="ts">
import type { ProcessingRecord } from '../ticket-action-api'
import { customerPortal } from '../portal'

defineProps<{ record: Partial<ProcessingRecord> }>()
</script>

<template>
  <dl v-if="!customerPortal && record.recordId" class="record-audit" aria-label="记录追溯">
    <dt>记录ID</dt><dd>{{ record.recordId }}</dd>
    <template v-if="record.actorUserId !== undefined">
      <dt>操作者</dt><dd>{{ record.actorUserId === '0' ? '系统' : '用户 ' + record.actorUserId }}</dd>
    </template>
    <template v-if="record.operationId">
      <dt>原操作UUID</dt><dd>{{ record.operationId }}</dd>
    </template>
    <template v-if="record.roundId">
      <dt>处理轮次</dt><dd>{{ record.roundId }}</dd>
    </template>
    <template v-if="record.assignmentId">
      <dt>分派ID</dt><dd>{{ record.assignmentId }}</dd>
    </template>
    <template v-if="record.rowVersion !== undefined">
      <dt>记录时工单版本</dt><dd>{{ record.rowVersion }}</dd>
    </template>
  </dl>
</template>

<style scoped>
.record-audit{display:grid;grid-template-columns:max-content minmax(0,1fr);gap:4px 12px;font-size:.9rem}
dt,dd{margin:0;overflow-wrap:anywhere}
dt{color:#50645e}
@media(max-width:520px){.record-audit{grid-template-columns:minmax(0,1fr)}dd{margin-bottom:6px}}
</style>
