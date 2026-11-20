import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'
import { resolve } from 'node:path'

const root = new URL('../../', import.meta.url)
export const suites = [
  ['identity', 'com.ias.identity.AuthCryptoTest'],
  ['identity', 'com.ias.identity.auth.DomainPolicyTest'],
  ['identity', 'com.ias.identity.auth.FeeMoneyPolicyTest'],
  ['ticket', 'com.ias.ticket.ApprovalBoundaryTest'],
  ['ticket', 'com.ias.ticket.ApprovalSettlementTest'],
  ['ticket', 'com.ias.ticket.CompletionTimingTest'],
  ['ticket', 'com.ias.ticket.SupplementTimingTest'],
  ['ticket', 'com.ias.ticket.ReopenWindowTest'],
  ['ticket', 'com.ias.ticket.StockExecutionBoundaryTest'],
  ['identity', 'com.ias.identity.auth.StockEngineerQualificationTest'],
  ['identity', 'com.ias.identity.config.StockEngineerSecurityBoundaryTest'],
  ['gateway', 'com.ias.gateway.FinanceGatewayBoundaryTest'],
  ['gateway', 'com.ias.gateway.MembershipRoleGatewayBoundaryTest'],
  ['asset', 'com.ias.asset.MembershipRoleServiceTest'],
  ['asset', 'com.ias.asset.MembershipRoleRequestBoundaryTest'],
  ['asset', 'com.ias.asset.MembershipRoleErrorsTest'],
  ['ticket', 'com.ias.ticket.FinanceQuoteBoundaryTest'],
  ['ticket', 'com.ias.ticket.FinanceQuoteSourceAdapterTest'],
  ['ticket', 'com.ias.ticket.FinanceFreezeCoordinatorTest'],
  ['ticket', 'com.ias.ticket.FinanceQuoteReferencesTest'],
  ['ticket', 'com.ias.ticket.FeeApprovalContextTest'],
  ['ticket', 'com.ias.ticket.FinanceVouchersTest'],
  ['ticket', 'com.ias.ticket.FeeCompletionPullTest'],
  ['ticket', 'com.ias.ticket.FinanceLaborExecutionStoreTest'],
  ['ticket', 'com.ias.ticket.FinanceLaborExecutionHttpTest'],
  ['identity', 'com.ias.identity.auth.MembershipRoleEligibilityTest'],
  ['identity', 'com.ias.identity.bootstrap.ConfigureFinanceCustomerTest'],
]

export function validateSuite(xml, expectedName) {
  const header = xml.match(/<testsuite\b([^>]*)>/)?.[1]
  if (!header) throw new Error(`Missing testsuite: ${expectedName}`)
  const attributes = Object.fromEntries([...header.matchAll(/([\w.-]+)="([^"]*)"/g)].map((m) => [m[1], m[2]]))
  if (attributes.name !== expectedName) throw new Error(`Unexpected suite name: ${expectedName}`)
  for (const field of ['tests', 'errors', 'failures', 'skipped']) {
    if (!/^\d+$/.test(attributes[field] ?? '')) throw new Error(`Missing or invalid ${field}: ${expectedName}`)
  }
  if (Number(attributes.tests) < 1 || ['errors', 'failures', 'skipped'].some((field) => Number(attributes[field]) !== 0)) {
    throw new Error(`Suite did not execute cleanly without skips: ${expectedName}`)
  }
  return Number(attributes.tests)
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  let count = 0
  for (const [module, name] of suites) {
    const report = new URL(`services/${module}/target/surefire-reports/TEST-${name}.xml`, root)
    count += validateSuite(readFileSync(report, 'utf8'), name)
  }
  console.log(`Portable suites: ${suites.length}; tests: ${count}; failures/errors/skips: 0. Database, browser, integration and deployment acceptance are not covered.`)
}

