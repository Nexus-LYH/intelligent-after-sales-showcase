import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createSSRApp } from 'vue'
import { renderToString } from 'vue/server-renderer'
import TicketRecordAudit from '../src/components/TicketRecordAudit.vue'
import type { ProcessingRecord } from '../src/ticket-action-api'

const portal = vi.hoisted(() => ({ customer: false }))
vi.mock('../src/portal', () => ({ get customerPortal() { return portal.customer } }))

const record: ProcessingRecord = {
  recordId: '10000000-0000-4000-8000-000000000001',
  operationId: '20000000-0000-4000-8000-000000000001',
  roundId: '30000000-0000-4000-8000-000000000001',
  assignmentId: '40000000-0000-4000-8000-000000000001',
  actorUserId: '9007199254740993',
  rowVersion: '9007199254740994',
  type: 'RECORDS',
  body: '故障已排除',
  visibility: 'CUSTOMER_VISIBLE',
  occurredAt: '2026-11-14T02:03:04Z',
}

function render(value: Partial<ProcessingRecord>) {
  return renderToString(createSSRApp(TicketRecordAudit, { record: value }))
}

beforeEach(() => { portal.customer = false })

describe('current object record audit display', () => {
  it('shows stored original references with exact BIGINT strings', async () => {
    const html = await render(record)
    for (const value of [record.recordId, record.operationId, record.roundId, record.assignmentId, record.actorUserId, record.rowVersion]) {
      expect(html).toContain(value)
    }
    expect(html).toContain('原操作UUID')
    expect(html).toContain('记录时工单版本')
  })

  it('uses the system actor only for stored actor zero', async () => {
    expect(await render({ ...record, actorUserId: '0' })).toContain('系统')
    expect(await render(record)).toContain('用户 9007199254740993')
  })

  it('hides staff audit metadata in the customer build even if a payload includes it', async () => {
    portal.customer = true
    const html = await render(record)
    expect(html).not.toContain(record.recordId)
    expect(html).not.toContain(record.operationId)
    expect(html).not.toContain('记录追溯')
  })

  it('keeps historical entries with missing operation references readable', async () => {
    const html = await render({ recordId: record.recordId, actorUserId: record.actorUserId, rowVersion: '0' })
    expect(html).toContain(record.recordId)
    expect(html).not.toContain('原操作UUID')
    expect(html).not.toContain('分派ID')
    expect(html).not.toContain('undefined')
  })

  it('adds no audit metadata to a submission marker without a processing record', async () => {
    expect(await render({ type: 'SUBMITTED' })).not.toContain('记录追溯')
  })
})
