import type {ReopenRequest} from './ticket-reopen-api'
import type { TransferRequest, TransferCoordination } from './ticket-transfer-api'
import type { CancellationRequest, CancellationFact } from './ticket-cancellation-api'
import { ApiError, request } from './api'
import type { AssignmentInfo, DepartmentOption, ProcessingRecord } from './ticket-action-api'
import type { SolutionAttempt, TicketTiming, TicketClosure } from './ticket-solution-api'
import type { SupplementRequest } from './ticket-supplement-api'
export interface RepairInput { deviceId: string; expectedOwnershipVersion: string; title: string; description: string; categoryCode: 'UNCERTAIN'; impact: 'UNUSABLE' | 'AFFECTED' | 'CONSULTATION'; contactName: string; contactPhone: string; attachmentIds: string[]; duplicateAcknowledged: boolean }
export interface TicketSummary { id: string; ticketNo: string; title: string; status: string; rowVersion: string; submittedAt: string; priority?: string; departmentId?: string; deviceSnapshot: { id: string; code: string; name: string; serialNumber: string; modelName: string; ownershipVersion: string }; companySnapshot: { id: string; code: string; name: string } }
export interface TicketTimelineEvent extends Partial<ProcessingRecord> { type:string; at?:string }
export interface TicketDetail extends TicketSummary { roundStartedAt?:string; ruleVersion?:string; currentRoundId?:string; isHistorical?:boolean; roundNo?:string|number; readOnly?:boolean; currentReopen?:ReopenRequest|null; reopenReviewDepartments?:DepartmentOption[]; reopenAssignableDepartments?:DepartmentOption[]; currentTransfer?: TransferRequest | null; transferCoordination?: TransferCoordination; transferDepartments?: DepartmentOption[]; transferHistory?: TransferRequest[]; currentEngineerUserId?: string | null; currentDepartmentId?: string | null; currentCancellation?: CancellationRequest | null; cancellation?: CancellationFact | null; description: string; categoryCode: string; impact: string; contactName: string; contactPhone: string; roundId?: string; currentSupplement?: SupplementRequest | null; currentSolution?: SolutionAttempt | null; timing?: TicketTiming; closure?: TicketClosure | null; recordedWorkHours?: string; allowedActions?: string[]; assignment?: AssignmentInfo | null; qualificationStatus?: 'VALID' | 'INVALID' | 'NONE'; assignableDepartments?: DepartmentOption[]; initialPriority?: string; firstResponseAt?: string | null; firstResponsePriority?: string; sla?: { responseDueAt: string; resolutionDueAt: string; policyVersion: string }; coordinationRequired?: boolean; rejectionReason?: string; timeline: TicketTimelineEvent[]; attachments: TicketFile[] }
export interface TicketFile { fileId: string; uploadId?: string; submissionId?: string; name: string; mediaType: string; size: number; sha256: string; state: string; nextAction?: string }
export interface CreationResult { operationId: string; ticketId?: string; ticketNo?: string; status?: string; state?: string; reason?: string; nextAction: string }
export interface CreationOperation extends CreationResult { state: 'REGISTERED' | 'RESERVED' | 'COMMITTED' | 'COMPLETED' | 'ABORTED'; ticket?: CreationResult | null }
export function verifyCreation<T extends CreationResult>(result: T, key: string): T {
  if (result.operationId !== key) throw new ApiError(503, 'OPERATION_RESPONSE_MISMATCH', '回执不属于原提交，请继续查询原UUID')
  return result
}
const root = '/ticket', enc = encodeURIComponent
const write = (token: string, key?: string) => ({ 'X-CSRF-TOKEN': token, ...(key ? { 'Idempotency-Key': key } : {}) })
export const tickets = {
  create: (key: string, body: RepairInput, token: string) => request<CreationResult>(`${root}/tickets`, { method: 'POST', headers: write(token, key), body: JSON.stringify(body) }),
  operation: (key: string) => request<CreationOperation>(`${root}/create-operations/${enc(key)}`),
  resume: (key: string, token: string) => request<CreationResult>(`${root}/create-operations/${enc(key)}/resume`, { method: 'POST', headers: write(token), body: '{}' }),
  list: (query: URLSearchParams) => request<{ items: TicketSummary[]; total: number; page: number; size: number }>(`${root}/tickets?${query}`),
  detail: (id: string) => request<TicketDetail>(`${root}/tickets/${enc(id)}`),
  duplicates: (id: string) => request<{ items: TicketSummary[] }>(`${root}/duplicate-candidates?deviceId=${enc(id)}`),
  uploadState: (id: string) => request<TicketFile>(`${root}/file-uploads/${enc(id)}`),
  remove: (id: string, submission: string, token: string) => request<{ fileId: string; state: string }>(`${root}/files/${enc(id)}?submissionId=${enc(submission)}`, { method: 'DELETE', headers: write(token) }),
  authorize: (id: string, token: string) => request<{ authorizationId: string; expiresAt: string; url: string }>(`${root}/files/${enc(id)}/download-authorizations`, { method: 'POST', headers: write(token), body: '{}' }),
}
// File bodies use the dedicated 120s transfer budget, never the JSON wrapper/header.
export async function transfer<T>(url: string, init: RequestInit, binary = false): Promise<T> {
  const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 120000)
  try {
    const r = await fetch(url, { ...init, credentials: 'same-origin', cache: 'no-store', signal: controller.signal })
    if (!r.ok) { const b = await r.json().catch(() => null); throw new ApiError(r.status, b?.code || 'TRANSFER_FAILED', b?.message || '文件传输失败，需显式核对', b?.traceId || '') }
    if (binary) {
      const mediaType = (r.headers.get('Content-Type') || '').split(';')[0]!.trim()
      if (!['image/jpeg', 'image/png', 'application/pdf'].includes(mediaType) || !r.body) throw new ApiError(503, 'INVALID_FILE_RESPONSE', '文件响应无法确认')
      const reader = r.body.getReader(), chunks: BlobPart[] = []; let size = 0
      try { while (true) { const { done, value } = await reader.read(); if (done) break; size += value.byteLength; if (size > 20971520) { await reader.cancel(); throw new ApiError(503, 'INVALID_FILE_RESPONSE', '下载内容超过20MiB限制') }; chunks.push(value.slice().buffer) } } finally { reader.releaseLock() }
      return new Blob(chunks, { type: mediaType }) as T
    }
    return await r.json() as T
  } catch (e) { if (e instanceof ApiError) throw e; throw new ApiError(0, 'TRANSFER_RESULT_UNKNOWN', '传输中断或超时，请查询原上传结果') } finally { clearTimeout(timer) }
}
export function uploadFile(key: string, submission: string, body: Pick<RepairInput, 'deviceId' | 'expectedOwnershipVersion'>, file: File, sha256: string, token: string) {
  const data = new FormData(); data.set('submissionId', submission); data.set('deviceId', body.deviceId); data.set('expectedOwnershipVersion', body.expectedOwnershipVersion); data.set('sha256', sha256); data.set('file', file)
  return transfer<TicketFile>('/api/ticket/files', { method: 'POST', headers: write(token, key), body: data })
}
export function verifyUpload(result: TicketFile, key: string, submission: string, file: File, sha256: string) {
  if (result.state === 'READY' && (result.uploadId !== key || result.submissionId !== submission || result.size !== file.size || result.sha256 !== sha256 || !result.fileId)) throw new ApiError(503, 'UPLOAD_RESPONSE_MISMATCH', '上传回执与原文件或原操作不匹配，请查询原上传')
  return result
}
export function repairValidation(body: RepairInput) {
  const count = (s: string) => Array.from(s).length
  if (count(body.title) < 5 || count(body.title) > 100) return '标题须为5～100个字符'
  if (count(body.description) < 20 || count(body.description) > 5000) return '故障描述须为20～5000个字符'
  if (count(body.contactName) < 1 || count(body.contactName) > 50) return '联系人须为1～50个字符'
  if (count(body.contactPhone) < 1 || count(body.contactPhone) > 30 || !/^[0-9 +()xX分机-]+$/.test(body.contactPhone) || !/[0-9]/.test(body.contactPhone)) return '联系电话须为1～30字符，包含数字，可用空格、+、-、括号、x或分机'
  for (const [key, value] of Object.entries(body)) if (typeof value === 'string' && (/[\uD800-\uDFFF]/u.test(value) || (key === 'description' ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/ : /[\u0000-\u001f\u007f-\u009f]/).test(value))) return '文本包含不允许的字符'
  if (!['UNUSABLE', 'AFFECTED', 'CONSULTATION'].includes(body.impact)) return '请选择影响程度'
  if (body.attachmentIds.length > 5 || new Set(body.attachmentIds).size !== body.attachmentIds.length) return '最多5个不同附件'
  return ''
}


