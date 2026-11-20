<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '../../../staff/src/api'
import { tickets, verifyCreation, type CreationOperation, type CreationResult } from '../../../staff/src/ticket-api'
import { customerWriter, ownDraft, useTicketWork } from '../../../staff/src/ticket-work'
const route = useRoute(), router = useRouter(), work = useTicketWork(() => route.fullPath), input = ref(''), result = ref<CreationOperation | CreationResult | null>(null), missing = ref(false)
const key = computed(() => String(route.params.operationId || '')), draft = computed(() => ownDraft(key.value, work.auth))
const ticketId = computed(() => result.value && ('ticket' in result.value ? (result.value as CreationOperation).ticket?.ticketId : result.value.ticketId))
const ticketNo = computed(() => result.value && ('ticket' in result.value ? (result.value as CreationOperation).ticket?.ticketNo : result.value.ticketNo))
const uuid = (s: string) => /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(s)
async function run(action: 'query' | 'resume' | 'resend') {
  if (!uuid(key.value) || work.busy.value) return
  if (action !== 'query' && !customerWriter(work.auth)) return
  if (action === 'resend' && (!missing.value || !draft.value?.sent)) return
  if (action === 'resume' && result.value?.nextAction !== 'RESUME') return
  const original = key.value, d = draft.value, scope = work.begin(() => key.value === original && (!d || ownDraft(original, work.auth) === d))
  result.value = null; missing.value = false
  try {
    const r = action === 'query' ? await tickets.operation(original) : action === 'resume' ? await tickets.resume(original, work.auth.csrf!.token) : await tickets.create(original, d!.sent!, work.auth.csrf!.token)
    if (!scope.current()) return; verifyCreation(r, original); result.value = r
    if (d) { d.result = r; d.unknown = !['COMPLETED','ABORTED'].includes(r.state || '') && r.nextAction !== 'VIEW_TICKET' }
  } catch (e) { if (!scope.current()) return; if (e instanceof ApiError && e.code === 'OPERATION_NOT_FOUND') { missing.value = true; work.message.value = '本次查询暂未见原操作，不证明在途提交不会成功。不要换键。' } else work.fail(e, scope) } finally { scope.finish() }
}
async function removeAbortedFile(fileId: string) {
  const d = draft.value
  if (!d || result.value?.state !== 'ABORTED' || work.busy.value || !customerWriter(work.auth)) return
  const file = d.files.find(f => f.result?.fileId === fileId); if (!file) return
  const scope = work.begin(() => ownDraft(d.key, work.auth) === d && d.files.includes(file) && result.value?.state === 'ABORTED')
  try { const r = await tickets.remove(fileId, d.key, work.auth.csrf!.token); if (!scope.current()) return; file.state = r.state; scope.finish(); if (r.state === 'DELETED') d.files.splice(d.files.indexOf(file), 1) } catch (e) { work.fail(e, scope) } finally { scope.finish() }
}
async function queryAbortedFile(uploadId: string) {
  const d = draft.value; if (!d || result.value?.state !== 'ABORTED' || work.busy.value) return
  const f = d.files.find(f => f.key === uploadId); if (!f) return
  const scope = work.begin(() => ownDraft(d.key, work.auth) === d && d.files.includes(f))
  try { const reply = await tickets.uploadState(f.key); if (!scope.current()) return; f.result = reply; f.state = reply.state; scope.finish(); if (reply.state === 'DELETED') d.files.splice(d.files.indexOf(f), 1) } catch (e) { work.fail(e, scope) } finally { scope.finish() }
}
watch(key, () => { result.value = null; missing.value = false; input.value = key.value; if (uuid(key.value)) void run('query') }, { immediate: true })
</script>
<template><section class="page-heading"><h1>报修提交结果</h1><p>仅核对报修是否创建，不表示维修完成；查询不会推进操作。</p></section><section class="panel"><form class="actions" @submit.prevent="uuid(input) && router.push(`/submissions/${input}`)"><el-input v-model="input" aria-label="原提交UUID" placeholder="粘贴原提交UUID"/><el-button native-type="submit" :disabled="!uuid(input)">按原UUID查询</el-button></form><p v-if="key">原UUID：<code>{{ key }}</code></p><p v-if="work.message.value" role="alert">{{ work.message.value }} {{ work.trace.value }}</p><p v-if="work.busy.value">正在核验原操作…</p><template v-if="result"><p>当前结果：{{ result.state || (result.nextAction === 'VIEW_TICKET' ? 'COMPLETED' : '待确认') }}</p><p v-if="ticketNo">工单号：{{ ticketNo }}</p><p v-if="result.nextAction === 'VIEW_TICKET'">报修已提交，待受理。</p><p v-else-if="result.state === 'ABORTED'">原提交已明确终止：{{ result.reason }}。不能恢复该操作。旧附件须显式移除后，再从设备发起新的报修并重新上传。</p><p v-else>结果尚未完整确认；保留原UUID及附件，仅由你显式继续。</p><el-button v-if="result.nextAction === 'RESUME'" :disabled="work.busy.value || !work.auth.csrf" @click="run('resume')">显式继续原提交</el-button><RouterLink v-if="ticketId" :to="`/tickets/${ticketId}`">查看待受理报修</RouterLink></template><p v-if="missing && !draft?.sent">当前内存中没有原始参数，不能重建或自动重发。可继续查询原UUID；不要将未见记录当作提交失败。</p><el-button v-if="missing && draft?.sent" :disabled="work.busy.value || !work.auth.csrf" @click="run('resend')">显式原UUID、原参数重交</el-button><el-button v-if="uuid(key)" :disabled="work.busy.value" @click="run('query')">重新查询原结果</el-button><section v-if="result?.state === 'ABORTED' && draft"><ul><li v-for="f in draft.files" :key="f.key">{{ f.file.name }} · {{ f.state }}<el-button :disabled="work.busy.value" @click="queryAbortedFile(f.key)">查询旧上传状态</el-button><el-button v-if="f.result?.fileId" :disabled="work.busy.value" @click="removeAbortedFile(f.result!.fileId)">显式移除旧未引用附件</el-button></li></ul><RouterLink v-if="!draft.files.length" :to="{ path: '/repairs/new', query: { deviceId: draft.body.deviceId } }">明确开始新的报修（新UUID）</RouterLink></section><RouterLink v-if="draft && !draft.frozen" :to="{ path: '/repairs/new', query: { deviceId: draft.body.deviceId, submissionId: draft.key } }">返回原附件及表单</RouterLink><RouterLink to="/tickets">本人报修列表</RouterLink></section></template>



