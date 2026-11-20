import type { Identity } from './api'
export const ticketStatuses: Record<string, string> = { CANCELLED: '已取消（非已解决）', WAITING_INFORMATION: '待补充资料', PENDING_ACCEPTANCE: '待受理', WAITING_ASSIGNMENT: '待分派', ASSIGNED: '待工程师接单', IN_PROGRESS: '处理中', REJECTED: '已驳回', PENDING_CUSTOMER_CONFIRMATION: '待客户确认', RESOLVED: '已解决，待客服关闭', CLOSED: '已关闭' }
export const categories: Record<string, string> = { UNCERTAIN: '不确定，待受理确认', HARDWARE: '硬件', SOFTWARE: '软件', NETWORK: '网络', OTHER: '其他' }
export const priorities: Record<string, string> = { P1: 'P1', P2: 'P2', P3: 'P3' }
export const initialPriority: Record<string, string> = { UNUSABLE: 'P1', AFFECTED: 'P2', CONSULTATION: 'P3' }
export const canOpenTickets = (me: Identity | null) => me?.realm === 'TENANT' && me.identityType === 'EMPLOYEE' && me.permissions.some(p => p === 'ticket.read' || p === 'ticket.engineer.read')
export const statusLabel = (status: string) => ticketStatuses[status] || '状态待核对'
export const validText = (s: string, min: number, max: number, multiline = false) => {
  const n = Array.from(s).length
  return n >= min && n <= max && !/[\uD800-\uDFFF]/u.test(s) && !(multiline ? /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/ : /[\u0000-\u001f\u007f-\u009f]/).test(s)
}
export function workHoursValid(value: string) { return /^(?:0|[1-9]\d?)(?:\.\d{1,2})?$/.test(value) && Number(value) <= 24 }

