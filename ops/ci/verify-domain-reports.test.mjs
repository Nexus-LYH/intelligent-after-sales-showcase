import test from 'node:test'
import assert from 'node:assert/strict'
import { validateDomainReports } from './verify-domain-reports.mjs'

const xml = (name = 'com.ias.finance.FeeGroupTest', skipped = 0) => `<testsuite name="${name}" tests="14" errors="0" failures="0" skipped="${skipped}"></testsuite>`
test('counts all suites in a domain', () => assert.equal(validateDomainReports([xml(), xml('com.ias.finance.OtherTest')], 'finance').tests, 28))
test('rejects missing or skipped domain execution', () => {
  assert.throws(() => validateDomainReports([], 'finance'))
  assert.throws(() => validateDomainReports([xml(undefined, 1)], 'finance'))
})
test('rejects another domain and duplicate suite evidence', () => {
  assert.throws(() => validateDomainReports([xml('com.ias.approval.OtherTest')], 'finance'))
  assert.throws(() => validateDomainReports([xml(), xml()], 'finance'))
})
