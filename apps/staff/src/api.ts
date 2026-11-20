export type Realm = 'TENANT' | 'PLATFORM'
export interface Identity { userId: string; account: string; displayName: string; realm: Realm; identityType: string; tenant: { id: string; code: string; name: string } | null; department: { name: string } | null; permissions: string[]; accessState: 'NORMAL' | 'PASSWORD_CHANGE_REQUIRED'; loginAttemptId: string; expiresAt: string }
export interface Csrf { token: string; headerName: string; contextKind: 'PREAUTH' | 'AUTH'; loginAttemptId?: string; expiresAt: string }
export interface LoginInput { realm: Realm; tenantCode?: string; account: string; password: string }
export interface Expected { attemptId: string; realm: Realm; account: string; tenantCode?: string; expiresAt: string }
export interface User { id: string; account: string; displayName: string; identityType: string; status: string | number; mustChangePassword: boolean | number; bindingState: string | number; securityVersion: string; updatedAt: string; tenant?: Identity['tenant'] }
export interface UserPage { items: User[]; total: number; page: number; size: number }
export interface EmployeeAccess { userId: string; securityVersion: string; departmentId: string | null; capabilities: string[]; companyIds: string[]; assignmentDepartmentIds: string[]; approvalDepartmentIds: string[]; reopenDepartmentIds: string[]; warehouseIds: string[] }
export interface EmployeeStatusReceipt { operationId: string; userId: string; status: number; securityVersion: string }
export interface EmployeeStatusCommand { operationId: string; expectedSecurityVersion: string; status: number; reason: string }
export interface EmployeeCreateCommand { operationId: string; account: string; displayName: string; initialPassword: string }
export interface EmployeeCreateReceipt { operationId: string; userId: string; account: string; displayName: string; mustChangePassword: boolean }
export interface EmployeeScopeCommand { operationId:string; expectedSecurityVersion:string; departmentId:string|null; capabilities:string[]; companyIds:string[]; assignmentDepartmentIds:string[]; approvalDepartmentIds:string[]; reopenDepartmentIds:string[]; warehouseIds:string[]; reason:string }
export interface EmployeeScopeReceipt { operationId:string; userId:string; securityVersion:string }
export interface Revocation { operationId: string; targetUserId: string; newSecurityVersion: string; revokedAt: string; scope: string }
export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string, public traceId = '', public loginRecovery?: string, public retryAfter = 0) { super(message) }
}
// Browser owns HttpOnly cookies, including the first me confirmation replacement.
// No credential/CSRF is logged, persisted, or put in a URL. Never automatically retry writes.
export const isUnavailableStatus = (status: number) => status === 0 || status >= 500
export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 15000)
  try {
    const response = await fetch(`/api${path}`, { ...options, credentials: 'same-origin', cache: 'no-store', signal: controller.signal, headers: { ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...options.headers } })
    if (response.status === 204) return undefined as T
    const body = await response.json().catch(() => null)
    if (!response.ok) {
      const retry = response.headers.get('Retry-After')
      const seconds = retry ? (/^\d+$/.test(retry) ? Number(retry) : Math.max(0, (Date.parse(retry) - Date.now()) / 1000)) : 0
      throw new ApiError(response.status, body?.code ?? 'HTTP_ERROR', body?.message ?? '请求失败，请稍后核对', body?.traceId ?? '', body?.loginRecovery, Number.isFinite(seconds) ? seconds : 0)
    }
    if (!body) throw new ApiError(503, 'INVALID_RESPONSE', '响应无法确认，请重新核对')
    return body as T
  } catch (e) {
    if (e instanceof ApiError) throw e
    throw new ApiError(0, 'NETWORK_RESULT_UNKNOWN', '连接中断或超时，提交结果尚未确认')
  } finally { clearTimeout(timer) }
}
export const api = {
  me: () => request<Identity>('/auth/me'),
  csrf: () => request<Csrf>('/auth/csrf'),
  login: (input: LoginInput, attemptId: string, token: string) => request<Identity>('/auth/login', { method: 'POST', headers: { 'X-Login-Attempt-Id': attemptId, 'X-CSRF-TOKEN': token }, body: JSON.stringify(input) }),
  logout: (token: string) => request<void>('/auth/logout', { method: 'POST', headers: { 'X-CSRF-TOKEN': token, 'Content-Type': 'application/json' } }),
  password: (body: { currentPassword: string; newPassword: string; confirmPassword: string }, token: string) => request<{ nextAction: string }>('/auth/password', { method: 'POST', headers: { 'X-CSRF-TOKEN': token }, body: JSON.stringify(body) }),
  users: (query: URLSearchParams) => request<UserPage>(`/identity/users?${query}`),
  user: (id: string) => request<User>(`/identity/users/${encodeURIComponent(id)}`),
  employeeAccess: (id: string) => request<EmployeeAccess>(`/identity/users/${encodeURIComponent(id)}/access`),
  employeeStatus: (id: string, body: EmployeeStatusCommand, token: string) => request<EmployeeStatusReceipt>(`/identity/users/${encodeURIComponent(id)}/status`, { method: 'POST', headers: { 'X-CSRF-TOKEN': token, 'Idempotency-Key': body.operationId }, body: JSON.stringify(body) }),
  employeeStatusOperation: (id: string, operation: string) => request<EmployeeStatusReceipt>(`/identity/users/${encodeURIComponent(id)}/status-operations/${encodeURIComponent(operation)}`),
  createEmployee: (body: EmployeeCreateCommand, token: string) => request<EmployeeCreateReceipt>('/identity/employees', { method: 'POST', headers: { 'X-CSRF-TOKEN': token, 'Idempotency-Key': body.operationId }, body: JSON.stringify(body) }),
  employeeCreateOperation: (operation: string) => request<EmployeeCreateReceipt>(`/identity/employee-create-operations/${encodeURIComponent(operation)}`),
  employeeScope: (id:string,body:EmployeeScopeCommand,token:string) => request<EmployeeScopeReceipt>(`/identity/users/${encodeURIComponent(id)}/scope`,{method:'POST',headers:{'X-CSRF-TOKEN':token,'Idempotency-Key':body.operationId},body:JSON.stringify(body)}),
  employeeScopeOperation: (id:string,operation:string) => request<EmployeeScopeReceipt>(`/identity/users/${encodeURIComponent(id)}/scope-operations/${encodeURIComponent(operation)}`),
  revoke: (id: string, reason: string, version: string, key: string, token: string) => request<Revocation>(`/identity/users/${encodeURIComponent(id)}/sessions/revoke`, { method: 'POST', headers: { 'X-CSRF-TOKEN': token, 'Idempotency-Key': key }, body: JSON.stringify({ reason, expectedSecurityVersion: version }) }),
}
export function matches(me: Identity, expected: Expected) { return me.loginAttemptId === expected.attemptId && me.realm === expected.realm && me.account === expected.account && (me.realm === 'PLATFORM' ? me.tenant === null : me.tenant?.code === expected.tenantCode) }
export function expectedOf(me: Identity): Expected { return { attemptId: me.loginAttemptId, realm: me.realm, account: me.account, tenantCode: me.tenant?.code, expiresAt: me.expiresAt } }
export function loginValidation(input: LoginInput): string {
  if (input.realm === 'TENANT' && !/^[A-Za-z0-9_-]{2,32}$/.test(input.tenantCode ?? '')) return '租户编码须为2～32位字母、数字、下划线或短横线，不允许空白'
  if (!/^[A-Za-z0-9._-]{1,100}$/.test(input.account)) return '账号须为1～100位字母、数字、点、下划线或短横线，不允许空白'
  if (Array.from(input.password).length < 1 || Array.from(input.password).length > 128) return '登录密码须为1～128个字符'
  return ''
}
export function passwordValidation(current: string, next: string, confirm: string) {
  if (!current || Array.from(current).length > 128) return '当前密码须为1～128个字符'
  if (Array.from(next).length < 12 || Array.from(next).length > 128) return '新密码须为12～128个字符'
  if (next === current) return '新密码必须不同于当前密码'
  if (next !== confirm) return '两次新密码不一致'
  return ''
}
