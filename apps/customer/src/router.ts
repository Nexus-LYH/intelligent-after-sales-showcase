import MoneyReviewsPage from '../../staff/src/features/money/MoneyReviewsPage.vue'
import FinancePage from '../../staff/src/features/finance/FinancePage.vue'
import NotificationsView from '../../staff/src/views/NotificationsView.vue'
import {canOpenNotifications} from '../../staff/src/features/notification/notification-client'
import {canReadFinance} from '../../staff/src/features/finance/finance-client'
import { createRouter, createWebHistory } from 'vue-router'
import { useSession } from '../../staff/src/session'
import LoginView from '../../staff/src/views/LoginView.vue'
import PasswordView from '../../staff/src/views/PasswordView.vue'
import AccountView from '../../staff/src/views/AccountView.vue'
import DeniedView from '../../staff/src/views/DeniedView.vue'
import DevicesView from './views/DevicesView.vue'
import DeviceView from './views/DeviceView.vue'
import RepairView from './views/RepairView.vue'
import SubmissionView from './views/SubmissionView.vue'
import TicketsView from '../../staff/src/views/TicketsView.vue'
import TicketView from '../../staff/src/views/TicketView.vue'
export const router = createRouter({ history: createWebHistory('/customer/'), routes: [
  {path:'/money-reviews/:groupId/:reviewId?',component:MoneyReviewsPage,meta:{auth:true,finance:true}},
  { path: '/', redirect: '/devices' }, { path: '/login', component: LoginView },
  { path: '/account', component: AccountView, meta: { auth: true } },
  { path: '/notifications', component: NotificationsView, meta:{auth:true,notification:true} },
  { path: '/notifications/:messageId', component: NotificationsView, meta:{auth:true,notification:true} },
  { path: '/account/password', component: PasswordView, meta: { auth: true, restricted: true } },
  { path: '/repairs/new', component: RepairView, meta: { auth: true } },
  { path: '/submissions/:operationId?', component: SubmissionView, meta: { auth: true } },
  {path:'/finance',component:FinancePage,meta:{auth:true,finance:true}},
  {path:'/tickets/:id/finance',component:FinancePage,meta:{auth:true,finance:true}},
  {path:'/tickets/:id/finance/quotes/:quoteId',component:FinancePage,meta:{auth:true,finance:true}},
  { path: '/tickets', component: TicketsView, meta: { auth: true } },
  { path: '/tickets/:id/rounds/:roundId', component: TicketView, meta: {auth:true} },
  { path: '/tickets/:id', component: TicketView, meta: { auth: true } },
  { path: '/devices', component: DevicesView, meta: { auth: true } },
  { path: '/devices/:id', component: DeviceView, meta: { auth: true } },
  { path: '/:pathMatch(.*)*', component: DeniedView },
] })
router.beforeEach(async to => {
  const auth = useSession(); await auth.initialize()
  if (auth.phase === 'PASSWORD_CHANGE_REQUIRED' && !to.meta.restricted) return '/account/password'
  if (auth.phase === 'NORMAL' && to.path === '/login') return '/devices'
  if(to.meta.finance&&auth.phase==='NORMAL'&&!canReadFinance(auth.identity))return '/403'
  if(to.meta.notification&&auth.phase==='NORMAL'&&!canOpenNotifications(auth.identity))return '/403'
  if (to.meta.auth && auth.phase === 'ANONYMOUS') return '/login'
})

