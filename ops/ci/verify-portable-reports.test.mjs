import test from 'node:test'
import assert from 'node:assert/strict'
import { validateSuite } from './verify-portable-reports.mjs'

const name = 'com.ias.example.PortableTest'
const xml = (overrides = {}) => `<testsuite ${Object.entries({ name, tests: '2', errors: '0', failures: '0', skipped: '0', ...overrides }).map(([key, value]) => `${key}="${value}"`).join(' ')}></testsuite>`

test('counts executed clean tests', () => assert.equal(validateSuite(xml(), name), 2))
test('rejects empty, skipped, failed and errored suites', () => {
  for (const overrides of [{ tests: '0' }, { skipped: '1' }, { failures: '1' }, { errors: '1' }]) {
    assert.throws(() => validateSuite(xml(overrides), name))
  }
})
test('rejects malformed counts, wrong reports and missing XML', () => {
  assert.throws(() => validateSuite(xml({ tests: 'NaN' }), name))
  assert.throws(() => validateSuite(xml({ name: 'Other' }), name))
  assert.throws(() => validateSuite('<broken/>', name))
  assert.throws(() => validateSuite(`<testsuite name="${name}" tests="1"/>`, name))
})
