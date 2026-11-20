import { readdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { validateSuite } from './verify-portable-reports.mjs'

export const domains = ['approval', 'stock', 'finance', 'notification', 'ai']
const root = new URL('../../', import.meta.url)

export function validateDomainReports(reports, domain) {
  if (!reports.length) throw new Error(`No executed suites for domain: ${domain}`)
  const seen = new Set()
  let count = 0
  for (const xml of reports) {
    const suite = xml.match(/<testsuite\b[^>]*\bname="([^"]+)"/)?.[1]
    if (!suite?.startsWith(`com.ias.${domain}.`) || seen.has(suite)) throw new Error(`Unexpected or duplicate suite for domain: ${domain}`)
    seen.add(suite)
    count += validateSuite(xml, suite)
  }
  return { domain, suites: seen.size, tests: count, failures: 0, errors: 0, skips: 0 }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const results = domains.map((domain) => {
    const dir = new URL(`services/${domain}/target/surefire-reports/`, root)
    const files = readdirSync(dir).filter((file) => /^TEST-.*\.xml$/.test(file))
    return validateDomainReports(files.map((file) => readFileSync(new URL(file, dir), 'utf8')), domain)
  })
  console.log(JSON.stringify({ domains: results, totalTests: results.reduce((sum, r) => sum + r.tests, 0), scope: 'Portable domain tests; H2/proxy faults do not establish MySQL, trusted network or end-user acceptance.' }))
}
