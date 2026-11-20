import { readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { resolve, relative, sep } from 'node:path'

const root = resolve(process.argv[2] ?? '.')
const output = process.argv[3]
const frozenFrontendManifestRef = process.argv[4]
if (!output) throw new Error('Usage: node ops/ci/audit-baseline.mjs <source-root> <external-evidence-json>')
const selected = new Set(['.gitignore', 'README.md', '.github/workflows/identity-build.yml', 'services/pom.xml', 'services/.gitignore', 'services/README.md'])
selected.add('services/approval/api-parts-v1.json')
selected.add('services/approval/api-fees-v1.json')
for(const file of ['services/finance/api-source-v1.md','services/finance/api-money-v1.md']) selected.add(file)
selected.add('services/finance/api-money-authority-v1.md')
selected.add('services/ticket/finance-vouchers-v1.json')
selected.add('services/ticket/api-fee-execution-v1.json')
for (const file of ['services/stock/inbound-api-v1.json','services/stock/execution-api-v1.json','services/finance/api-quotes-v1.json']) selected.add(file)
for(const file of ['services/stock/api-catalog-v1.json','services/notification/api-messages-v1.json','services/ticket/approval-boundary-v1.json']) selected.add(file)
selected.add('services/ticket/finance-freeze-owner-protocol-v1.json')
selected.add('services/asset/api-membership-roles-v1.json')
const excludedDirectories = new Set(['target', 'node_modules', 'dist', 'runtime', 'uploads', 'backups', 'secrets', '.git'])
const excludedFile = /(?:^|\/)(?:\.env(?:\..*)?|application-local\.(?:yml|yaml|properties)|\.npmrc|.*\.(?:p12|pfx|pem|key|jks|keystore|log|class|jar|zip|pdf|png|jpg|jpeg))$/i
function walk(dir) {
  for (const entry of readdirSync(resolve(root, dir), { withFileTypes: true })) {
    const name = `${dir}/${entry.name}`
    if (entry.isSymbolicLink()) throw new Error(`Symlink forbidden: ${name}`)
    if (entry.isDirectory()) {
      if (!excludedDirectories.has(entry.name)) walk(name)
    } else if (entry.isFile() && !excludedFile.test(name)) selected.add(name)
  }
}
const includedModules = ['trust', 'identity', 'asset', 'ticket', 'gateway', 'approval', 'stock', 'finance', 'notification', 'ai']
for (const module of includedModules) {
  for (const file of ['pom.xml', 'README.md']) selected.add(`services/${module}/${file}`)
  walk(`services/${module}/src`)
}
walk('services/identity/scripts')
for (const app of ['staff', 'customer']) {
  for (const file of ['package.json', 'package-lock.json', 'index.html', 'tsconfig.json', 'vite.config.ts', 'README.md']) selected.add(`apps/${app}/${file}`)
  if (!frozenFrontendManifestRef) for (const dir of ['src', 'scripts']) walk(`apps/${app}/${dir}`)
}
if (frozenFrontendManifestRef) {
  const frozen = JSON.parse(readFileSync(frozenFrontendManifestRef, 'utf8').replace(/^\uFEFF/, ''))
  for (const file of frozen.files.filter((file) => file.path.startsWith('apps/'))) {
    if (file.path.includes('..') || !file.path.match(/^apps\/(staff|customer)\//)) throw new Error('Invalid frozen frontend path')
    const actualHash = createHash('sha256').update(readFileSync(resolve(root, file.path))).digest('hex')
    if (actualHash !== file.sha256) throw new Error(`Frozen frontend source changed: ${file.path}`)
    selected.add(file.path)
  }
} else walk('apps/staff/tests')
walk('ops/ci')
walk('ops/local')

const patterns = [
  ['private-key', /-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----/g],
  ['github-token', /\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,})\b/g],
  ['provider-token', /\b(?:sk-(?:proj-|ant-)?[A-Za-z0-9_-]{20,}|AIza[0-9A-Za-z_-]{30,}|AKIA[0-9A-Z]{16})\b/g],
  ['credential-url', /\b(?:https?|mysql|postgres(?:ql)?):\/\/[^\s/@:]+:[^\s/@]+@/g],
  ['literal-credential-assignment', /\b(?:password|passwd|api[_-]?key|client[_-]?secret|access[_-]?token|refresh[_-]?token|storePassword|appPassword|migrationPassword)\b\s*[=:]\s*["'`]([^"'`\r\n]+)["'`]/gi],
]
const findings = [], files = []
for (const name of [...selected].sort()) {
  const absolute = resolve(root, name)
  if (relative(root, absolute).startsWith(`..${sep}`)) throw new Error('Path escapes root')
  if (!statSync(absolute).isFile()) throw new Error(`Not a regular file: ${name}`)
  const bytes = readFileSync(absolute)
  if (bytes.includes(0)) throw new Error(`Binary content forbidden: ${name}`)
  const source = bytes.toString('utf8')
  for (const [kind, regex] of patterns) {
    for (const match of source.matchAll(regex)) {
      findings.push({ path: name, line: source.slice(0, match.index).split('\n').length, kind })
    }
  }
  files.push({ path: name, size: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') })
}
writeFileSync(output, JSON.stringify({ capturedAt: new Date().toISOString(), sourceRoot: root, includedModules, excludedUnfrozenModules: [], frozenFrontendManifestRef: frozenFrontendManifestRef ?? null, excluded: ['private/local configuration', 'bootstrap environment plans', 'runtime/results/screenshots', 'caches/build output', 'external secrets', ...(frozenFrontendManifestRef ? ['frontend additions absent from the prior frozen manifest'] : [])], files, findings, scannedFiles: files.length, scope: 'Pattern scan of explicit source allowlist; no external secret values read; findings require review before commit/push' }, null, 2))
console.log(JSON.stringify({ scannedFiles: files.length, findings }, null, 2))

