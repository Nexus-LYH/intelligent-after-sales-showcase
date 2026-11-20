import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { api, ApiError, isUnavailableStatus, expectedOf, matches, type Csrf, type Expected, type Identity, type LoginInput } from './api'
import { captureRequestScope, sameRevocation, type PendingRevocation } from './request-scope'
import { acceptsIdentity, portalName, customerPortal } from './portal'
export type Phase = 'ANONYMOUS' | 'CHECKING' | 'LOGIN_PENDING' | 'LOGIN_RESULT_UNKNOWN' | 'LOGIN_RESULT_CONFLICT' | 'AUTH_RECOVERY_PENDING' | 'NORMAL' | 'PASSWORD_CHANGE_REQUIRED' | 'IDENTITY_UNAVAILABLE' | 'LOGOUT_PENDING' | 'LOGOUT_RESULT_UNKNOWN' | 'PASSWORD_RESULT_UNKNOWN' | 'ENTRY_MISMATCH'
const storageKey = 'ias-login-expectation'
function readExpected(): Expected | null {
  try { const v = JSON.parse(sessionStorage.getItem(storageKey) ?? 'null'); if (v && typeof v.attemptId === 'string' && typeof v.account === 'string' && Date.parse(v.expiresAt) > Date.now()) return v; sessionStorage.removeItem(storageKey); return null } catch { sessionStorage.removeItem(storageKey); return null }
}
export const useSession = defineStore('session', () => {
  const phase = ref<Phase>('CHECKING'), identity = ref<Identity | null>(null), csrf = ref<Csrf | null>(null)
  const expected = ref<Expected | null>(readExpected()), actual = ref<Identity | null>(null)
  const notice = ref(''), traceId = ref(''), busy = ref(false), retryUntil = ref(0), generation = ref(0)
  const loginEntry = ref('/login')
  const revocationPending = ref<PendingRevocation | null>(null)
  // Unknown outcomes remain in memory, bound to the original actor/namespace.
  // Switching identity never exposes them or automatically resends them.
  const outstandingRevocations = new Map<string, PendingRevocation>()
  let initial: Promise<void> | undefined
  let current: { scope: ReturnType<typeof captureRequestScope>; promise: Promise<void> } | undefined
  let busyOwner: symbol | null = null
  const source = { get generation() { return generation.value }, get identity() { return identity.value }, get expected() { return expected.value } }
  let logoutExpected: Expected | null = null
  let peerUntil = 0
  const channel = typeof BroadcastChannel !== 'undefined' ? new BroadcastChannel('ias-session') : null
  const ready = computed(() => phase.value === 'NORMAL' || phase.value === 'PASSWORD_CHANGE_REQUIRED')
  const canManage = computed(() => phase.value === 'NORMAL' && !!identity.value?.permissions?.includes(identity.value.realm === 'PLATFORM' ? 'identity.platform.session.revoke' : 'identity.session.revoke'))
  function saveExpected(value: Expected | null) { expected.value = value; if (value) sessionStorage.setItem(storageKey, JSON.stringify(value)); else sessionStorage.removeItem(storageKey) }
  function clear(next: Phase) { generation.value++; current = undefined; busyOwner = null; busy.value = false; identity.value = null; csrf.value = null; phase.value = next }
  function startBusy() { const owner = Symbol('session-request'); busyOwner = owner; busy.value = true; return owner }
  function finishBusy(owner: symbol, scope: ReturnType<typeof captureRequestScope>) { if (busyOwner === owner && scope.current()) { busyOwner = null; busy.value = false } }
  function rememberRevocation(operation: PendingRevocation) { outstandingRevocations.set(operation.key, { ...operation }) }
  function completeRevocation(operation: PendingRevocation) {
    if (!sameRevocation(revocationPending.value, operation)) return false
    revocationPending.value = null
    if (sameRevocation(outstandingRevocations.get(operation.key) ?? null, operation)) outstandingRevocations.delete(operation.key)
    return true
  }
  function belongsTo(operation: PendingRevocation, me: Identity) { return operation.actor === me.userId && (!operation.actorRealm || (operation.actorRealm === me.realm && operation.actorNamespace === (me.tenant?.id ?? 'PLATFORM'))) }
  function restoreRevocation(me: Identity) {
    const pending = revocationPending.value
    if (pending && !belongsTo(pending, me)) { rememberRevocation(pending); if (sameRevocation(revocationPending.value, pending)) revocationPending.value = null }
    if (!revocationPending.value) {
      const owned = [...outstandingRevocations.values()].find(operation => belongsTo(operation, me))
      if (owned) revocationPending.value = { ...owned }
    }
  }
  function error(e: unknown) { const err = e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', '操作未完成'); notice.value = `${err.message}（${err.code}）`; traceId.value = err.traceId; if (err.retryAfter) retryUntil.value = Date.now() + err.retryAfter * 1000; return err }
  function broadcast() { channel?.postMessage({ kind: 'changed' }) }
  async function synchronize(preview = false): Promise<void> {
    if (current?.scope.current()) return current.promise
    const reference = expected.value ?? (identity.value ? expectedOf(identity.value) : null)
    clear('CHECKING'); actual.value = null
    const task: { scope: ReturnType<typeof captureRequestScope>; promise: Promise<void> } = { scope: captureRequestScope(source, (): boolean => current === task), promise: Promise.resolve() }
    current = task
    task.promise = (async () => {
      try {
        const me = await api.me() // FIRST confirmation Set-Cookie must finish before csrf.
        if (!task.scope.current()) return
        actual.value = me
        loginEntry.value = !customerPortal && me.realm === 'PLATFORM' ? '/platform/login' : '/login'
        if (reference && !matches(me, reference)) { phase.value = 'LOGIN_RESULT_CONFLICT'; notice.value = '当前浏览器身份已变化。请核对实际账号，不能继续原操作。'; return }
        if (!acceptsIdentity(me.identityType)) { phase.value = 'ENTRY_MISMATCH'; notice.value = `此账号不适用于${portalName}，请使用适用入口或显式退出。`; return }
        saveExpected(expectedOf(me)); phase.value = 'AUTH_RECOVERY_PENDING'
        task.scope.checkpoint()
        const token = await api.csrf()
        if (!task.scope.current()) return
        if (token.contextKind !== 'AUTH' || token.loginAttemptId !== me.loginAttemptId || !Number.isFinite(Date.parse(token.expiresAt)) || Date.parse(token.expiresAt) <= Date.now()) { phase.value = 'LOGIN_RESULT_CONFLICT'; notice.value = '认证上下文已变化，请重新核验。'; return }
        csrf.value = token; identity.value = me
        restoreRevocation(me)
        phase.value = me.accessState; notice.value = preview ? '当前身份已核验。请明确选择下一步。' : ''; traceId.value = ''
        task.scope.checkpoint()
      } catch (e) {
        if (!task.scope.current()) return
        const err = error(e)
        if (err.status === 401) { clear('ANONYMOUS'); saveExpected(null); actual.value = null }
        else if (phase.value === 'AUTH_RECOVERY_PENDING') { csrf.value = null }
        else { clear('IDENTITY_UNAVAILABLE') }
      }
    })().finally(() => { if (current === task && task.scope.current()) current = undefined })
    return task.promise
  }
  function initialize() { initial ??= synchronize(); return initial }
  async function login(input: LoginInput) {
    if (busy.value || Date.now() < retryUntil.value) return
    if (peerUntil > Date.now()) { notice.value = '另一个标签页正在登录，请稍后重新核验。'; return }
    clear('LOGIN_PENDING'); actual.value = null; notice.value = ''; traceId.value = ''
    const owner = startBusy(), scope = captureRequestScope(source, () => busyOwner === owner)
    loginEntry.value = !customerPortal && input.realm === 'PLATFORM' ? '/platform/login' : '/login'
    channel?.postMessage({ kind: 'login-start', until: Date.now() + 30000 })
    try {
      const pre = await api.csrf()
      if (!scope.current()) return
      if (pre.contextKind !== 'PREAUTH') { phase.value = 'LOGIN_RESULT_CONFLICT'; notice.value = '当前浏览器已有会话，请核验后显式退出再登录。'; return }
      if (!Number.isFinite(Date.parse(pre.expiresAt)) || Date.parse(pre.expiresAt) <= Date.now()) throw new ApiError(401, 'PREAUTH_EXPIRED', '登录上下文已过期，请重新提交')
      const attemptId = crypto.randomUUID()
      saveExpected({ attemptId, realm: input.realm, account: input.account, tenantCode: input.tenantCode, expiresAt: pre.expiresAt })
      scope.checkpoint()
      await api.login(input, attemptId, pre.token)
      if (!scope.current()) return
      finishBusy(owner, scope); channel?.postMessage({ kind: 'login-end' })
      const confirmationGeneration = generation.value + 1
      await synchronize()
      if (generation.value === confirmationGeneration && ready.value && identity.value?.loginAttemptId === attemptId) broadcast()
    } catch (e) {
      if (!scope.current()) return
      const err = error(e)
      if (err.loginRecovery === 'LOGOUT_CURRENT') { saveExpected(null); clear('LOGIN_RESULT_CONFLICT') }
      else if (err.loginRecovery === 'VERIFY_ATTEMPT' || err.status === 0 || (err.status >= 500 && !err.loginRecovery) || err.code === 'LOGIN_IN_PROGRESS') { phase.value = 'LOGIN_RESULT_UNKNOWN'; notice.value = '登录结果尚未确认。请核验本次身份，不要重复提交密码。' }
      else { saveExpected(null); clear('ANONYMOUS') }
    } finally { if (scope.current()) { finishBusy(owner, scope); channel?.postMessage({ kind: 'login-end' }) } }
  }
  async function logout(useActual = false) {
    if (busy.value) return
    const origin = useActual ? actual.value : identity.value
    if (!origin) return
    const reference = expectedOf(origin)
    logoutExpected = reference; saveExpected(reference); clear('LOGOUT_PENDING')
    const owner = startBusy(), scope = captureRequestScope(source, () => busyOwner === owner && logoutExpected === reference)
    try {
      const me = await api.me()
      if (!scope.current()) return
      if (!matches(me, reference)) { actual.value = me; phase.value = 'LOGIN_RESULT_CONFLICT'; notice.value = '身份已变化，请确认是否退出当前实际会话。'; return }
      const token = await api.csrf()
      if (!scope.current()) return
      if (token.contextKind !== 'AUTH' || token.loginAttemptId !== origin.loginAttemptId) throw new ApiError(409, 'SESSION_CONFLICT', '身份已变化')
      await api.logout(token.token)
      if (!scope.current()) return
      clear('ANONYMOUS'); saveExpected(null); actual.value = null; notice.value = '当前会话已退出。'; broadcast()
    } catch (e) {
      if (!scope.current()) return
      const err = error(e)
      if (err.status === 401) { clear('ANONYMOUS'); saveExpected(null); actual.value = null }
      else { clear('LOGOUT_RESULT_UNKNOWN'); broadcast() }
    } finally { finishBusy(owner, scope) }
  }
  async function recoverLogout() {
    if (busy.value) return
    const reference = logoutExpected, owner = startBusy()
    const scope = captureRequestScope(source, () => busyOwner === owner && logoutExpected === reference)
    try {
      const me = await api.me(); if (!scope.current()) return; actual.value = me
      if (!reference || !matches(me, reference)) { clear('LOGIN_RESULT_CONFLICT'); notice.value = '发现另一会话，不会自动退出它。请确认当前账号。' }
      else { clear('LOGOUT_RESULT_UNKNOWN'); notice.value = '原会话仍有效，您可以显式重试退出。' }
    } catch (e) { if (!scope.current()) return; if (error(e).status === 401) { clear('ANONYMOUS'); saveExpected(null); actual.value = null; notice.value = '当前浏览器无有效会话。' } }
    finally { finishBusy(owner, scope) }
  }
  async function changePassword(body: { currentPassword: string; newPassword: string; confirmPassword: string }) {
    if (!ready.value || !csrf.value || busy.value) return false
    const owner = startBusy(), scope = captureRequestScope(source, () => busyOwner === owner)
    try { await api.password(body, csrf.value.token); if (!scope.current()) return false; clear('ANONYMOUS'); saveExpected(null); actual.value = null; notice.value = '密码已修改，旧会话失效。请使用新密码重新登录。'; broadcast(); return true }
    catch (e) {
      if (!scope.current()) return false
      const err = error(e)
      if (isUnavailableStatus(err.status)) { clear('PASSWORD_RESULT_UNKNOWN'); saveExpected(null); notice.value = '改密结果尚未确认。请显式重新登录核对，不自动重试旧密码或新密码。'; broadcast() }
      else if (err.status === 401) { clear('ANONYMOUS'); saveExpected(null) }
      else if (err.code === 'CSRF_INVALID' || err.status === 409) { clear('AUTH_RECOVERY_PENDING') }
      return false
    } finally { finishBusy(owner, scope) }
  }
  function invalidateBusiness(message: string) { clear('AUTH_RECOVERY_PENDING'); notice.value = message }
  function resetLogin() { clear('ANONYMOUS'); saveExpected(null); actual.value = null }
  function handleFailure(e: unknown, message?: string) { const err = error(e); if (message) notice.value = message; if (err.status === 401) { clear('ANONYMOUS'); saveExpected(null) } else if (isUnavailableStatus(err.status)) { clear('IDENTITY_UNAVAILABLE') } else if (err.code === 'CSRF_INVALID') { clear('AUTH_RECOVERY_PENDING') } return err }
  channel && (channel.onmessage = (event) => {
    if (event.data?.kind === 'login-start') { peerUntil = Math.min(Number(event.data.until) || 0, Date.now() + 30000); return }
    if (event.data?.kind === 'login-end') { peerUntil = 0; return }
    if (event.data?.kind === 'changed') { clear('AUTH_RECOVERY_PENDING'); void synchronize() }
  })
  window.addEventListener('focus', () => { if (!busy.value && !['ANONYMOUS', 'PASSWORD_RESULT_UNKNOWN', 'LOGOUT_RESULT_UNKNOWN'].includes(phase.value)) void synchronize() })
  window.addEventListener('beforeunload', (event) => { if (busy.value || revocationPending.value || ['LOGIN_RESULT_UNKNOWN','LOGOUT_RESULT_UNKNOWN'].includes(phase.value)) { event.preventDefault(); event.returnValue = '' } })
  setInterval(() => { if (ready.value && identity.value && Date.parse(identity.value.expiresAt) <= Date.now()) { clear('ANONYMOUS'); saveExpected(null); notice.value = '会话已到期，请显式重新登录。' } }, 1000)
  return { phase, identity, actual, csrf, notice, traceId, busy, retryUntil, generation, loginEntry, revocationPending, ready, canManage, initialize, synchronize, login, logout, recoverLogout, changePassword, resetLogin, handleFailure, invalidateBusiness, broadcast, rememberRevocation, completeRevocation }
})

