<script setup lang="ts">
import { ref, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { assets, type AssetOperation, type AssetKind } from '../asset-api'
import { useAssetWork, type PendingAsset } from '../asset-work'
const route = useRoute(), work = useAssetWork(() => route.fullPath), result = ref<AssetOperation | null>(null), selected = ref(''), initialPassword = ref('')
const reference = ref(''), referenceKind = ref('enrollment')
function restore() {
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(reference.value)) { work.message.value = '请输入完整的小写UUID操作键'; return }
  const pending = work.remember('recovered', '', referenceKind.value === 'enrollment', undefined, false, reference.value)
  void inspect(pending)
}
async function inspect(pending: PendingAsset, action: 'query' | 'resume' | 'password' | 'replay' = 'query') {
  if (work.loading.value || work.isActive(pending) || !work.owns(pending)) return
  const current = selected.value === pending.key ? result.value : null
  if (action !== 'query' && (!work.canWrite.value || !work.auth.csrf)) return
  if (action === 'resume' && (!work.canEnroll.value || current?.nextAction !== 'RESUME')) return
  if (action === 'password' && (!work.canEnroll.value || current?.nextAction !== 'ENTER_INITIAL_PASSWORD')) return
  if (action === 'password' && (Array.from(initialPassword.value).length < 12 || Array.from(initialPassword.value).length > 128)) { work.message.value = '初始密码须为12～128个字符'; return }
  if (action !== 'query' && !confirm('确认以原操作键继续？不会创建新的操作。')) return
  let password = initialPassword.value; initialPassword.value = ''
  const scope = work.begin(pending); selected.value = pending.key; result.value = null
  let secretBody: { account: string; displayName: string; initialPassword: string } | undefined
  try {
    let reply: AssetOperation
    if (action === 'resume') reply = await assets.resumeEnrollment(pending.key, work.auth.csrf!.token)
    else if (action === 'password') {
      secretBody = { account: String(current?.account ?? pending.body?.account ?? ''), displayName: String(current?.displayName ?? pending.body?.displayName ?? ''), initialPassword: password }
      reply = await assets.enroll(pending.target || current?.companyId || '', secretBody, pending.key, work.auth.csrf!.token)
    } else if (action === 'replay' && pending.body && !pending.enrollment) {
      const data = pending.statusAction ? await assets.status(pending.kind as AssetKind, pending.target, pending.body, pending.key, work.auth.csrf!.token) : await assets.write(pending.kind as AssetKind, pending.target || null, pending.body, pending.key, work.auth.csrf!.token)
      reply = { operationId: pending.key, state: 'COMPLETED', result: data }
    } else reply = await assets.operation(pending.key, pending.enrollment)
    if (!scope.current() || !work.owned.value.some(p => p.key === pending.key)) return
    result.value = reply; work.attach(pending, reply.operationId)
    if (['COMPLETED', 'COMMITTED', 'SUCCEEDED'].includes(reply.state)) { work.finish(scope); work.complete(pending); work.message.value = '原操作已确认完成，请刷新列表或详情。' }
    else { work.finish(scope); work.message.value = '操作尚未完成，请按当前状态显式处理；不会自动重发。' }
  } catch (e) { work.fail(e, scope) } finally { password = ''; if (secretBody) secretBody.initialPassword = ''; work.finish(scope) }
}
onUnmounted(() => { initialPassword.value = '' })
</script>
<template><details class="operation-lookup"><summary>按已有操作键查询</summary><form class="filters" @submit.prevent="restore"><div><label for="operation-reference">原操作键</label><el-input id="operation-reference" v-model="reference" placeholder="小写UUID"/></div><div><label for="operation-kind">操作类型</label><el-select id="operation-kind" v-model="referenceKind"><el-option label="客户开通" value="enrollment"/><el-option label="资产维护" value="asset"/></el-select></div><el-button native-type="submit" :disabled="work.loading.value">查询已有操作</el-button></form></details><section v-if="work.owned.value.length || work.message.value" class="notice asset-recovery"><h2>待核对的提交</h2><p>关闭或刷新浏览器会丢失本地内存引用，请先保留操作键。查询不会自动继续写入。</p><article v-for="pending in work.owned.value" :key="pending.key"><p>{{ pending.enrollment ? '客户开通' : '资产维护' }} · {{ pending.target || '新建' }}<br><small>操作键：{{ pending.key }}</small></p><div class="actions"><el-button :disabled="work.loading.value || work.isActive(pending)" @click="inspect(pending)">查询原操作</el-button><el-button v-if="!pending.enrollment && pending.body" :disabled="work.loading.value || work.isActive(pending) || !work.canWrite.value" @click="inspect(pending, 'replay')">显式以原键核对恢复</el-button><el-button v-if="pending.enrollment && selected === pending.key && result?.nextAction === 'RESUME'" :disabled="work.loading.value || work.isActive(pending) || !work.canEnroll.value" @click="inspect(pending, 'resume')">显式继续原开通</el-button></div><form v-if="pending.enrollment && selected === pending.key && result?.nextAction === 'ENTER_INITIAL_PASSWORD'" @submit.prevent="inspect(pending, 'password')"><p>服务端确认需要重新输入初始密码。只继续本次原键，不创建新账号。</p><label for="recovery-initial-password">重新输入一次性初始密码</label><el-input id="recovery-initial-password" v-model="initialPassword" type="password" show-password autocomplete="new-password"/><el-button native-type="submit" :disabled="work.loading.value || work.isActive(pending) || !work.canEnroll.value">确认同键继续开通</el-button></form></article><p v-if="result">当前状态：{{ result.state }}</p><p role="status">{{ work.message.value }} <small v-if="work.trace.value">追踪标识：{{ work.trace.value }}</small></p></section></template>
