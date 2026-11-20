import { onBeforeUnmount, ref } from 'vue'
import { useSession } from './session'
import { ApiError, isUnavailableStatus } from './api'
import { captureRequestScope } from './request-scope'
import type { CreationOperation, CreationResult, RepairInput, TicketFile } from './ticket-api'
export interface UploadOrigin { readonly actor: string; readonly uploadId: string; readonly submissionId: string; readonly file: File; readonly sha256: string; readonly deviceId: string; readonly expectedOwnershipVersion: string }
export interface UploadWork { key: string; file: File; sha256: string; state: string; result?: TicketFile; original?: UploadOrigin }
export interface RepairDraft { key: string; actor: string; body: RepairInput; frozen: boolean; sent?: RepairInput; files: UploadWork[]; result?: CreationResult | CreationOperation; unknown: boolean }
export function matchesUploadOrigin(d: RepairDraft, f: UploadWork) {
  const o = f.original
  return !!o && d.actor === o.actor && f.key === o.uploadId && d.key === o.submissionId && f.file === o.file && f.sha256 === o.sha256 && d.body.deviceId === o.deviceId && d.body.expectedOwnershipVersion === o.expectedOwnershipVersion
}
// Memory only: contact details, original payloads and File objects never enter Web Storage.
const drafts = new Map<string, RepairDraft>()
export function actorKey(auth: ReturnType<typeof useSession>) { const me = auth.identity; return me ? JSON.stringify([me.realm, me.tenant?.id, me.userId]) : '' }
export function customerWriter(auth: ReturnType<typeof useSession>) {
  return auth.phase === 'NORMAL' && auth.identity?.realm === 'TENANT' && auth.identity.identityType === 'CUSTOMER' && auth.csrf?.contextKind === 'AUTH' && auth.csrf.loginAttemptId === auth.identity.loginAttemptId
}
export function ownDraft(key: string, auth: ReturnType<typeof useSession>) { const d = drafts.get(key); return d?.actor === actorKey(auth) ? d : undefined }
export function saveDraft(draft: RepairDraft) { drafts.set(draft.key, draft) }
export function unresolvedDraft(deviceId: string, auth: ReturnType<typeof useSession>) {
  return Array.from(drafts.values()).find(d => d.actor === actorKey(auth) && d.body.deviceId === deviceId && d.frozen && (d.unknown || d.result?.state === 'ABORTED' && d.files.length > 0))
}
export function useTicketWork(route: () => string) {
  const auth = useSession(), busy = ref(false), message = ref(''), trace = ref('')
  let alive = true, slot = 0
  onBeforeUnmount(() => { alive = false; ++slot })
  function begin(operation: () => boolean = () => true) {
    const id = ++slot, path = route()
    const scope = captureRequestScope(auth, () => alive && slot === id && route() === path && operation())
    busy.value = true; message.value = ''; trace.value = ''
    return { current: scope.current, finish: () => { if (scope.current()) busy.value = false } }
  }
  function fail(e: unknown, scope: ReturnType<typeof begin>) {
    if (!scope.current()) return
    scope.finish()
    const error = e instanceof ApiError ? e : new ApiError(0, 'UNKNOWN', '结果无法确认，请显式查询')
    message.value = `${error.message}（${error.code}）`; trace.value = error.traceId
    if (error.status === 403 && error.code !== 'CSRF_INVALID') { auth.invalidateBusiness('当前业务访问被拒绝，已隐藏正文。请显式重新核验身份与权限。') }
    else if (error.status === 401 || isUnavailableStatus(error.status) || error.code === 'CSRF_INVALID') auth.handleFailure(error)
  }
  return { auth, busy, message, trace, begin, fail }
}
