import { id, uuid, warehousePath, type StockTransport } from './stock-client'

export interface StockBalance {
  partId: string
  onHand: string
  reserved: string
  available: string
  rowVersion: string
}

export interface StockMovement {
  source: 'INBOUND' | 'EXECUTION'
  operationId: string
  orderId: string | null
  partId: string
  actorUserId: string
  action: 'INBOUND' | 'RESERVE' | 'ISSUE' | 'USE' | 'RETURN' | 'EXPIRE'
  onHandDelta: string
  reservedDelta: string
  usedDelta: string
  recordedAt: string
}

export interface LedgerPage<T> { items: T[]; total: number; page: number; size: number }
const decimal = (value: unknown): value is string => typeof value === 'string'
  && /^(0|[1-9][0-9]{0,18})$/.test(value) && BigInt(value) <= 9223372036854775807n
const signed = (value: unknown): value is string => typeof value === 'string'
  && /^(0|-?[1-9][0-9]{0,18})$/.test(value)
  && BigInt(value) >= -9223372036854775808n && BigInt(value) <= 9223372036854775807n

function pageOf<T>(value: LedgerPage<T>, page: number, valid: (item: T) => boolean): LedgerPage<T> {
  if (!value || value.page !== page || value.size !== 20 || !Number.isSafeInteger(value.total)
    || value.total < 0 || !Array.isArray(value.items) || value.items.length > 20
    || value.items.some(item => !item || !valid(item))) throw Error('库存查询投影无法确认')
  return value
}

export function createStockLedger(wire: StockTransport, current: () => boolean) {
  function query(warehouse: string, part: string, page: number) {
    if (!id(warehouse) || part && !id(part) || !Number.isInteger(page) || page < 1 || page > 100000) {
      throw Error('请选择授权仓库及规范备件编号和页码')
    }
    return new URLSearchParams({ ...(part ? { partId: part } : {}), page: String(page), size: '20' })
  }
  return {
    async balances(warehouse: string, part: string, page: number) {
      const parameters = query(warehouse, part, page)
      const result = pageOf(await wire.send<LedgerPage<StockBalance>>('GET',
        warehousePath(warehouse) + '/balances?' + parameters, undefined, current), page,
      row => id(row.partId) && (!part || row.partId === part)
        && [row.onHand, row.reserved, row.available, row.rowVersion].every(decimal)
        && BigInt(row.onHand) - BigInt(row.reserved) === BigInt(row.available))
      if (new Set(result.items.map(row => row.partId)).size !== result.items.length) throw Error('余额备件重复')
      return result
    },
    async movements(warehouse: string, part: string, order: string, page: number) {
      const parameters = query(warehouse, part, page)
      if (order && !uuid(order)) throw Error('原执行单须为完整UUID')
      if (order) parameters.set('orderId', order)
      const result = pageOf(await wire.send<LedgerPage<StockMovement>>('GET',
        warehousePath(warehouse) + '/movements?' + parameters, undefined, current), page,
      row => uuid(row.operationId) && id(row.partId) && (!part || row.partId === part)
        && (!order || row.orderId === order) && decimal(row.actorUserId)
        && (row.source === 'INBOUND' ? row.orderId === null && row.action === 'INBOUND'
          : row.source === 'EXECUTION' && uuid(row.orderId) && ['RESERVE', 'ISSUE', 'USE', 'RETURN', 'EXPIRE'].includes(row.action))
        && [row.onHandDelta, row.reservedDelta, row.usedDelta].every(signed)
        && typeof row.recordedAt === 'string' && /^\d{4}-\d\d-\d\dT.*Z$/.test(row.recordedAt)
        && Number.isFinite(Date.parse(row.recordedAt)))
      const keys = result.items.map(row => row.source + row.operationId + row.partId)
      if (new Set(keys).size !== keys.length) throw Error('库存流水重复')
      return result
    },
  }
}
