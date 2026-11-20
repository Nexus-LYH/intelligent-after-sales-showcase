import { request } from './api'
export type AssetKind = 'companies' | 'models' | 'devices'
export interface AssetRecord {
  id: string; code: string; name: string; status: string; rowVersion: string; updatedAt: string
  contactName?: string; phone?: string; address?: string; category?: string; description?: string
  serialNumber?: string; companyId?: string; companyName?: string; modelId?: string; modelName?: string
  location?: string; purchaseDate?: string | null; warrantyStart?: string | null; warrantyEnd?: string | null
  ownershipVersion?: string; disabledReason?: string
}
export interface AssetPage<T = AssetRecord> { items: T[]; total: number; page: number; size: number }
export interface Member { id: string; userId: string; state: string; rowVersion: string; companyId: string; createdAt: string }
export interface AssetOperation { operationId: string; state: string; resourceId?: string; userId?: string; companyId?: string; account?: string; displayName?: string; nextAction?: string; result?: AssetRecord }
export const endpoints: Record<AssetKind, string> = { companies: 'customer-companies', models: 'product-models', devices: 'devices' }
export const assetLabels: Record<AssetKind, string> = { companies: '客户企业', models: '产品型号', devices: '设备档案' }
const path = (kind: AssetKind) => `/asset/${endpoints[kind]}`
// Public wire contract: login/session draft section 14. All calls go through the gateway.
export const assets = {
  list: (kind: AssetKind, query: URLSearchParams) => request<AssetPage>(`${path(kind)}?${query}`),
  detail: (kind: AssetKind, id: string) => request<AssetRecord>(`${path(kind)}/${encodeURIComponent(id)}`),
  write: (kind: AssetKind, id: string | null, body: Record<string, unknown>, key: string, token: string) => request<AssetRecord>(`${path(kind)}${id ? `/${encodeURIComponent(id)}` : ''}`, { method: id ? 'PATCH' : 'POST', headers: { 'Idempotency-Key': key, 'X-CSRF-TOKEN': token }, body: JSON.stringify(body) }),
  status: (kind: AssetKind, id: string, body: Record<string, unknown>, key: string, token: string) => request<AssetRecord>(`${path(kind)}/${encodeURIComponent(id)}/status`, { method: 'POST', headers: { 'Idempotency-Key': key, 'X-CSRF-TOKEN': token }, body: JSON.stringify(body) }),
  members: (id: string, page = 1) => request<AssetPage<Member>>(`${path('companies')}/${encodeURIComponent(id)}/members?page=${page}&size=20`),
  enroll: (companyId: string, body: { account: string; displayName: string; initialPassword: string }, key: string, token: string) => request<AssetOperation>(`${path('companies')}/${encodeURIComponent(companyId)}/members`, { method: 'POST', headers: { 'Idempotency-Key': key, 'X-CSRF-TOKEN': token }, body: JSON.stringify(body) }),
  operation: (key: string, enrollment = false) => request<AssetOperation>(enrollment ? `/asset/customer-enrollments/${encodeURIComponent(key)}` : `/asset/operations/${encodeURIComponent(key)}`),
  resumeEnrollment: (id: string, token: string) => request<AssetOperation>(`/asset/customer-enrollments/${encodeURIComponent(id)}/resume`, { method: 'POST', headers: { 'X-CSRF-TOKEN': token }, body: '{}' }),
  myCompanies: () => request<{ items: AssetRecord[] }>('/asset/me/customer-companies'),
}
export const activeStatus = (value: string) => value === 'ENABLED' ? '启用' : '停用'
