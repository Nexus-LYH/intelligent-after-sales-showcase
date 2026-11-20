<script setup lang="ts">
import { ref, computed, watch, onUnmounted } from 'vue'
import { useRoute, onBeforeRouteLeave } from 'vue-router'
import { assets, assetLabels, activeStatus, type AssetKind, type AssetRecord, type Member } from '../asset-api'
import { assetFields, assetBody, formOf, validateAsset } from '../asset-forms'
import { ApiError, isUnavailableStatus } from '../api'
import { useAssetWork } from '../asset-work'
import AssetForm from '../components/AssetForm.vue'
import AssetRecovery from '../components/AssetRecovery.vue'
const props = defineProps<{ kind: AssetKind }>(), route = useRoute(), work = useAssetWork(() => route.fullPath)
const record = ref<AssetRecord | null>(null), members = ref<Member[]>([]), memberPage = ref(1), memberTotal = ref(0)
const open = ref(false), enrollment = ref(false), validation = ref(''), form = ref(formOf(props.kind))
const account = ref(''), displayName = ref(''), password = ref('')
const id = computed(() => String(route.params.id)), targetPending = computed(() => work.owned.value.some(p => p.kind === props.kind && p.target === id.value && !p.enrollment))
const wipe = () => { password.value = '' }
async function load(notice = '') {
  const scope = work.begin(); record.value = null; members.value = []; memberTotal.value = 0
  try {
    const data = await assets.detail(props.kind, id.value); if (!scope.current()) return
    if (props.kind === 'companies') { const list = await assets.members(id.value, memberPage.value); if (!scope.current()) return; members.value = list.items; memberTotal.value = list.total }
    record.value = data; work.message.value = notice
  } catch (e) { work.fail(e, scope) } finally { work.finish(scope) }
}
function edit() {
  if (!record.value || !work.canWrite.value || work.loading.value || targetPending.value) return
  form.value = formOf(props.kind, record.value); validation.value = ''; open.value = true
}
async function write(status?: string) {
  if (!record.value || !work.canWrite.value || work.loading.value || targetPending.value || !work.auth.csrf) return
  if (status === undefined) { validation.value = validateAsset(props.kind, form.value, true); if (validation.value || !open.value) return }
  else if (!confirm(`确认${status === 'ENABLED' ? '启用' : '停用'}${assetLabels[props.kind]}？${props.kind === 'companies' ? '停用不删除成员、设备或历史。' : ''}`)) return
  const reason = status === undefined ? '' : prompt('请输入启停原因（5～200个字符）')
  if (status !== undefined && (reason === null || Array.from(reason).length < 5 || Array.from(reason).length > 200)) { validation.value = '启停原因须为5～200个字符'; work.message.value = validation.value; return }
  const original = record.value, body = status === undefined ? assetBody(props.kind, form.value, original) : { status, reason, expectedVersion: original.rowVersion }
  const pending = work.remember(props.kind, id.value, false, body, status !== undefined), scope = work.begin(pending)
  try {
    const reply = status === undefined ? await assets.write(props.kind, id.value, body, pending.key, work.auth.csrf.token) : await assets.status(props.kind, id.value, body, pending.key, work.auth.csrf.token)
    if (!scope.current() || !work.owned.value.some(p => p.key === pending.key)) return
    work.finish(scope); work.complete(pending); record.value = reply; open.value = false; work.message.value = '已保存。'
  } catch (e) {
    if (!scope.current()) return
    open.value = false
    if (e instanceof ApiError && e.status === 409) { work.finish(scope); work.complete(pending); await load('版本或操作内容冲突，已刷新最新资料。请重新核对并明确提交。'); return }
    record.value = null; work.fail(e, scope)
    if (e instanceof ApiError && !isUnavailableStatus(e.status)) work.complete(pending)
  } finally { work.finish(scope) }
}
async function enroll() {
  if (!enrollment.value || !record.value || record.value.status !== 'ENABLED' || !work.canEnroll.value || work.loading.value || targetPending.value || !work.auth.csrf) return
  if (!/^[A-Za-z0-9._-]{1,100}$/.test(account.value)) { validation.value = '账号为1～100位字母、数字、点、下划线或短横线'; return }
  if (!displayName.value.trim() || Array.from(displayName.value).length > 50) { validation.value = '请输入1～50字符姓名'; return }
  validation.value = Array.from(password.value).length < 12 || Array.from(password.value).length > 128 ? '初始密码须为12～128个字符' : ''; if (validation.value) return
  const body = { account: account.value.toLowerCase(), displayName: displayName.value, initialPassword: password.value }
  wipe(); const pending = work.remember('companies', id.value, true, { account: body.account, displayName: body.displayName }), scope = work.begin(pending)
  try {
    const reply = await assets.enroll(id.value, body, pending.key, work.auth.csrf.token)
    if (!scope.current() || !work.owned.value.some(p => p.key === pending.key)) return
    work.attach(pending, reply.operationId); enrollment.value = false
    if (['BOUND','COMPLETED','SUCCEEDED'].includes(reply.state)) { work.finish(scope); work.complete(pending); await load() }
    else { work.finish(scope); work.message.value = '开通尚待确认，请查询原操作；不重新提交密码。' }
  } catch (e) {
    if (!scope.current()) return
    enrollment.value = false; work.fail(e, scope)
    if (e instanceof ApiError && !isUnavailableStatus(e.status)) work.complete(pending)
  } finally { body.initialPassword = ''; work.finish(scope) }
}
watch(() => [props.kind, id.value], () => { open.value = false; enrollment.value = false; memberPage.value = 1; wipe(); void load() }, { immediate: true })
watch(enrollment, value => { if (!value) wipe() })
onBeforeRouteLeave(() => !work.auth.ready || (!work.loading.value && (!password.value || confirm('离开会清除未提交的初始密码，确认离开？'))))
onUnmounted(wipe)
</script>
<template><section class="page-heading"><RouterLink :to="`/assets/${kind}`">← 返回{{ assetLabels[kind] }}列表</RouterLink><p class="eyebrow">ASSET DETAIL</p><h1>{{ record?.name || record?.code || assetLabels[kind] }}</h1></section><AssetRecovery/><p v-if="work.message.value" class="notice" role="alert">{{ work.message.value }} <small>{{ work.trace.value }}</small></p><section v-if="work.loading.value" class="panel" role="status">正在核对档案…</section><section v-else-if="record" class="panel"><div class="section-heading"><h2>基本资料</h2><el-tag :type="record.status === 'ENABLED' ? 'success' : 'info'">{{ activeStatus(record.status) }}</el-tag></div><p v-if="record.status !== 'ENABLED'" class="notice">此档案已停用。{{ record.disabledReason || '保留只读资料与历史，不提供新增报修。' }}</p><dl class="details"><template v-for="field in assetFields[kind]" :key="field.key"><dt>{{ field.label }}</dt><dd>{{ record[field.key as keyof AssetRecord] || '未提供' }}</dd></template><template v-if="kind === 'devices'"><dt>客户企业</dt><dd>{{ record.companyName || record.companyId }}</dd><dt>产品型号</dt><dd>{{ record.modelName || record.modelId }}</dd><dt>归属版本</dt><dd>{{ record.ownershipVersion }}</dd></template><dt>资料版本</dt><dd>{{ record.rowVersion }}</dd><dt>更新时间（UTC）</dt><dd>{{ record.updatedAt }}</dd></dl><div class="actions"><el-button @click="load()">刷新</el-button><el-button v-if="work.canWrite.value" :disabled="targetPending" @click="edit">编辑基本资料</el-button><el-button v-if="work.canWrite.value && kind !== 'models'" :disabled="targetPending" @click="write(record.status === 'ENABLED' ? 'DISABLED' : 'ENABLED')">{{ record.status === 'ENABLED' ? '停用' : '启用' }}</el-button><el-button v-if="kind === 'companies' && work.canEnroll.value && record.status === 'ENABLED'" type="primary" :disabled="targetPending || work.owned.value.some(p => p.enrollment && p.target === id)" @click="enrollment = true; validation = ''; account = ''; displayName = ''">开通普通客户</el-button></div></section><section v-if="record && kind === 'companies'" class="panel member-panel"><h2>企业成员</h2><el-table :data="members" empty-text="暂无已开通成员"><el-table-column prop="userId" label="用户ID"/><el-table-column label="状态"><template #default="{ row }">{{ row.state === 'ACTIVE' ? '已生效' : '开通待确认' }}</template></el-table-column></el-table><el-pagination class="pagination" :total="memberTotal" :page-size="20" :current-page="memberPage" layout="prev, pager, next, total" @current-change="(page: number) => { memberPage = page; load() }"/><p class="muted">本批仅开通普通客户，不提供企业管理员授权或成员停用操作。</p><RouterLink :to="{ path: '/assets/devices', query: { companyId: record.id } }">查看企业设备</RouterLink></section><section v-if="!record && !work.loading.value" class="panel"><p>档案不可用或无权访问，未显示旧正文。</p><el-button @click="load()">重新查询</el-button></section><el-dialog v-model="open" title="编辑基本资料" width="min(680px,94vw)" :close-on-click-modal="false" :show-close="!work.loading.value"><form @submit.prevent="write()"><AssetForm :kind="kind" :form="form" editing :companies="record ? [record] : []" :disabled="work.loading.value"/><p v-if="validation" class="field-error">{{ validation }}</p><div class="actions"><el-button type="primary" native-type="submit" :loading="work.loading.value">保存修改</el-button><el-button :disabled="work.loading.value" @click="open = false">取消</el-button></div></form></el-dialog><el-dialog v-model="enrollment" title="开通普通客户" width="min(580px,94vw)" :close-on-click-modal="false" :show-close="!work.loading.value"><p>一次性初始密码由您通过受控渠道交付。客户首次登录必须改密；结果查询不会返回密码。</p><form @submit.prevent="enroll"><label for="enroll-account">客户账号</label><el-input id="enroll-account" v-model="account" autocomplete="off" :disabled="work.loading.value"/><label for="enroll-name">客户姓名</label><el-input id="enroll-name" v-model="displayName" :disabled="work.loading.value"/><label for="enroll-password">一次性初始密码</label><el-input id="enroll-password" v-model="password" type="password" show-password autocomplete="new-password" :disabled="work.loading.value"/><p v-if="validation" class="field-error">{{ validation }}</p><div class="actions"><el-button type="primary" native-type="submit" :loading="work.loading.value">确认开通</el-button><el-button :disabled="work.loading.value" @click="enrollment = false">取消</el-button></div></form></el-dialog></template>
