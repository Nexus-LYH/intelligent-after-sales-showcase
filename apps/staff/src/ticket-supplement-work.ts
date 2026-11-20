import { shallowReactive } from 'vue'
import type { TicketDetail, TicketFile } from './ticket-api'
import { ApiError, request } from './api'
import { transfer } from './ticket-api'
export interface SupplementFile extends Omit<TicketFile, 'size' | 'mediaType'> { size: number | null; mediaType: string | null; ticketId: string; roundId: string; requestId: string; replyOperationId: string; uploadId: string }
export interface ReplyFile {
  readonly uploadId: string; readonly file: File; readonly sha256: string
  state: 'UNKNOWN' | 'READY' | 'RETRY' | 'REMOVED'; result?: SupplementFile; retryKind?: 'NOT_OBSERVED' | 'FAILED'
}
export interface ReplyDraft {
  readonly actor: string; readonly ticketId: string; readonly roundId: string; readonly requestId: string
  readonly assignmentId: string | null; readonly resumeState: string; readonly key: string
  body: string; frozen: boolean; files: ReplyFile[]
}
// Private content and File objects survive a component remount in memory only.
const drafts = new Map<string, ReplyDraft>()
const retained = new Map<string, ReplyDraft>()
export const replyDraftByKey = (key: string) => retained.get(key)
export const retainedReplyDrafts = (actor: string, ticketId: string) => Array.from(retained.values()).filter(d => d.actor === actor && d.ticketId === ticketId && d.files.length > 0)
export function retireReplyDraft(key: string) { for (const [slot,d] of drafts) if (d.key === key) drafts.delete(slot) }
export function replyDraft(actor: string, t: TicketDetail) {
  const r = t.currentSupplement; if (!r || r.state !== 'OPEN' || t.status !== 'WAITING_INFORMATION' || !t.roundId) return null
  const slot = JSON.stringify([actor,t.id,t.roundId,r.requestId]), old = drafts.get(slot)
  if (old) return old
  const d = shallowReactive<ReplyDraft>({actor,ticketId:t.id,roundId:t.roundId,requestId:r.requestId,assignmentId:r.assignmentId,resumeState:r.resumeState,key:crypto.randomUUID(),body:'',frozen:false,files:shallowReactive([])})
  drafts.set(slot,d); retained.set(d.key,d); return d
}
export const replyMatches = (d: ReplyDraft, actor: string, t: TicketDetail) => d.actor === actor && d.ticketId === t.id && d.roundId === t.roundId && d.requestId === t.currentSupplement?.requestId && t.currentSupplement.state === 'OPEN' && d.assignmentId === t.currentSupplement.assignmentId && d.resumeState === t.currentSupplement.resumeState && t.status === 'WAITING_INFORMATION'
const path = (d: ReplyDraft) => `/ticket/tickets/${encodeURIComponent(d.ticketId)}/supplement-requests/${encodeURIComponent(d.requestId)}`
const headers = (token: string) => ({'X-CSRF-TOKEN':token})
export const replyFiles = {
  query: (d: ReplyDraft, f: ReplyFile) => request<SupplementFile>(`${path(d)}/file-uploads/${encodeURIComponent(f.uploadId)}`),
  remove: (d: ReplyDraft, f: ReplyFile, token: string) => request<{fileId: string; state: string}>(`${path(d)}/files/${encodeURIComponent(f.result!.fileId)}?replyOperationId=${encodeURIComponent(d.key)}`, {method:'DELETE',headers:headers(token)}),
  upload: (d: ReplyDraft, f: ReplyFile, token: string) => {
    const data = new FormData(); data.set('replyOperationId',d.key); data.set('sha256',f.sha256); data.set('file',f.file)
    return transfer<SupplementFile>(`/api${path(d)}/files`,{method:'POST',headers:{...headers(token),'Idempotency-Key':f.uploadId},body:data})
  },
}
export function verifyReplyFile(d: ReplyDraft, f: ReplyFile, r: SupplementFile) {
  if (!r || r.uploadId !== f.uploadId || r.ticketId !== d.ticketId || r.roundId !== d.roundId || r.requestId !== d.requestId || r.replyOperationId !== d.key || r.sha256 !== f.sha256 || r.name !== f.file.name || !r.fileId || !['READY','UPLOADING','FAILED','DELETING','DELETED'].includes(r.state) || r.size !== null && r.size !== 0 && r.size !== f.file.size || r.state === 'READY' && (r.size !== f.file.size || !['image/png','image/jpeg','application/pdf'].includes(r.mediaType || ''))) throw new ApiError(503,'SUPPLEMENT_FILE_MISMATCH','回执与原上传/请求/回复/文件不匹配，请保留原件并查询')
  return r
}
export const replyFileReady = (d: ReplyDraft) => d.files.every(f => f.state === 'REMOVED' || f.state === 'READY' && !!f.result && f.result.state === 'READY')
