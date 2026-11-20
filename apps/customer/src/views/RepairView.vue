<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { assets } from '../../../staff/src/asset-api'
import { ApiError } from '../../../staff/src/api'
import { actorKey, customerWriter, matchesUploadOrigin, ownDraft, saveDraft, unresolvedDraft, useTicketWork, type RepairDraft, type UploadWork } from '../../../staff/src/ticket-work'
import { repairValidation, tickets, uploadFile, verifyUpload, verifyCreation, type TicketSummary } from '../../../staff/src/ticket-api'
const route = useRoute(), router = useRouter(), work = useTicketWork(() => route.fullPath)
const draft = ref<RepairDraft>(), deviceName = ref(''), allowed = ref(false), duplicateState = ref('尚未检查'), duplicateCount = ref(0)
const duplicateItems = ref<TicketSummary[]>([])
const locked = computed(() => !!draft.value?.frozen || work.busy.value)
function unsaved() { const d = draft.value; return !!d && !d.frozen && (!!d.body.title || !!d.body.description || !!d.body.contactName || !!d.body.contactPhone || !!d.files.length) }
const confirmLeave = () => !unsaved() || confirm('表单及原附件仅保留在当前页面内存。确认离开？可使用原UUID返回当前内存草稿。')
onBeforeRouteLeave(confirmLeave)
onBeforeRouteUpdate((to, from) => to.query.submissionId === from.query.submissionId || confirmLeave())
function beforeUnload(event: BeforeUnloadEvent) { if (unsaved() || draft.value?.unknown) { event.preventDefault(); event.returnValue = '' } }
window.addEventListener('beforeunload', beforeUnload)
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload))
async function load() {
  const scope = work.begin(); allowed.value = false; deviceName.value = ''; draft.value = undefined; duplicateItems.value = []; duplicateState.value = '正在检查本人同设备报修'; duplicateCount.value = 0
  const id = String(route.query.deviceId || '')
  try {
    const d = await assets.detail('devices', id); if (!scope.current()) return
    const companies = await assets.myCompanies(); if (!scope.current()) return
    const company = companies.items.find(c => c.id === d.companyId)
    if (d.status !== 'ENABLED' || company?.status !== 'ENABLED') { work.message.value = '企业或设备已停用，不能新报修'; return }
    allowed.value = true; deviceName.value = d.name
    const existing = typeof route.query.submissionId === 'string' ? route.query.submissionId : ''
    const unresolved = !existing && unresolvedDraft(id, work.auth)
    if (unresolved) { scope.finish(); await router.replace(`/submissions/${unresolved.key}`); return }
    if (existing) {
      const retained = ownDraft(existing, work.auth)
      if (!retained) { allowed.value = false; work.message.value = `原提交 ${existing} 的正文不在当前身份内存中，请到报修提交结果按原UUID查询，不能重建或换键`; return }
      draft.value = retained
      for (const f of retained.files) if (f.state === 'HASHING') f.state = 'SELECTED'
    }
    const key = existing || crypto.randomUUID()
    const value: RepairDraft = reactive({ key, actor: actorKey(work.auth), body: { deviceId: id, expectedOwnershipVersion: String(d.ownershipVersion), title: '', description: '', categoryCode: 'UNCERTAIN', impact: 'AFFECTED', contactName: '', contactPhone: '', attachmentIds: [], duplicateAcknowledged: false }, frozen: false, files: [], unknown: false })
    if (!existing) { saveDraft(value); draft.value = value; scope.finish(); await router.replace({ path: '/repairs/new', query: { deviceId: id, submissionId: key } }); return }
    try { const result = await tickets.duplicates(id); if (!scope.current()) return; duplicateItems.value = result.items; duplicateCount.value = result.items.length; duplicateState.value = result.items.length ? `发现本人同设备待受理报修 ${result.items.length} 条，请先查看本人列表` : '当前未发现本人同设备待受理报修（提交时仍会复核）' } catch (e) { if (!scope.current()) return; duplicateState.value = '重复检查不可用，不代表没有重复报修'; if (e instanceof ApiError && [401, 403].includes(e.status)) work.fail(e, scope) }
  } catch (e) { work.fail(e, scope) } finally { scope.finish() }
}
function begin(d: RepairDraft, file?: UploadWork) { return work.begin(() => draft.value === d && ownDraft(d.key, work.auth) === d && (!file || d.files.includes(file) && (!file.original || matchesUploadOrigin(d, file)))) }
function canReplay(f: UploadWork) { return !!draft.value && ownDraft(draft.value.key, work.auth) === draft.value && f.state === 'NOT_OBSERVED' && matchesUploadOrigin(draft.value, f) && customerWriter(work.auth) }
async function choose(event: Event) {
  const input = event.target as HTMLInputElement, selected = Array.from(input.files || []); input.value = ''
  const d = draft.value; if (!d || locked.value || !customerWriter(work.auth) || ownDraft(d.key, work.auth) !== d) return
  if (d.files.length + selected.length > 5) { work.message.value = '最多5个附件，请先移除多余项'; return }
  for (const file of selected) {
    if (!file.size || file.size > 20971520 || !['image/jpeg', 'image/png', 'application/pdf'].includes(file.type)) { work.message.value = '只接受非空JPEG、PNG、PDF，每个最多20MiB，服务端仍核验真实格式'; continue }
    d.files.push({ key: crypto.randomUUID(), file, sha256: '', state: 'SELECTED' })
  }
}
async function upload(f: UploadWork, query = false) {
  const d = draft.value; if (!d || d.frozen || work.busy.value || !customerWriter(work.auth) || ownDraft(d.key, work.auth) !== d) return
  if (f.original && !matchesUploadOrigin(d, f)) { work.message.value = '原上传参数不匹配，不能重传或将新参数混入原操作'; return }
  const scope = begin(d, f)
  try {
    if (query) { const result = await tickets.uploadState(f.key); if (!scope.current()) return; verifyUpload(result, f.key, d.key, f.file, f.sha256); f.result = result; f.state = result.state; return }
    if (!['SELECTED', 'FAILED'].includes(f.state) && !canReplay(f)) return
    f.state = 'HASHING'
    if (!f.sha256) { const bytes = await f.file.arrayBuffer(); if (!scope.current()) return; const digest = await crypto.subtle.digest('SHA-256', bytes); if (!scope.current()) return; f.sha256 = Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('') }
    if (!f.original) f.original = Object.freeze({ actor: d.actor, uploadId: f.key, submissionId: d.key, file: f.file, sha256: f.sha256, deviceId: d.body.deviceId, expectedOwnershipVersion: d.body.expectedOwnershipVersion })
    if (!matchesUploadOrigin(d, f)) return
    f.state = 'UPLOADING'
    const o = f.original
    const result = await uploadFile(o.uploadId, o.submissionId, o, o.file, o.sha256, work.auth.csrf!.token); if (!scope.current()) return
    verifyUpload(result, f.key, d.key, f.file, f.sha256); f.result = result; f.state = result.state
  } catch (e) {
    if (!scope.current()) return
    if (query && e instanceof ApiError && e.status === 404 && e.code === 'TARGET_NOT_ACCESSIBLE' && matchesUploadOrigin(d, f)) {
      f.state = 'NOT_OBSERVED'
      work.message.value = '本次未观察到原上传，不代表原请求绝对未执行。可显式原键同文件重传，也可继续查询；不能丢弃未知附件。'
    } else { f.state = 'UNKNOWN'; work.fail(e, scope) }
  } finally { scope.finish() }
}
async function remove(f: UploadWork) {
  const d = draft.value; if (!d || d.frozen || work.busy.value || !customerWriter(work.auth) || ownDraft(d.key, work.auth) !== d) return
  if (f.state === 'SELECTED') { d.files.splice(d.files.indexOf(f), 1); return }
  if (!f.result?.fileId || ['UNKNOWN', 'NOT_OBSERVED', 'UPLOADING', 'HASHING'].includes(f.state)) { work.message.value = '须先查询原上传状态，不能将未知上传当作已移除'; return }
  const scope = begin(d, f)
  try { const r = await tickets.remove(f.result.fileId, d.key, work.auth.csrf!.token); if (!scope.current()) return; f.state = r.state; scope.finish(); if (r.state === 'DELETED') d.files.splice(d.files.indexOf(f), 1) } catch (e) { work.fail(e, scope) } finally { scope.finish() }
}
async function submit() {
  const d = draft.value; if (!d || locked.value || !allowed.value || !customerWriter(work.auth) || ownDraft(d.key, work.auth) !== d) return
  if (d.files.some(f => f.state !== 'READY')) { work.message.value = '所有附件须真实READY；失败或未知项请显式查询、重试或移除'; return }
  d.body.attachmentIds = d.files.map(f => f.result!.fileId).sort()
  const error = repairValidation(d.body); if (error) { work.message.value = error; return }
  d.sent = structuredClone(JSON.parse(JSON.stringify(d.body))); d.frozen = true; d.unknown = true
  const scope = begin(d)
  try { const r = await tickets.create(d.key, d.sent!, work.auth.csrf!.token); if (!scope.current()) return; verifyCreation(r, d.key); d.result = r; d.unknown = r.nextAction !== 'VIEW_TICKET'; scope.finish(); await router.push(`/submissions/${d.key}`) }
  catch (e) { if (!scope.current()) return; work.fail(e, scope); await router.push(`/submissions/${d.key}`) }
  finally { scope.finish() }
}
watch(() => route.fullPath, load, { immediate: true })
</script>
<template><section class="page-heading"><RouterLink to="/devices">← 授权设备</RouterLink><h1>提交报修</h1><p>提交后进入待受理，不代表维修完成。</p></section><section class="panel"><p v-if="work.message.value" role="alert">{{ work.message.value }} {{ work.trace.value }}</p><form v-if="draft && allowed" @submit.prevent="submit"><h2>{{ deviceName }}</h2><p>本次提交UUID：<code>{{ draft.key }}</code>（可复制保存，仅此标识可落盘）</p><fieldset :disabled="locked" class="repair-fields"><label>标题（5～100字符）<el-input v-model="draft.body.title" aria-label="报修标题"/></label><label>故障描述（20～5000字符）<el-input v-model="draft.body.description" type="textarea" :rows="6" aria-label="故障描述"/></label><label>影响程度<el-select v-model="draft.body.impact" aria-label="影响程度"><el-option label="无法使用" value="UNUSABLE"/><el-option label="使用受影响" value="AFFECTED"/><el-option label="一般咨询" value="CONSULTATION"/></el-select></label><p>分类暂为“不确定”，影响程度不等于最终优先级。</p><label>联系人<el-input v-model="draft.body.contactName" aria-label="联系人"/></label><label>联系电话<el-input v-model="draft.body.contactPhone" aria-label="联系电话"/></label><p role="status">{{ duplicateState }}</p><ul><li v-for="t in duplicateItems" :key="t.id"><RouterLink :to="`/tickets/${t.id}`">{{ t.ticketNo }} · {{ t.title }} · 待受理</RouterLink></li></ul><RouterLink to="/tickets">查看本人报修</RouterLink><label><input v-model="draft.body.duplicateAcknowledged" type="checkbox"/>{{ duplicateCount ? '已查看已有报修，仍确认另行提交' : '若提交时发现本人同设备待受理报修，仍确认另行提交' }}</label><h2>附件</h2><p>最多5个JPEG/PNG/PDF，每个不超过20MiB。选中不等于上传完成。</p><input type="file" multiple accept="image/jpeg,image/png,application/pdf" aria-label="选择附件" @change="choose"/><ul><li v-for="f in draft.files" :key="f.key">{{ f.file.name }} · {{ f.state }} <el-button :disabled="locked || !['SELECTED','FAILED'].includes(f.state)" @click="upload(f)">显式上传/原键重试</el-button><el-button v-if="f.state === 'NOT_OBSERVED'" :disabled="locked || !canReplay(f)" @click="upload(f)">显式原键同文件重传</el-button><span v-if="f.state === 'NOT_OBSERVED'" role="status">本次未观察到原上传，不代表绝对未执行。</span><el-button :disabled="locked" @click="upload(f, true)">查询原上传</el-button><el-button :disabled="locked" @click="remove(f)">显式移除</el-button></li></ul></fieldset><el-button type="primary" native-type="submit" :disabled="locked">提交报修</el-button><RouterLink v-if="draft.frozen" :to="`/submissions/${draft.key}`">查看报修提交结果</RouterLink></form><p v-else>正在核验设备，或当前设备不允许新报修。未创建工单。</p></section></template>


