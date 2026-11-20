import { ref, reactive, computed, onUnmounted } from 'vue'
import { useSession } from './session'
import { ApiError, isUnavailableStatus } from './api'
import { captureRequestScope } from './request-scope'
export interface PendingAsset { key: string; actor: string; namespace: string; kind: string; target: string; enrollment: boolean; operationId?: string; body?: Record<string, unknown>; statusAction?: boolean }
// Non-secret in-memory references only. Passwords never enter this registry; ordinary asset payloads are non-secret.
const pending = ref<PendingAsset[]>([])
const activeOwners = reactive(new Map<string, symbol>())
export function useAssetWork(routeKey: () => string) {
  const auth = useSession(), loading = ref(false), message = ref(''), trace = ref('')
  let alive = true, slot = 0
  const localOwners = new Map<string, symbol>()
  onUnmounted(() => { alive = false; slot++; for (const [key, owner] of localOwners) if (activeOwners.get(key) === owner) activeOwners.delete(key) })
  const actor = () => auth.identity?.userId ?? ''
  const namespace = () => `${auth.identity?.realm}:${auth.identity?.tenant?.id ?? ''}`
  const owned = computed(() => pending.value.filter(p => p.actor === actor() && p.namespace === namespace()))
  const canWrite = computed(() => auth.phase === 'NORMAL' && auth.identity?.identityType === 'EMPLOYEE' && !!auth.identity.permissions.includes('asset.manage'))
  const canEnroll = computed(() => canWrite.value && !!auth.identity?.permissions.includes('identity.customer.provision'))
  function same(left: PendingAsset, right: PendingAsset) { return left.key === right.key && left.actor === right.actor && left.namespace === right.namespace && left.kind === right.kind && left.target === right.target && left.enrollment === right.enrollment && left.statusAction === right.statusAction && JSON.stringify(left.body) === JSON.stringify(right.body) }
  function owns(value: PendingAsset) { return owned.value.some(p => same(p, value)) }
  function isActive(value: PendingAsset) { return activeOwners.has(value.key) }
  function begin(value?: PendingAsset) {
    const id = ++slot, route = routeKey()
    const owner = Symbol('asset-request')
    if (value) { activeOwners.set(value.key, owner); localOwners.set(value.key, owner) }
    const scope = captureRequestScope(auth, () => alive && slot === id && routeKey() === route && (!value || (owns(value) && activeOwners.get(value.key) === owner)))
    loading.value = true; message.value = ''; trace.value = ''
    return { ...scope, finish: () => { if (scope.current()) { loading.value = false; if (value && activeOwners.get(value.key) === owner) { activeOwners.delete(value.key); localOwners.delete(value.key) } } } }
  }
  function finish(scope: ReturnType<typeof begin>) { scope.finish() }
  function fail(e: unknown, scope: ReturnType<typeof begin>) {
    if (!scope.current()) return
    const error = e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', '结果未确认')
    finish(scope); message.value = `${error.message}（${error.code}）`; trace.value = error.traceId
    if ((error.status === 401 || isUnavailableStatus(error.status)) || error.code === 'CSRF_INVALID') auth.handleFailure(error)
  }
  function remember(kind: string, target: string, enrollment = false, body?: Record<string, unknown>, statusAction = false, key: string = crypto.randomUUID()) {
    const existing = owned.value.find(p => p.key === key)
    if (existing) return existing
    const draft = body ? (enrollment ? { account: body.account, displayName: body.displayName } : { ...body }) : undefined
    const value: PendingAsset = { key, actor: actor(), namespace: namespace(), kind, target, enrollment, body: draft, statusAction }
    pending.value.push(value); return value
  }
  function complete(value: PendingAsset) { const index = pending.value.findIndex(p => same(p, value)); if (index >= 0) pending.value.splice(index, 1) }
  function attach(value: PendingAsset, operationId: string) { const found = pending.value.find(p => same(p, value)); if (found) found.operationId = operationId }
  return { auth, loading, message, trace, owned, canWrite, canEnroll, begin, finish, fail, remember, complete, attach, owns, isActive }
}
