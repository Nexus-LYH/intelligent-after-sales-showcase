// Real browser -> single 5173 -> gateway -> real services. Run ONLY after backend window release.
// No SQL, fixtures, bootstrap or environment changes. Never log secrets, traces, HAR or request bodies.
import { chromium } from 'playwright'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { randomBytes } from 'node:crypto'
import { resolve } from 'node:path'
const out = process.env.IAS_BATCH4_EVIDENCE ?? resolve('runtime/batch4-real')
const secretDir = process.env.IAS_BROWSER_SECRET_DIR ?? 'C:/ias-local/Develop/IAS-MySQL84/secrets'
const privatePath = resolve(secretDir, 'frontend-browser-passwords.json')
const saved = JSON.parse(await readFile(privatePath, 'utf8')), initial = JSON.parse(await readFile(resolve(secretDir, 'identity-local-passwords.json'), 'utf8'))
const plan = JSON.parse(await readFile(resolve('../../services/identity/bootstrap/local-accounts.json'), 'utf8'))
const browser = await chromium.launch({ channel: 'chrome', headless: true }), contexts = [], results = [], records = []
const suffix = Date.now().toString(36).toUpperCase(), root = 'http://127.0.0.1:5173', calls = []
await mkdir(out, { recursive: true })
const assert = (v, message) => { if (!v) throw new Error(message) }
const password = () => randomBytes(24).toString('base64url')
async function test(name, action) { try { await action(); results.push({ name, status: 'PASS' }); console.log(JSON.stringify(results.at(-1))) } catch (e) { results.push({ name, status: 'FAIL', failure: e.message.split('\n')[0] }); throw new Error(`Real browser failed: ${name}`) } }
async function pageFor() {
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } }); contexts.push(context)
  const page = await context.newPage(); page.setDefaultTimeout(15000)
  page.on('dialog', d => d.type() === 'prompt' ? d.accept('真实浏览器验证启停') : d.accept())
  page.on('response', response => { const u = new URL(response.url()); if (u.pathname.startsWith('/api/')) calls.push({ path: u.pathname, method: response.request().method(), status: response.status() }) })
  return page
}
async function login(page, portal, account, value) {
  await page.goto(`${root}/${portal}/${account.realm === 'PLATFORM' ? 'platform/login' : 'login'}`)
  if (account.realm !== 'PLATFORM') await page.getByLabel('租户编码').fill(account.tenantCode)
  await page.getByLabel('账号', { exact: true }).fill(account.account); await page.getByLabel('密码', { exact: true }).fill(value)
  let issued, confirmed, httpOnly = false
  const listener = async response => {
    const path = new URL(response.url()).pathname
    if (response.status() !== 200 || !['/api/auth/login','/api/auth/me'].includes(path)) return
    const cookie = await response.headerValue('set-cookie')
    const token = cookie?.match(/IAS-SESSION-DEV=([^;]+)/)?.[1]
    if (path.endsWith('/login')) issued = token
    else if (token && !confirmed) { confirmed = token; httpOnly = /httponly/i.test(cookie) }
  }
  page.on('response', listener)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByRole('heading', { name: /本人账户|授权设备|首次登录，请修改密码/ }).waitFor()
  page.off('response', listener)
  assert(issued && confirmed && issued !== confirmed && httpOnly, 'first confirmation cookie not replaced/HttpOnly')
}
async function forcePassword(page, portal, old, ref) {
  if (!await page.getByRole('heading', { name: '首次登录，请修改密码' }).isVisible()) return old
  const next = password()
  await page.getByLabel('当前密码', { exact: true }).fill(old); await page.getByLabel('新密码', { exact: true }).fill(next); await page.getByLabel('确认新密码').fill(next)
  await page.getByRole('button', { name: '确认改密' }).click(); await page.getByRole('heading', { name: portal === 'customer' ? '客户登录' : '员工登录' }).waitFor()
  saved[ref] = next; await writeFile(privatePath, JSON.stringify(saved, null, 2)); return next
}
async function create(page, kind, code, name, prepare) {
  const labels = { companies: '客户企业', models: '产品型号', devices: '设备档案' }, paths = { companies: 'customer-companies', models: 'product-models', devices: 'devices' }
  await page.goto(`${root}/staff/assets/${kind}`); await page.getByRole('button', { name: `新建${labels[kind]}` }).click()
  await page.getByLabel(kind === 'devices' ? '设备编号 *' : '编码 *', { exact: true }).fill(code)
  await page.getByLabel(kind === 'devices' ? '设备名称 *' : '名称 *', { exact: true }).fill(name)
  if (prepare) await prepare()
  const response = page.waitForResponse(r => new URL(r.url()).pathname === `/api/asset/${paths[kind]}` && r.request().method() === 'POST')
  await page.getByRole('button', { name: '确认创建', exact: true }).click(); const reply = await response
  assert(reply.status() === 200, 'real asset create rejected'); const data = await reply.json()
  await page.getByRole('heading', { name, exact: true }).waitFor(); return data
}
async function enroll(page, company, tenant, index) {
  await page.goto(`${root}/staff/assets/companies/${company.id}`); await page.getByRole('button', { name: '开通普通客户', exact: true }).click()
  const account = `web.${suffix.toLowerCase()}.${index}`, value = password(), displayName = `浏览器客户${index}`
  await page.getByLabel('客户账号').fill(account); await page.getByLabel('客户姓名').fill(displayName); await page.getByLabel('一次性初始密码', { exact: true }).fill(value)
  const response = page.waitForResponse(r => r.request().method() === 'POST' && new URL(r.url()).pathname.endsWith(`/${company.id}/members`))
  await page.getByRole('button', { name: '确认开通', exact: true }).click(); const reply = await response
  assert(reply.status() === 200, 'real enrollment rejected'); const data = await reply.json(); assert(data.state === 'COMPLETED', 'enrollment did not complete')
  const ref = `customer:${tenant}:${account}`; saved[ref] = value; await writeFile(privatePath, JSON.stringify(saved, null, 2))
  return { account, realm: 'TENANT', tenantCode: tenant, ref, userId: data.userId, companyId: company.id }
}
try {
  for (const [ref, letter] of [['tenantA1','A'],['tenantB','B']]) {
    const admin = plan.users.find(u => u.passwordRef === ref); assert(admin, 'admin plan missing')
    const staff = await pageFor(); let adminPassword = saved[ref] ?? initial[ref]
    await test(`${letter}:管理员经gateway首次确认及既有登录`, async () => { await login(staff, 'staff', admin, adminPassword); const next = await forcePassword(staff, 'staff', adminPassword, ref); if (next !== adminPassword) { adminPassword = next; await login(staff, 'staff', admin, adminPassword) } })
    const model = await create(staff, 'models', `M${letter}${suffix}`, `型号${letter}${suffix}`)
    for (const n of [1,2]) {
      let company, device, customer
      await test(`${letter}${n}:真实页面企业型号设备建档`, async () => {
        company = await create(staff, 'companies', `C${letter}${suffix}${n}`, `企业${letter}${suffix}${n}`)
        device = await create(staff, 'devices', `D${letter}${suffix}${n}`, `设备${letter}${suffix}${n}`, async () => {
          await staff.getByLabel('序列号 *').fill(`SERIAL${letter}${suffix}${n}`)
          await staff.getByText('先选择启用的客户企业', { exact: true }).click(); await staff.getByRole('option', { name: `${company.code} · ${company.name}`, exact: true }).click()
          await staff.getByText('选择启用的产品型号', { exact: true }).click(); await staff.getByRole('option', { name: `${model.code} · ${model.name}`, exact: true }).click()
          await staff.getByLabel('保修开始日期').fill('2026-10-01'); await staff.getByLabel('保修结束日期').fill('2027-10-01')
        })
      })
      await test(`${letter}${n}:受控客户开通COMPLETED`, async () => { customer = await enroll(staff, company, admin.tenantCode, `${letter.toLowerCase()}${n}`) })
      const portal = await pageFor()
      await test(`${letter}${n}:客户首登受限、改密后重新登录`, async () => {
        await login(portal, 'customer', customer, saved[customer.ref]); assert(await portal.getByRole('heading', { name: '首次登录，请修改密码' }).isVisible(), 'customer not restricted')
        await portal.goto(`${root}/customer/devices/${device.id}`); await portal.getByRole('heading', { name: '首次登录，请修改密码' }).waitFor()
        const next = await forcePassword(portal, 'customer', saved[customer.ref], customer.ref); await login(portal, 'customer', customer, next)
      })
      await test(`${letter}${n}:授权设备列表详情及深链刷新`, async () => {
        await portal.getByRole('link', { name: '查看设备详情 →' }).first().waitFor()
        const card = portal.locator('.device-card').filter({ has: portal.getByRole('heading', { name: device.code, exact: true }) }); await card.getByRole('link', { name: '查看设备详情 →' }).click()
        await portal.getByRole('heading', { name: device.code, exact: true }).waitFor(); await portal.reload(); await portal.getByRole('heading', { name: device.code, exact: true }).waitFor()
        assert(!await portal.getByRole('button', { name: /报修|编辑|新建/ }).count(), 'out-of-scope customer action')
      })
      const other = records.find(r => r.tenantCode === admin.tenantCode)
      if (other) await test(`${letter}${n}:另一企业详情/筛选及total均不越权`, async () => {
        await portal.goto(`${root}/customer/devices/${other.deviceId}`); await portal.getByText(/TARGET_NOT_ACCESSIBLE/).waitFor(); assert(!await portal.getByRole('heading', { name: other.deviceCode, exact: true }).isVisible(), 'foreign detail leaked')
        const response = portal.waitForResponse(r => new URL(r.url()).pathname === '/api/asset/devices' && new URL(r.url()).searchParams.get('companyId') === other.companyId)
        await portal.goto(`${root}/customer/devices?companyId=${other.companyId}`); const reply = await response; assert(reply.status() === 200, 'foreign filter response'); const data = await reply.json(); assert(data.total === 0 && data.items.length === 0, 'foreign total leaked')
      })
      if (letter === 'B') await test(`${letter}${n}:跨租户设备详情拒绝`, async () => { await portal.goto(`${root}/customer/devices/${records[0].deviceId}`); await portal.getByText(/TARGET_NOT_ACCESSIBLE/).waitFor() })
      await portal.goto(`${root}/customer/devices/${device.id}`); await portal.getByRole('heading', { name: device.code, exact: true }).waitFor()
      if (letter === 'A' && n === 1) await portal.screenshot({ path: resolve(out, 'real-customer-device.png'), fullPage: true })
      records.push({ tenantCode: admin.tenantCode, companyId: company.id, deviceId: device.id, deviceCode: device.code, modelId: model.id, customer: customer.account, userId: customer.userId, passwordRef: customer.ref })
      await test(`${letter}${n}:管理员撤权后客户旧会话经asset拒绝并清正文`, async () => {
        let userId = customer.userId
        if (!userId) { await staff.goto(`${root}/staff/users?keyword=${customer.account}`); const list = await staff.evaluate(async account => (await fetch('/api/identity/users?keyword=' + encodeURIComponent(account), { cache: 'no-store' })).json(), customer.account); userId = list.items.find(u => u.account === customer.account)?.id }
        assert(userId, 'customer identity reference missing')
        await staff.goto(`${root}/staff/users/${userId}`); await staff.getByRole('button', { name: '撤销全部会话' }).click(); await staff.getByLabel('撤权原因').fill('第四批浏览器真实撤权验证'); await staff.getByRole('button', { name: '确认撤权', exact: true }).click(); await staff.getByText(/撤权成功/).waitFor()
        const result = await portal.evaluate(async id => { const r = await fetch('/api/asset/devices/' + id, { cache: 'no-store' }); return r.status }, device.id); assert(result === 401, 'revoked customer accepted by asset')
        await portal.getByRole('button', { name: '重新查询', exact: true }).click(); await portal.getByRole('heading', { name: '客户登录', exact: true }).waitFor(); assert(!await portal.getByRole('heading', { name: device.code, exact: true }).isVisible(), 'old customer body survives rejection')
      })
      await portal.context().close()
    }
    await staff.screenshot({ path: resolve(out, `real-staff-${letter}.png`), fullPage: true })
    await staff.context().close()
  }
} finally {
  for (const context of contexts) await context.close().catch(() => {})
  await browser.close()
  await writeFile(resolve(out, 'batch4-real-results.json'), JSON.stringify({ mode: 'REAL_CHROME_GATEWAY_IDENTITY_ASSET_MYSQL', results, accounts: records, calls, notes: ['No SQL/fixture/bootstrap. Existing changed passwords preserved; only first-login-required accounts changed.', 'Current browser-owned passwords are only in the existing external restricted role file.', 'No physical DB failure or production TLS claim.'] }, null, 2))
  console.log(JSON.stringify({ passed: results.filter(r => r.status === 'PASS').length, failed: results.filter(r => r.status !== 'PASS').length }))
  if (results.some(r => r.status !== 'PASS')) process.exitCode = 1
}
