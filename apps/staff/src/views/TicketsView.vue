<script setup lang="ts">
import { computed, reactive, ref, watch, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { tickets, type TicketSummary } from '../ticket-api'
import { useTicketWork } from '../ticket-work'
import { customerPortal } from '../portal'
import { statusLabel, ticketStatuses } from '../ticket-flow'
import { shanghaiTime } from '../display'
const route = useRoute(), router = useRouter(), work = useTicketWork(() => route.fullPath)
const rows = ref<TicketSummary[]>([]), total = ref(0), keyword = ref('')
const previewId = ref('')
const preview = computed(() => rows.value.find(ticket => ticket.id === previewId.value) ?? rows.value[0])
const filtered = computed(() => Object.keys(route.query).some(key => key !== 'page'))
function statusVariant(status: string) {
  switch (status) {
    case 'PENDING_ACCEPTANCE':
    case 'WAITING_ASSIGNMENT':
    case 'WAITING_INFORMATION':
    case 'PENDING_CUSTOMER_CONFIRMATION':
      return 'chip--warning'
    case 'RESOLVED':
    case 'CLOSED':
      return 'chip--success'
    case 'CANCELLED':
    case 'REJECTED':
      return 'chip--danger'
    case 'ASSIGNED':
    case 'IN_PROGRESS':
      return 'chip--accent'
  }
}
const filter = reactive({ deviceId: '', status: '', priority: '', departmentId: '', mine: '', coordination: '', reopen:'', cancellation: '', from: '', to: '' })
function search() {
  const query: Record<string, string> = {}
  if (keyword.value) query.keyword = keyword.value
  if (filter.deviceId) query.deviceId = filter.deviceId
  if (filter.status) query.status = filter.status
  if (filter.priority) query.priority = filter.priority
  if (!customerPortal && filter.departmentId) query.departmentId = filter.departmentId
  if (!customerPortal && filter.mine) query.mine = filter.mine
  if (!customerPortal && filter.coordination) query.coordination = filter.coordination
  if (!customerPortal && filter.reopen) query.reopen=filter.reopen
  if (!customerPortal && filter.cancellation) query.cancellation = filter.cancellation
  if (filter.from) query.from = new Date(filter.from).toISOString()
  if (filter.to) query.to = new Date(filter.to).toISOString()
  if (query.from && query.to && query.from > query.to) { work.message.value = '提交开始时间不能晚于结束时间'; return }
  void router.push({ path: '/tickets', query })
}
async function load() {
  const scope = work.begin(); rows.value = []; total.value = 0
  try { const q = new URLSearchParams({ page: String(Number(route.query.page) || 1), size: '20' }); for (const key of ['keyword', 'deviceId', 'status', 'priority', 'departmentId', 'mine', 'coordination', 'cancellation', 'reopen', 'from', 'to']) if (typeof route.query[key] === 'string') q.set(key, route.query[key] as string); const r = await tickets.list(q); if (!scope.current()) return; rows.value = r.items; total.value = r.total } catch (e) { work.fail(e, scope) } finally { scope.finish() }
}
watch(() => route.fullPath, () => {
  keyword.value = typeof route.query.keyword === 'string' ? route.query.keyword : ''
  filter.deviceId = typeof route.query.deviceId === 'string' ? route.query.deviceId : ''
  filter.status = typeof route.query.status === 'string' ? route.query.status : ''
  filter.priority = typeof route.query.priority === 'string' ? route.query.priority : ''
  filter.departmentId = typeof route.query.departmentId === 'string' ? route.query.departmentId : ''
  filter.mine = typeof route.query.mine === 'string' ? route.query.mine : ''
  filter.reopen=typeof route.query.reopen==='string'?route.query.reopen:''
  filter.cancellation = typeof route.query.cancellation === 'string' ? route.query.cancellation : ''
  filter.coordination = typeof route.query.coordination === 'string' ? route.query.coordination : ''
  for (const key of ['from', 'to'] as const) {
    const value = route.query[key], date = typeof value === 'string' ? new Date(value) : null
    filter[key] = date && Number.isFinite(date.getTime()) ? new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16) : ''
  }
  void load()
}, { immediate: true })
const readTimer = !customerPortal ? setInterval(() => { if (!work.busy.value && work.auth.phase === 'NORMAL') void load() },30000) : undefined
function listVisibility() { if (customerPortal) return; if (document.visibilityState === 'hidden') { work.begin().finish(); rows.value=[];total.value=0 } else if (work.auth.phase==='NORMAL') void load() }
document.addEventListener('visibilitychange',listVisibility)
onBeforeUnmount(()=>{clearInterval(readTimer);document.removeEventListener('visibilitychange',listVisibility)})
function pageChanged(page: number) { void router.push({ query: { ...route.query, page } }) }
</script>
<template>
  <section class="page-heading">
    <h1>{{ customerPortal ? '本人报修' : '授权工单队列' }}</h1>
    <p>{{ customerPortal ? '仅显示本人提交且当前有权查看的工单。' : '客服仅见当前授权企业，工程师仅见当前本人分派；范围由服务端逐次核验。' }}</p>
  </section>
  <section class="panel ticket-workspace">
    <div v-if="customerPortal" class="ticket-workspace__customer-actions">
      <RouterLink class="button button--primary" to="/devices">选择设备新报修</RouterLink>
      <RouterLink to="/submissions">查询提交结果</RouterLink>
    </div>
    <details class="ticket-workspace__filters">
      <summary>筛选与{{ customerPortal ? '搜索' : '快捷队列' }}<small v-if="filtered">已筛选</small></summary>
      <nav v-if="!customerPortal" class="ticket-workspace__shortcuts" aria-label="工单快捷筛选">
        <RouterLink v-if="work.auth.identity?.permissions.includes('ticket.reopen.review') && work.auth.identity?.permissions.includes('ticket.read')" :to="{path:'/tickets',query:{reopen:'PENDING_REVIEW'}}">重开申请待主管审核（源轮仍关闭）</RouterLink>
        <RouterLink v-if="work.auth.identity?.permissions.includes('ticket.transfer.request') && work.auth.identity?.permissions.includes('ticket.read')" :to="{path:'/tickets',query:{coordination:'TRANSFER_REQUIRED'}}">转派待协调</RouterLink>
        <RouterLink v-if="work.auth.identity?.permissions.includes('ticket.cancel.review') && work.auth.identity?.permissions.includes('ticket.read')" :to="{path:'/tickets',query:{cancellation:'PENDING_REVIEW'}}">取消申请待审核（主流程继续）</RouterLink>
        <RouterLink v-if="work.auth.identity?.permissions.includes('ticket.read')" :to="{ path: '/tickets', query: { status: 'PENDING_ACCEPTANCE' } }">客服待受理</RouterLink>
        <RouterLink v-if="work.auth.identity?.permissions.includes('ticket.read')" :to="{ path: '/tickets', query: { status: 'WAITING_ASSIGNMENT' } }">待分派</RouterLink>
        <RouterLink v-if="work.auth.identity?.permissions.includes('ticket.engineer.read')" :to="{ path: '/tickets', query: { mine: 'ASSIGNED' } }">本人待接单</RouterLink>
        <RouterLink v-if="work.auth.identity?.permissions.includes('ticket.engineer.read')" :to="{ path: '/tickets', query: { mine: 'IN_PROGRESS' } }">本人处理中</RouterLink>
        <RouterLink :to="{ path: '/tickets', query: { status: 'WAITING_INFORMATION' } }">待补充队列</RouterLink>
        <RouterLink v-if="work.auth.identity?.permissions.includes('ticket.read')" :to="{ path: '/tickets', query: { coordination: 'SUPPLEMENT_LIMIT_REACHED' } }">待补充额度耗尽待协调</RouterLink>
        <RouterLink v-if="work.auth.identity?.permissions.includes('ticket.read')" :to="{ path: '/tickets', query: { status: 'RESOLVED' } }">已解决待关闭</RouterLink>
        <RouterLink v-if="work.auth.identity?.permissions.includes('ticket.read')" :to="{ path: '/tickets', query: { coordination: 'CONFIRMATION_LIMIT_REACHED' } }">待确认超72小时待协调</RouterLink>
        <RouterLink v-for="state in work.auth.identity?.permissions.includes('ticket.engineer.read') ? ['WAITING_INFORMATION','PENDING_CUSTOMER_CONFIRMATION','RESOLVED','CLOSED','CANCELLED'] : []" :key="state" :to="{ path: '/tickets', query: { mine: state } }">本人{{ statusLabel(state) }}</RouterLink>
      </nav>
      <p v-if="filter.coordination">{{ filter.coordination === 'TRANSFER_REQUIRED' ? '拒绝、撤回、超时或负责人失格需要协调；新请求确认中仍非已交接。按服务端当前scope计数，GET不推进或恢复旧状态。' : filter.coordination === 'SUPPLEMENT_LIMIT_REACHED' ? '按服务端补充累计等待≥72小时筛选，不自动恢复原状态。' : '按服务端累计待确认≥72小时筛选，GET只查询；不自动确认、关闭或发送提醒。' }}</p>
      <form class="actions" @submit.prevent="search">
        <el-input v-model="keyword" placeholder="搜索报修标题或工单号" aria-label="搜索报修"/>
        <el-input v-model="filter.deviceId" placeholder="设备ID（可选）" aria-label="设备筛选"/>
        <select v-model="filter.status" aria-label="报修状态"><option value="">全部已开放状态</option><option v-for="(label,key) in ticketStatuses" :key="key" :value="key">{{ label }}</option></select>
        <select v-model="filter.priority" aria-label="优先级筛选"><option value="">全部优先级</option><option v-for="p in ['P1','P2','P3']" :key="p" :value="p">{{ p }}</option></select>
        <label>提交开始<input v-model="filter.from" type="datetime-local" aria-label="提交开始"/></label>
        <label>提交结束<input v-model="filter.to" type="datetime-local" aria-label="提交结束"/></label>
        <el-button native-type="submit">查询</el-button>
        <el-button :disabled="work.busy.value" @click="load">刷新</el-button>
      </form>
    </details>
    <p v-if="work.message.value" role="alert">{{ work.message.value }} {{ work.trace.value }}</p>
    <p v-if="work.busy.value">正在核对当前访问权限…</p>
    <p v-else-if="!rows.length && !work.message.value">当前范围暂无可显示的报修。</p>
    <div v-if="rows.length" class="ticket-workspace__body">
      <section class="ticket-queue" aria-label="工单列表">
        <h2>工单列表<span v-if="total"> · {{ total }} 条</span></h2>
        <div class="ticket-queue__rows">
          <article v-for="t in rows" :key="t.id" class="ticket-row" :class="{ 'ticket-row--selected': preview?.id === t.id }">
            <RouterLink class="ticket-row__link" :to="`/tickets/${t.id}`">
              <strong>{{ t.title }}</strong>
              <span class="ticket-row__meta">
                <span class="chip chip--sm chip--soft" :class="statusVariant(t.status)">
                  <span class="chip__label">{{ statusLabel(t.status) }}</span>
                </span>
                <span v-if="t.priority">{{ t.priority }}</span>
                <time :datetime="t.submittedAt">{{ shanghaiTime(t.submittedAt) }}</time>
              </span>
              <span class="ticket-row__meta ticket-row__number">{{ t.ticketNo }}</span>
              <span v-if="t.deviceSnapshot?.name || t.companySnapshot?.name" class="ticket-row__meta">{{ [t.deviceSnapshot?.name, t.companySnapshot?.name].filter(Boolean).join(' · ') }}</span>
            </RouterLink>
            <button type="button" class="button button--sm button--ghost ticket-row__preview" :aria-label="`预览工单 ${t.ticketNo}`" :aria-pressed="preview?.id === t.id" @click="previewId = t.id">
              预览
            </button>
          </article>
        </div>
      </section>
      <aside v-if="preview" class="card ticket-peek" aria-label="工单概览" aria-live="polite">
        <div :key="preview.id" class="card__header ticket-peek__heading">
          <p class="card__description">{{ preview.ticketNo }}</p>
          <h2 class="card__title">{{ preview.title }}</h2>
        </div>
        <div class="card__content">
          <dl>
            <dt>状态</dt><dd>{{ statusLabel(preview.status) }}</dd>
            <template v-if="preview.priority"><dt>优先级</dt><dd>{{ preview.priority }}</dd></template>
            <template v-if="preview.deviceSnapshot?.name"><dt>设备</dt><dd>{{ preview.deviceSnapshot.name }}</dd></template>
            <template v-if="preview.companySnapshot?.name"><dt>客户企业</dt><dd>{{ preview.companySnapshot.name }}</dd></template>
            <dt>提交时间（北京时间）</dt><dd><time :datetime="preview.submittedAt">{{ shanghaiTime(preview.submittedAt) }}</time></dd>
          </dl>
          <RouterLink class="button button--primary" :to="`/tickets/${preview.id}`">打开完整工单</RouterLink>
        </div>
      </aside>
    </div>
    <el-pagination :current-page="Number(route.query.page) || 1" :total="total" :page-size="20" layout="prev, pager, next, total" @current-change="pageChanged"/>
  </section>
</template>






