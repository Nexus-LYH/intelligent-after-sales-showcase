<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { assets, assetLabels, activeStatus, type AssetKind, type AssetRecord } from '../asset-api'
import { formOf, validateAsset, assetBody } from '../asset-forms'
import { useAssetWork } from '../asset-work'
import { ApiError, isUnavailableStatus } from '../api'
import AssetForm from '../components/AssetForm.vue'
import AssetRecovery from '../components/AssetRecovery.vue'
const props = defineProps<{ kind: AssetKind }>(), route = useRoute(), router = useRouter(), work = useAssetWork(() => route.fullPath)
const rows = ref<AssetRecord[]>([]), total = ref(0), open = ref(false), validation = ref(''), form = ref(formOf(props.kind))
const companies = ref<AssetRecord[]>([]), models = ref<AssetRecord[]>([])
const filter = reactive({ keyword: '', status: '', companyId: '', modelId: '', page: 1, size: 20 })
function query() { const q = new URLSearchParams({ page: String(filter.page), size: String(filter.size) }); for (const k of ['keyword','status','companyId','modelId'] as const) if (filter[k]) q.set(k, filter[k]); return q }
async function load() {
  const scope = work.begin(); rows.value = []; total.value = 0
  try { const data = await assets.list(props.kind, query()); if (!scope.current()) return; rows.value = data.items; total.value = data.total } catch (e) { work.fail(e, scope) } finally { work.finish(scope) }
}
async function choices() {
  const scope = work.begin()
  try {
    const q = new URLSearchParams({ page: '1', size: '100', status: 'ENABLED' })
    // Fetch every page: never present only the first 100 valid associations as the complete choice set.
    async function all(kind: AssetKind) { const items: AssetRecord[] = []; for (let page = 1; ; page++) { q.set('page', String(page)); const data = await assets.list(kind, q); if (!scope.current()) return []; items.push(...data.items); if (items.length >= data.total || !data.items.length) return items } }
    companies.value = await all('companies'); if (!scope.current()) return
    models.value = await all('models'); if (!scope.current()) return
    form.value = formOf(props.kind); validation.value = ''; open.value = true
  } catch (e) { work.fail(e, scope) } finally { work.finish(scope) }
}
async function create() {
  if (!work.canWrite.value || work.loading.value || work.owned.value.length) return
  if (props.kind === 'devices') return choices()
  form.value = formOf(props.kind); validation.value = ''; open.value = true
}
async function submit() {
  if (!open.value || !work.canWrite.value || work.loading.value || work.owned.value.length || !work.auth.csrf) return
  validation.value = validateAsset(props.kind, form.value); if (validation.value) return
  const kind = props.kind, body = assetBody(kind, form.value), pending = work.remember(kind, '', false, body), scope = work.begin(pending)
  try {
    const reply = await assets.write(kind, null, body, pending.key, work.auth.csrf.token)
    if (!scope.current() || !work.owned.value.some(p => p.key === pending.key)) return
    work.finish(scope); work.complete(pending); open.value = false; work.finish(scope)
    await router.push(`/assets/${kind}/${reply.id}`)
  } catch (e) {
    if (!scope.current()) return
    open.value = false; work.fail(e, scope)
    if (e instanceof ApiError && !isUnavailableStatus(e.status)) work.complete(pending)
  } finally { work.finish(scope) }
}
function search() { filter.page = 1; void router.replace({ query: Object.fromEntries(query()) }) }
watch(() => [props.kind, route.query], () => { filter.keyword = String(route.query.keyword ?? ''); filter.status = String(route.query.status ?? ''); filter.companyId = String(route.query.companyId ?? ''); filter.modelId = String(route.query.modelId ?? ''); filter.page = Math.max(1, Number(route.query.page) || 1); open.value = false; void load() }, { immediate: true })
onBeforeRouteLeave(() => !work.auth.ready || !work.loading.value)
</script>
<template><section class="page-heading"><p class="eyebrow">ASSET REGISTER</p><h1>{{ assetLabels[kind] }}</h1><p>仅显示当前授权租户的真实档案，停用不删除历史。</p></section><AssetRecovery /><section class="panel"><div class="section-heading"><h2>档案列表 <small>{{ total }} 条</small></h2><el-button v-if="work.canWrite.value" type="primary" :disabled="work.loading.value || !!work.owned.value.length" @click="create">新建{{ assetLabels[kind] }}</el-button></div><form class="filters" @submit.prevent="search"><div><label for="asset-keyword">关键词</label><el-input id="asset-keyword" v-model="filter.keyword" :placeholder="kind === 'devices' ? '编号或序列号' : '编码或名称'" /></div><div><label for="asset-status">状态</label><el-select id="asset-status" v-model="filter.status"><el-option label="全部" value=""/><el-option label="启用" value="ENABLED"/><el-option label="停用" value="DISABLED"/></el-select></div><el-button native-type="submit" :disabled="work.loading.value">查询</el-button><el-button :disabled="work.loading.value" @click="load">刷新</el-button></form><p v-if="work.message.value" role="alert">{{ work.message.value }} <small>{{ work.trace.value }}</small></p><p v-if="work.loading.value" role="status">正在加载…</p><div v-else class="table-wrap"><el-table :data="rows" empty-text="暂无授权范围内的档案，请调整筛选或联系服务商建档。"><el-table-column prop="code" label="编码/编号" min-width="150"/><el-table-column v-if="kind !== 'devices'" prop="name" label="名称" min-width="170"/><el-table-column v-if="kind === 'devices'" prop="serialNumber" label="序列号" min-width="170"/><el-table-column v-if="kind === 'devices'" label="型号" min-width="140"><template #default="{ row }">{{ row.modelName || `型号ID ${row.modelId}` }}</template></el-table-column><el-table-column v-if="kind === 'devices'" label="客户企业" min-width="160"><template #default="{ row }"><RouterLink :to="`/assets/companies/${row.companyId}`">{{ row.companyName || `企业ID ${row.companyId}` }}</RouterLink></template></el-table-column><el-table-column label="状态" width="100"><template #default="{ row }"><el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'">{{ activeStatus(row.status) }}</el-tag></template></el-table-column><el-table-column label="操作" width="100"><template #default="{ row }"><RouterLink :to="`/assets/${kind}/${row.id}`">查看详情</RouterLink></template></el-table-column></el-table></div><el-pagination class="pagination" :total="total" :page-size="20" :current-page="filter.page" layout="prev, pager, next, total" @current-change="(page: number) => { filter.page = page; router.replace({ query: Object.fromEntries(query()) }) }" /></section><el-dialog v-model="open" :title="`新建${assetLabels[kind]}`" width="min(680px, 94vw)" :close-on-click-modal="false" :close-on-press-escape="!work.loading.value" :show-close="!work.loading.value"><form @submit.prevent="submit"><AssetForm :kind="kind" :form="form" :companies="companies" :models="models" :disabled="work.loading.value"/><p v-if="validation" class="field-error">{{ validation }}</p><div class="actions"><el-button native-type="submit" type="primary" :loading="work.loading.value">确认创建</el-button><el-button :disabled="work.loading.value" @click="open = false">取消</el-button></div></form></el-dialog></template>
