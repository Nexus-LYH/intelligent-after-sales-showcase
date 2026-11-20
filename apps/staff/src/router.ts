import MoneyReviewsPage from './features/money/MoneyReviewsPage.vue'
import FeeApprovalPage from './features/fee-approval/FeeApprovalPage.vue'
import FeeExecutionView from './views/FeeExecutionView.vue'
import {canReadFees} from './features/fee-approval/fee-client'
import FinancePage from './features/finance/FinancePage.vue'
import {canReadFinance} from './features/finance/finance-client'
import StockExecutionPage from './features/stock/StockExecutionPage.vue'
import StockInboundPage from './features/stock/StockInboundPage.vue'
import StockLedgerPage from './features/stock/StockLedgerPage.vue'
import{canReadStock}from'./features/stock/stock-client'
import {canOpenApprovals} from './features/approval/approval-integration'
import ApprovalPage from './features/approval/ApprovalPage.vue'
import NotificationsView from './views/NotificationsView.vue'
import MoneyPage from './features/money/MoneyPage.vue'
import {canReadMoney} from './features/money/money-client'
import {canOpenNotifications} from './features/notification/notification-client'
import { canOpenTickets } from './ticket-flow'
import { createRouter, createWebHistory } from 'vue-router'
import { useSession } from './session'
import LoginView from './views/LoginView.vue'
import AccountView from './views/AccountView.vue'
import PasswordView from './views/PasswordView.vue'
import UsersView from './views/UsersView.vue'
import UserView from './views/UserView.vue'
import TransferInboxView from './views/TransferInboxView.vue'
import TicketsView from './views/TicketsView.vue'
import TicketView from './views/TicketView.vue'
import DeniedView from './views/DeniedView.vue'
import AssetsView from './views/AssetsView.vue'
import AssetDetailView from './views/AssetDetailView.vue'
export const router = createRouter({ history: createWebHistory(import.meta.env.BASE_URL), routes: [
  {path:'/money-reviews/:groupId/:reviewId?',component:MoneyReviewsPage,meta:{auth:true,money:true}},
  { path: '/', redirect: '/account' },
  { path: '/login', component: LoginView },
  { path: '/platform/login', component: LoginView },
  { path: '/account', component: AccountView, meta: { auth: true } },
  { path: '/account/password', component: PasswordView, meta: { auth: true, restricted: true } },
  { path: '/users', component: UsersView, meta: { auth: true, manage: true } },
  { path: '/users/:id', component: UserView, meta: { auth: true, manage: true } },
  ...(['companies', 'models', 'devices'] as const).flatMap(kind => [
    { path: `/assets/${kind}`, component: AssetsView, props: { kind }, meta: { auth: true, asset: true } },
    { path: `/assets/${kind}/:id`, component: AssetDetailView, props: { kind }, meta: { auth: true, asset: true } },
  ]),
  { path: '/transfers', component: TransferInboxView, meta: {auth:true,transferInbox:true} },
  { path: '/transfers/:requestId', component: TransferInboxView, meta: {auth:true,transferInbox:true} },
  {path:'/fee-approvals',component:FeeApprovalPage,meta:{auth:true,fees:true}},
  {path:'/tickets/:id/fee-approvals',component:FeeApprovalPage,meta:{auth:true,fees:true}},
  {path:'/tickets/:id/fee-approvals/:applicationId',component:FeeApprovalPage,meta:{auth:true,fees:true}},
  {path:'/fee-executions',component:FeeExecutionView,meta:{auth:true,fees:true}},
  {path:'/tickets/:id/fee-executions/:applicationId',component:FeeExecutionView,meta:{auth:true,fees:true}},
  {path:'/finance',component:FinancePage,meta:{auth:true,finance:true}},
  {path:'/money/:groupId?',component:MoneyPage,meta:{auth:true,money:true}},
  {path:'/tickets/:id/finance',component:FinancePage,meta:{auth:true,finance:true}},
  {path:'/tickets/:id/finance/quotes/:quoteId',component:FinancePage,meta:{auth:true,finance:true}},
  { path: '/tickets', component: TicketsView, meta: { auth: true, ticketRead: true } },
  { path: '/approvals', redirect:'/approvals/parts' },
  { path: '/approvals/:applicationId', redirect:to=>'/approvals/parts/'+encodeURIComponent(String(to.params.applicationId)) },
  { path: '/tickets/:id/parts', component: ApprovalPage, meta:{auth:true,approval:true,ticketRead:true} },
  { path: '/tickets/:id/rounds/:roundId', component: TicketView, meta: {auth:true,ticketRead:true} },
  { path: '/tickets/:id', component: TicketView, meta: { auth: true, ticketRead: true } },
  { path: '/approvals/parts', component: ApprovalPage, meta:{auth:true,approval:true} },
  { path: '/notifications', component: NotificationsView, meta:{auth:true,notification:true} },
  { path: '/notifications/:messageId', component: NotificationsView, meta:{auth:true,notification:true} },
  { path: '/approvals/parts/:applicationId', component: ApprovalPage, meta:{auth:true,approval:true} },
  {path:'/stock/warehouses/:warehouseId/inbound',component:StockInboundPage,meta:{auth:true,stock:true}},
  {path:'/stock/executions',component:StockExecutionPage,meta:{auth:true,stock:true}},
  {path:'/stock/warehouses/:warehouseId/orders/:orderId',component:StockExecutionPage,meta:{auth:true,stock:true}},
  {path:'/stock/engineering',component:StockExecutionPage,meta:{auth:true,stockEngineer:true}},
  {path:'/stock/engineering/orders/:orderId',component:StockExecutionPage,meta:{auth:true,stockEngineer:true}},
  {path:'/stock/inbound',component:StockInboundPage,meta:{auth:true,stock:true}},
  {path:'/stock/ledger',component:StockLedgerPage,meta:{auth:true,stock:true}},
  { path: '/403', component: DeniedView },
  { path: '/:pathMatch(.*)*', component: DeniedView },
] })
router.beforeEach(async (to) => {
  const auth = useSession()
  await auth.initialize()
  if (auth.phase === 'PASSWORD_CHANGE_REQUIRED' && !to.meta.restricted) return '/account/password'
  if (auth.phase === 'NORMAL' && (to.path === '/login' || to.path === '/platform/login')) return '/account'
  if (to.meta.auth && auth.phase === 'ANONYMOUS') return auth.loginEntry
  if(to.meta.fees&&auth.phase==='NORMAL'&&!canReadFees(auth.identity))return '/403'
  if(to.meta.finance&&auth.phase==='NORMAL'&&!canReadFinance(auth.identity))return '/403'
  if(to.meta.money&&auth.phase==='NORMAL'&&!canReadMoney(auth.identity))return '/403'
  if(to.meta.stockEngineer&&auth.phase==='NORMAL'&&(auth.identity?.identityType!=='EMPLOYEE'||!auth.identity.permissions.includes('ticket.engineer.read')))return '/403'
  if(to.meta.stock&&auth.phase==='NORMAL'&&!canReadStock(auth.identity))return '/403'
  if (to.meta.approval && auth.phase==='NORMAL' && !canOpenApprovals(auth.identity)) return '/403'
  if (to.meta.notification && auth.phase==='NORMAL' && !canOpenNotifications(auth.identity)) return '/403'
  if (to.meta.manage && auth.phase === 'NORMAL' && !auth.canManage) return '/403'
  if (to.meta.transferInbox && auth.phase === 'NORMAL' && (auth.identity?.identityType !== 'EMPLOYEE' || !auth.identity.permissions.includes('ticket.engineer.read') || !auth.identity.permissions.includes('ticket.assignment.accept'))) return '/403'
  if (to.meta.ticketRead && auth.phase === 'NORMAL' && !canOpenTickets(auth.identity)) return '/403'
  if (to.meta.asset && auth.phase === 'NORMAL' && (auth.identity?.identityType !== 'EMPLOYEE' || !auth.identity.permissions.includes('asset.read'))) return '/403'
})


