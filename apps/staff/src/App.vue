<script setup lang="ts">
import{canReadFees}from'./features/fee-approval/fee-client'
import{canReadFinance}from'./features/finance/finance-client'
import{canReadStock}from'./features/stock/stock-client'
import {canOpenApprovals} from './features/approval/approval-integration'
import {canOpenNotifications} from './features/notification/notification-client'
import {canReadMoney} from './features/money/money-client'
import { canOpenTickets } from './ticket-flow'
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSession } from './session'
import { customerPortal, portalHome, portalName } from './portal'
import './service-workspace.css'
const auth = useSession(), route = useRoute(), router = useRouter()
const menuOpen = ref(false)
watch(() => route.fullPath, () => { menuOpen.value = false })
let initialRouteReady = false
void router.isReady().then(() => { initialRouteReady = true })
const isLogin = computed(() => route.path === '/login' || route.path === '/platform/login')
const blocked = computed(() => !['ANONYMOUS', 'NORMAL', 'PASSWORD_CHANGE_REQUIRED'].includes(auth.phase))
watch(() => auth.phase, (phase) => {
  // Initial async route guards own the first redirect; do not overwrite /platform/login
  // while the visible route is still the router's startup placeholder.
  if (!initialRouteReady) return
  if (phase === 'PASSWORD_CHANGE_REQUIRED') void router.replace('/account/password')
  if (phase === 'ANONYMOUS' && !isLogin.value) void router.replace(auth.loginEntry)
  if (phase === 'NORMAL' && isLogin.value) void router.replace(portalHome)
  if (phase === 'NORMAL' && route.meta.ticketRead && !canOpenTickets(auth.identity)) void router.replace('/403')
  if(phase==='NORMAL'&&route.meta.fees&&!canReadFees(auth.identity))void router.replace('/403')
  if(phase==='NORMAL'&&route.meta.finance&&!canReadFinance(auth.identity))void router.replace('/403')
  if(phase==='NORMAL'&&route.meta.stockEngineer&&(auth.identity?.identityType!=='EMPLOYEE'||!auth.identity.permissions.includes('ticket.engineer.read')))void router.replace('/403')
  if(phase==='NORMAL'&&route.meta.stock&&!canReadStock(auth.identity))void router.replace('/403')
  if (phase === 'NORMAL' && route.meta.approval && !canOpenApprovals(auth.identity)) void router.replace('/403')
  if (phase === 'NORMAL' && route.meta.notification && !canOpenNotifications(auth.identity)) void router.replace('/403')
  if (phase === 'NORMAL' && route.meta.manage && !auth.canManage) void router.replace('/403')
})
async function exitActual() { if (auth.actual && confirm(`退出当前实际会话：${auth.actual.account}？仅撤销本浏览器当前会话。`)) await auth.logout(true) }
</script>
<template>
  <div class="service-shell" :class="{ 'service-shell--open': auth.phase === 'NORMAL', 'service-shell--customer': customerPortal }">
    <aside v-if="auth.phase === 'NORMAL'" class="service-rail">
      <RouterLink class="service-brand" :to="portalHome">
        <span class="service-brand__mark" aria-hidden="true">启</span>
        <span>启维服务<small>{{ customerPortal ? '客户门户' : '服务工作台' }}</small></span>
      </RouterLink>
      <button type="button" class="service-menu-toggle button button--secondary" aria-controls="service-navigation" :aria-expanded="menuOpen" @click="menuOpen = !menuOpen">
        {{ menuOpen ? '收起菜单' : '展开菜单' }}
      </button>
      <nav id="service-navigation" class="service-navigation" :class="{ 'service-navigation--expanded': menuOpen }" aria-label="工作台导航">
        <section class="service-group">
          <h2>服务</h2>
          <RouterLink v-if="customerPortal" to="/devices">授权设备</RouterLink>
          <RouterLink v-if="customerPortal || canOpenTickets(auth.identity)" to="/tickets">{{ customerPortal ? '本人报修' : '工单队列' }}</RouterLink>
          <RouterLink v-if="!customerPortal && auth.identity?.identityType === 'EMPLOYEE' && auth.identity.permissions.includes('ticket.engineer.read') && auth.identity.permissions.includes('ticket.assignment.accept')" to="/transfers">发给我的转派</RouterLink>
          <RouterLink v-if="canOpenNotifications(auth.identity)" to="/notifications">本人站内信</RouterLink>
        </section>
        <section v-if="!customerPortal" class="service-group">
          <h2>备件与库存</h2>
          <RouterLink v-if="canOpenApprovals(auth.identity)" to="/approvals/parts">备件申请与审批</RouterLink>
          <RouterLink v-if="canReadStock(auth.identity)" to="/stock/inbound">库存入库</RouterLink>
          <RouterLink v-if="canReadStock(auth.identity)" to="/stock/executions">库存执行</RouterLink>
          <RouterLink v-if="canReadStock(auth.identity)" to="/stock/ledger">库存余额与流水</RouterLink>
          <RouterLink v-if="auth.identity?.identityType === 'EMPLOYEE' && auth.identity.permissions.includes('ticket.engineer.read')" to="/stock/engineering">备件消耗</RouterLink>
        </section>
        <section class="service-group">
          <h2>费用</h2>
          <RouterLink v-if="canReadFinance(auth.identity)" to="/finance">费用报价</RouterLink>
          <RouterLink v-if="!customerPortal && canReadFees(auth.identity)" to="/fee-approvals">内部费用审批</RouterLink>
          <RouterLink v-if="!customerPortal && canReadFees(auth.identity)" to="/fee-executions">收费执行</RouterLink>
          <RouterLink v-if="!customerPortal && canReadMoney(auth.identity)" to="/money">线下资金登记</RouterLink>
        </section>
        <section class="service-group">
          <h2>账户与资料</h2>
          <RouterLink v-if="!customerPortal && auth.canManage" to="/users">{{ auth.identity?.realm === 'PLATFORM' ? '平台账号' : '租户用户' }}</RouterLink>
          <template v-if="!customerPortal && auth.identity?.identityType === 'EMPLOYEE' && auth.identity.permissions.includes('asset.read')">
            <RouterLink to="/assets/companies">客户企业</RouterLink>
            <RouterLink to="/assets/models">产品型号</RouterLink>
            <RouterLink to="/assets/devices">设备档案</RouterLink>
          </template>
          <RouterLink to="/account">本人账户</RouterLink>
          <RouterLink to="/account/password">修改密码</RouterLink>
        </section>
      </nav>
    </aside>
    <div class="service-content">
      <header v-if="auth.phase !== 'NORMAL'" class="topbar">
        <RouterLink class="brand" :to="portalHome"><span class="brand-icon">启</span>启维服务 <small>{{ portalName }}</small></RouterLink>
        <span class="connection">账户安全 · 服务协作</span>
      </header>
      <div v-if="auth.ready" class="identity-bar service-identity">
        <div><small>{{ auth.identity?.realm === 'PLATFORM' ? '平台账号' : auth.identity?.tenant?.name }}</small><strong>{{ auth.identity?.displayName }}（{{ auth.identity?.account }}）</strong></div>
        <el-button :loading="auth.busy" @click="auth.logout()">退出当前会话</el-button>
      </div>
      <main :class="{ 'auth-main': isLogin }">

    <section v-if="auth.notice" class="notice" role="status"><strong>{{ auth.notice }}</strong><span v-if="auth.traceId" class="trace">追踪标识：{{ auth.traceId }}</span></section>
    <section v-if="blocked" class="panel recovery" aria-live="polite">
      <p class="eyebrow">身份与操作恢复</p><h1>{{ auth.phase === 'CHECKING' ? '正在核验当前会话' : '请确认当前状态' }}</h1>
      <p>核验完成前不显示旧正文，不重复提交密码或写操作。</p>
      <p v-if="auth.actual">当前实际账号：{{ auth.actual.account }} · {{ auth.actual.realm === 'PLATFORM' ? '平台' : auth.actual.tenant?.code }}</p>
      <div v-if="auth.phase === 'ENTRY_MISMATCH'" class="actions"><a href="/staff/account">员工管理端</a><a href="/customer/devices">客户门户</a></div>
      <div class="actions">
        <el-button v-if="!['CHECKING','LOGIN_PENDING','LOGOUT_PENDING','PASSWORD_RESULT_UNKNOWN'].includes(auth.phase)" type="primary" :disabled="auth.busy" @click="auth.phase === 'LOGOUT_RESULT_UNKNOWN' ? auth.recoverLogout() : auth.synchronize(true)">重新核验</el-button>
        <el-button v-if="auth.actual" :disabled="auth.busy" @click="exitActual">退出当前实际会话</el-button>
        <el-button v-if="auth.phase === 'PASSWORD_RESULT_UNKNOWN'" @click="auth.resetLogin(); router.replace(auth.loginEntry)">前往登录核对</el-button>
      </div>
    </section>
    <RouterView v-else v-slot="{ Component }"><component :is="Component" v-if="isLogin || auth.ready || !route.meta.auth" /></RouterView>
  </main><footer v-if="auth.phase !== 'NORMAL'">启维服务 · 仅展示当前授权范围</footer>
    </div>
  </div>
</template>
