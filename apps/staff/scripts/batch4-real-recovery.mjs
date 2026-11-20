// Real gateway/services/MySQL. Response replacement is HTTP fault injection, NOT database outage.
import { chromium } from 'playwright'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { randomBytes } from 'node:crypto'
import { resolve } from 'node:path'
const out = process.env.IAS_BATCH4_EVIDENCE ?? resolve('runtime/batch4-real')
const baseline = JSON.parse(await readFile(resolve(out, 'batch4-real-results.json'), 'utf8'))
const plan = JSON.parse(await readFile(resolve('../../services/identity/bootstrap/local-accounts.json'), 'utf8'))
const secretDir = process.env.IAS_BROWSER_SECRET_DIR ?? 'C:/ias-local/Develop/IAS-MySQL84/secrets', privatePath = resolve(secretDir, 'frontend-browser-passwords.json')
const saved = JSON.parse(await readFile(privatePath, 'utf8')), admin = plan.users.find(u => u.passwordRef === 'tenantA1'), target = baseline.accounts[0]
const browser = await chromium.launch({ channel: 'chrome', headless: true }), contexts = [], results = [], refs = []
const root = 'http://127.0.0.1:5173', suffix = Date.now().toString(36).toUpperCase()
await mkdir(out, { recursive: true })
const assert = (v, m) => { if (!v) throw new Error(m) }
async function test(name, f) { try { await f(); results.push({ name, status: 'PASS' }); console.log(JSON.stringify(results.at(-1))) } catch (e) { results.push({ name, status: 'FAIL', failure: e.message.split('\n')[0] }); throw new Error(`Real recovery failed: ${name}`) } }
async function pageFor() { const context = await browser.newContext({ viewport: { width: 1280, height: 900 } }); contexts.push(context); const page = await context.newPage(); page.setDefaultTimeout(15000); page.on('dialog', d => d.type() === 'prompt' ? d.accept('浏览器真实启停恢复验证') : d.accept()); return page }
async function login(page, portal, account, password) { await page.goto(`${root}/${portal}/login`); await page.getByLabel('租户编码').fill(account.tenantCode); await page.getByLabel('账号', { exact: true }).fill(account.account); await page.getByLabel('密码', { exact: true }).fill(password); await page.getByRole('button', { name: '登录', exact: true }).click(); await page.getByRole('heading', { name: /本人账户|授权设备|首次登录，请修改密码/ }).waitFor() }
async function editName(page, name) { await page.getByRole('button', { name: '编辑基本资料' }).click(); await page.getByLabel('名称 *', { exact: true }).fill(name); await page.getByRole('button', { name: '保存修改' }).click() }
try {
  const staff = await pageFor(), rival = await pageFor()
  await login(staff, 'staff', admin, saved.tenantA1); await login(rival, 'staff', admin, saved.tenantA1)
  await test('真实409版本竞争刷新详情并关闭旧确认', async () => {
    await staff.goto(`${root}/staff/assets/companies/${target.companyId}`); await staff.getByRole('button', { name: '编辑基本资料' }).waitFor()
    await rival.goto(`${root}/staff/assets/companies/${target.companyId}`); await editName(rival, `企业维护${suffix}`); await rival.getByText('已保存。', { exact: true }).waitFor()
    await editName(staff, `过期编辑${suffix}`); await staff.getByText(/版本或操作内容冲突/).waitFor(); await staff.getByRole('heading', { name: `企业维护${suffix}`, exact: true }).waitFor(); await staff.getByRole('dialog').waitFor({state:'hidden'})
  })
  const portal = await pageFor(), customer = { tenantCode: target.tenantCode, account: target.customer }
  await login(portal, 'customer', customer, saved[target.passwordRef])
  await test('企业停用后授权客户只读显示真实企业原因、再启用', async () => {
    await staff.getByRole('button', { name: '停用', exact: true }).click(); await staff.getByText(/此档案已停用/).waitFor()
    await portal.goto(`${root}/customer/devices/${target.deviceId}`); await portal.getByText(/所属企业已停用/).waitFor(); assert(!await portal.getByRole('button', { name: /报修|新建|编辑/ }).count(), 'disabled company action')
    await staff.getByRole('button', { name: '启用', exact: true }).click(); await staff.getByRole('button', { name: '停用', exact: true }).waitFor()
  })
  await test('设备停用客户详情保留只读说明、再启用', async () => {
    await staff.goto(`${root}/staff/assets/devices/${target.deviceId}`); await staff.getByRole('button', { name: '停用', exact: true }).click(); await staff.getByText(/此档案已停用/).waitFor()
    await portal.reload(); await portal.getByText('设备已停用，请联系服务商。保留只读档案。').waitFor()
    await staff.getByRole('button', { name: '启用', exact: true }).click(); await staff.getByRole('button', { name: '停用', exact: true }).waitFor()
  })
  await portal.context().close(); await rival.context().close()
  await test('真实资产提交200后替换503，查询原UUID不重写', async () => {
    let originalKey, committed, writes = 0
    await staff.route('**/api/asset/customer-companies', async route => {
      if (route.request().method() !== 'POST') return route.continue()
      writes++; originalKey = route.request().headers()['idempotency-key']; const response = await route.fetch(); assert(response.status() === 200, 'real commit failed'); committed = await response.json()
      return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'OPERATION_RESULT_UNKNOWN', message: '受控丢失已提交回执' }) })
    })
    await staff.goto(`${root}/staff/assets/companies`); await staff.getByRole('button', { name: '新建客户企业' }).click(); await staff.getByLabel('编码 *', { exact: true }).fill(`U${suffix}`); await staff.getByLabel('名称 *', { exact: true }).fill(`未知回执企业${suffix}`); await staff.getByRole('button', { name: '确认创建', exact: true }).click()
    await staff.getByRole('button', { name: '重新核验', exact: true }).waitFor(); assert(!await staff.getByRole('heading', { name: '客户企业', exact: true }).isVisible(), 'old body survived unknown')
    await staff.getByRole('button', { name: '重新核验', exact: true }).click(); await staff.getByRole('button', { name: '查询原操作', exact: true }).click(); await staff.getByText(/原操作已确认完成/).waitFor(); assert(writes === 1 && originalKey === committed.operationId, 'changed key or duplicate write')
    refs.push({ kind: 'asset-unknown', operationId: originalKey, companyId: committed.id }); await staff.unroute('**/api/asset/customer-companies')
  })
  await test('真实客户开通已完成后替换503，仅查询原操作并可首登', async () => {
    let originalKey, committed, writes = 0
    const account = `recover.${suffix.toLowerCase()}`, initialPassword = randomBytes(24).toString('base64url'), ref = `customer:${target.tenantCode}:${account}`
    saved[ref] = initialPassword; await writeFile(privatePath, JSON.stringify(saved, null, 2))
    await staff.route(`**/api/asset/customer-companies/${target.companyId}/members`, async route => {
      if (route.request().method() !== 'POST') return route.continue()
      writes++; originalKey = route.request().headers()['idempotency-key']; const response = await route.fetch(); assert(response.status() === 200, 'enrollment commit failed'); committed = await response.json()
      return route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'OPERATION_RESULT_UNKNOWN', message: '受控丢失开通回执' }) })
    })
    await staff.goto(`${root}/staff/assets/companies/${target.companyId}`); await staff.getByRole('button', { name: '开通普通客户' }).click(); await staff.getByLabel('客户账号').fill(account); await staff.getByLabel('客户姓名').fill('真实恢复客户'); await staff.getByLabel('一次性初始密码', { exact: true }).fill(initialPassword); await staff.getByRole('button', { name: '确认开通', exact: true }).click()
    await staff.getByRole('button', { name: '重新核验', exact: true }).waitFor(); await staff.getByRole('button', { name: '重新核验', exact: true }).click(); await staff.getByRole('button', { name: '查询原操作', exact: true }).click(); await staff.getByText(/原操作已确认完成/).waitFor(); assert(writes === 1 && originalKey === committed.operationId && committed.state === 'COMPLETED', 'enrollment incorrectly replayed')
    await staff.unroute(`**/api/asset/customer-companies/${target.companyId}/members`)
    const page = await pageFor(); await login(page, 'customer', { tenantCode: target.tenantCode, account }, initialPassword); await page.getByRole('heading', { name: '首次登录，请修改密码' }).waitFor()
    const next = randomBytes(24).toString('base64url'); await page.getByLabel('当前密码', { exact: true }).fill(initialPassword); await page.getByLabel('新密码', { exact: true }).fill(next); await page.getByLabel('确认新密码').fill(next); await page.getByRole('button', { name: '确认改密' }).click(); await page.getByRole('heading', { name: '客户登录' }).waitFor(); saved[ref] = next; await writeFile(privatePath, JSON.stringify(saved, null, 2))
    await login(page, 'customer', { tenantCode: target.tenantCode, account }, next); await page.getByRole('heading', { name: '授权设备' }).waitFor(); refs.push({ kind: 'enrollment-unknown', operationId: originalKey, companyId: target.companyId, account, passwordRef: ref }); await page.context().close()
  })
} finally {
  for (const context of contexts) await context.close().catch(() => {}); await browser.close()
  await writeFile(resolve(out, 'batch4-real-recovery-results.json'), JSON.stringify({ mode: 'REAL_CHROME_GATEWAY_IDENTITY_ASSET_MYSQL_WITH_CONTROLLED_RESPONSE_REPLACEMENT', results, refs, notes: ['503 cases, when reached, replace actual server 200 commit responses; no DB shutdown or physical commit kill.', 'No automatic replay. Resume/ENTER_INITIAL_PASSWORD intermediate states were tested synthetically and in backend integration, not physically induced here.'] }, null, 2))
  console.log(JSON.stringify({ passed: results.filter(r => r.status === 'PASS').length, failed: results.filter(r => r.status !== 'PASS').length })); if (results.some(r => r.status !== 'PASS')) process.exitCode = 1
}
