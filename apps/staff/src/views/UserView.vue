<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { api, ApiError, isUnavailableStatus, type User, type Revocation, type EmployeeAccess } from '../api'
import { useSession } from '../session'
import { identityLabel, bindingLabel, shanghaiTime } from '../display'
import { captureRequestScope, sameRevocation } from '../request-scope'
import EmployeeStatus from './EmployeeStatus.vue'
import EmployeeScope from './EmployeeScope.vue'
const route = useRoute(), router = useRouter(), auth = useSession(), user = ref<User | null>(null), loading = ref(false), failed = ref(''), open = ref(false), reason = ref(''), error = ref(''), busy = ref(false), result = ref<Revocation | null>(null)
const { revocationPending: pending } = storeToRefs(auth)
const access = ref<EmployeeAccess | null>(null), accessError = ref('')
let serial = 0, submitSerial = 0, alive = true
async function load() {
  const n = ++serial, id = String(route.params.id)
  const scope = captureRequestScope(auth, () => alive && n === serial && String(route.params.id) === id)
  loading.value = true; user.value = null; access.value = null; accessError.value = ''; failed.value = ''
  try { const value = await api.user(id); if (!scope.current()) return; user.value = value
    if (auth.identity?.realm === 'TENANT' && value.identityType === 'EMPLOYEE') {
      try { const a = await api.employeeAccess(id); if (scope.current()) access.value = a }
      catch (e) { if (scope.current()) { accessError.value = '员工授权范围未能确认，请刷新重试。'; await auth.handleFailure(e) } }
    }
  }
  catch (e) { if (!scope.current()) return; failed.value = '详情加载失败或记录不可访问。'; await auth.handleFailure(e) }
  finally { if (scope.current()) loading.value = false }
}
function begin() { if (!user.value) return; if (pending.value) { if (pending.value.id !== user.value.id) { auth.notice = '已有另一目标的撤权结果待确认，请先返回原目标核对。'; void router.push('/users/'+pending.value.id); return }; error.value = '已有未知结果，请先核对原操作，不新建撤权。'; reason.value = pending.value.reason; open.value = true; return }; reason.value = ''; error.value = ''; result.value = null; open.value = true }
function closeDialog(done: () => void) { if (busy.value) return; if (pending.value && !confirm('结果未知。原操作仍保留在本页会话内，确认关闭？')) return; done() }
async function submit() {
  if (!open.value || busy.value || !auth.canManage || !auth.csrf || !user.value) return
  if (!pending.value) {
    if (Array.from(reason.value).length < 5 || Array.from(reason.value).length > 200 || /[\u0000-\u001f\u007f]/.test(reason.value)) { error.value = '原因须为5～200个字符，不含控制字符'; return }
    pending.value = { id: user.value.id, account: user.value.account, reason: reason.value, version: user.value.securityVersion, key: crypto.randomUUID(), actor: auth.identity!.userId, actorRealm: auth.identity!.realm, actorNamespace: auth.identity!.tenant?.id ?? 'PLATFORM' }
  }
  const operation = { ...pending.value }
  if (operation.actor !== auth.identity?.userId) { error.value = '操作者身份已变化，不能恢复原操作。'; return }
  if (operation.actorRealm && (operation.actorRealm !== auth.identity.realm || operation.actorNamespace !== (auth.identity.tenant?.id ?? 'PLATFORM'))) { error.value = '操作所属范围已变化，不能恢复原操作。'; return }
  auth.rememberRevocation(operation)
  const n = ++submitSerial, actorId = auth.identity.userId, actorAttempt = auth.identity.loginAttemptId
  let completedOwn = false
  const scope = captureRequestScope(auth, () => alive && n === submitSerial && String(route.params.id) === operation.id && sameRevocation(pending.value, operation))
  // Local finally belongs to this component's request slot even after its own
  // successful pending clear. It never unlocks a replacement request or instance.
  const localScope = captureRequestScope(auth, () => alive && n === submitSerial && String(route.params.id) === operation.id && (sameRevocation(pending.value, operation) || (completedOwn && pending.value === null)))
  function clearOwnPending() { if (scope.current()) completedOwn = auth.completeRevocation(operation) }
  function finishLocal() { if (localScope.current()) busy.value = false }
  busy.value = true; error.value = ''
  try {
    const reply = await api.revoke(operation.id, operation.reason, operation.version, operation.key, auth.csrf.token)
    if (!scope.current() || auth.identity?.loginAttemptId !== actorAttempt) return
    result.value = reply; clearOwnPending(); open.value = false
    finishLocal() // Finish this owned write before its own auth change/read refresh.
    if (operation.id === actorId) { auth.resetLogin(); auth.notice = '已撤销本人全部旧会话，请重新登录。'; auth.broadcast(); void router.replace(auth.loginEntry) }
    else await load()
  } catch (e) {
    if (!scope.current()) return
    const err = e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', '结果未知')
    if (isUnavailableStatus(err.status)) {
      error.value = '撤权结果尚未确认。已保留原键和原内容，请显式核对恢复，不能新建第二次操作。'
      // handleFailure is synchronous: publish the message before it invalidates
      // this generation and unmounts the view. No continuation may write after it.
      finishLocal(); auth.notice = error.value; auth.handleFailure(e, error.value)
    } else if (err.code === 'SECURITY_VERSION_CONFLICT') {
      clearOwnPending(); open.value = false
      finishLocal()
      auth.notice = '目标版本已变化，请查看最新详情后重新确认。'
      await load() // load has its own component/auth lease; no post-await writes.
    } else {
      if (err.status === 400 || err.status === 429) clearOwnPending()
      error.value = `${err.message}（${err.code}）`; finishLocal(); auth.handleFailure(e)
      if (localScope.current() && err.status === 403 && err.code !== 'CSRF_INVALID') await auth.synchronize()
    }
  } finally { finishLocal() }
}
onBeforeRouteLeave(() => !auth.ready || (!busy.value && (!pending.value || confirm('撤权结果未知。原操作只保留在当前页面会话内，确认离开？'))))
watch(() => route.params.id, () => { serial++; submitSerial++; busy.value = false; open.value = false; result.value = null; void load() }); onMounted(() => { void load(); if (pending.value?.id === String(route.params.id) && pending.value.actor === auth.identity?.userId) { reason.value = pending.value.reason; error.value = '原撤权结果尚未确认，恢复时使用相同键和内容。'; open.value = true } }); onUnmounted(() => { alive = false; serial++; submitSerial++ })
</script>
<template><section class="page-heading"><RouterLink to="/users">← 返回用户列表</RouterLink><h1>账号详情与会话撤权</h1><p>撤权使全部旧会话失效，不停用账号，不阻止以后重新登录。</p></section><section class="panel" v-loading="loading"><p v-if="failed" class="field-error" role="alert">{{ failed }} <el-button @click="load">重试</el-button></p><template v-if="user"><div class="section-heading"><h2>{{ user.displayName }} <small>{{ user.account }}</small></h2><el-button type="danger" @click="begin">撤销全部会话</el-button></div><dl class="details"><dt>账号ID</dt><dd>{{ user.id }}</dd><dt>身份</dt><dd>{{ identityLabel(user.identityType) }}</dd><dt>安全版本</dt><dd>{{ user.securityVersion }}</dd><dt>需要改密</dt><dd>{{ user.mustChangePassword ? '是' : '否' }}</dd><dt>绑定状态</dt><dd>{{ bindingLabel(user.bindingState) }}</dd><dt>最近更新</dt><dd>{{ shanghaiTime(user.updatedAt) }}</dd></dl></template><p v-if="result" role="status">撤权成功 · 操作 {{ result.operationId }} · 安全版本 {{ result.newSecurityVersion }}</p></section>
<section v-if="access || accessError" class="panel"><h2>当前员工授权</h2><p v-if="accessError" role="alert">{{ accessError }}</p><template v-if="access"><p>所属部门编号：{{ access.departmentId || '未设置' }}</p><p>客户企业编号：{{ access.companyIds.join('、') || '无' }}</p><p>可分派部门编号：{{ access.assignmentDepartmentIds.join('、') || '无' }}</p><p>审批部门编号：{{ access.approvalDepartmentIds.join('、') || '无' }}</p><p>重开审核部门编号：{{ access.reopenDepartmentIds.join('、') || '无' }}</p><p>仓库编号：{{ access.warehouseIds.join('、') || '无' }}</p><p>当前能力：{{ access.capabilities.join('、') || '无' }}</p></template></section><EmployeeStatus v-if="user && access" :user="user" @changed="load" /><EmployeeScope v-if="access" :access="access" @changed="load" />
<el-dialog v-model="open" title="撤销目标全部会话" width="560px" :close-on-click-modal="false" :before-close="closeDialog"><p>目标：{{ user?.account }} · 版本 {{ pending?.version ?? user?.securityVersion }}</p><p>全部旧会话失效，账号仍可重新登录。若目标为本人，当前页面也将退出。</p><label for="reason">撤权原因（5～200个字符）</label><el-input id="reason" v-model="reason" type="textarea" :rows="3" :disabled="!!pending" /><p v-if="error" class="field-error" role="alert">{{ error }}</p><template #footer><el-button :disabled="busy" @click="closeDialog(()=>open=false)">返回</el-button><el-button type="danger" :loading="busy" @click="submit">{{ pending ? '使用原键核对恢复' : '确认撤权' }}</el-button></template></el-dialog></template>
