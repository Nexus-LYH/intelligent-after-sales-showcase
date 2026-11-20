// Real Chrome/Vue, entirely synthetic HTTP fixtures. No backend/DB connection or real secrets.
import { chromium } from 'playwright'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
const out = process.env.IAS_BATCH4_EVIDENCE ?? resolve('runtime/batch4')
await mkdir(out, { recursive: true })
const browser = await chromium.launch({ channel: 'chrome', headless: true }), results = []
const assert = (v, message) => { if (!v) throw new Error(message) }
const common = { status: 'ENABLED', rowVersion: '1', createdAt: '2026-10-15T00:00:00Z', updatedAt: '2026-10-15T00:00:00Z' }
async function scenario(name, config, action) {
  const context = await browser.newContext({ viewport: config.mobile ? { width: 390, height: 844 } : { width: 1280, height: 900 } }), page = await context.newPage()
  page.setDefaultTimeout(6000); page.on('dialog', async d => d.type() === 'prompt' ? d.accept('停用原因合成测试') : d.accept())
  let state = config.restricted ? 'PASSWORD_CHANGE_REQUIRED' : 'NORMAL', logged = true, attempt = 'fixture-attempt', calls = [], writes = [], held = [], resumeCount = 0
  const identity = () => ({ userId: '101', account: config.type === 'CUSTOMER' ? 'fixture.customer' : 'fixture', displayName: '合成账号', realm: config.platform ? 'PLATFORM' : 'TENANT', identityType: config.type ?? 'EMPLOYEE', tenant: config.platform ? null : { id: '1', code: 'AA', name: '合成服务商' }, department: null, permissions: config.noCapability ? [] : ['asset.read','asset.manage','identity.customer.provision','identity.session.revoke'], accessState: state, loginAttemptId: attempt, expiresAt: '2099-01-01T00:00:00Z' })
  const companies = [{ ...common, id: '1', code: 'C1', name: '合成企业', contactName: '', phone: '', address: '' }]
  const models = [{ ...common, id: '2', code: 'M1', name: '合成型号', category: '', description: '' }]
  const devices = [{ ...common, id: '3', code: 'D1', name: '合成设备', serialNumber: 'S1', companyId: '1', modelId: '2', location: '安装位置', warrantyStart: null, warrantyEnd: null, purchaseDate: null, ownershipVersion: '1' }]
  if (config.disabled) devices[0].status = 'DISABLED'
  const db = { 'customer-companies': companies, 'product-models': models, devices }
  await context.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname, method = req.method(), body = req.postDataJSON?.()
    calls.push({ path, method }); if (method !== 'GET') writes.push({ path, key: req.headers()['idempotency-key'], body: body ? { ...body, ...(body.initialPassword ? { initialPassword: '[REDACTED]' } : {}) } : null })
    const send = (status, value) => route.fulfill({ status, contentType: 'application/json', body: status === 204 ? '' : JSON.stringify(value) })
    const delay = async (value, code = 200) => { let release; const gate = new Promise(r => release = r); held.push(release); await gate; return code === 200 ? send(200, value) : send(code, {code:'LATE_FIXTURE',message:'迟到合成回执'}) }
    if (path === '/api/auth/me') return logged ? send(200, identity()) : send(401, { code: 'SESSION_INVALID' })
    if (path === '/api/auth/csrf') return send(200, { token: 'synthetic', headerName: 'X-CSRF-TOKEN', contextKind: logged ? 'AUTH' : 'PREAUTH', ...(logged ? { loginAttemptId: attempt } : {}), expiresAt: '2099-01-01T00:00:00Z' })
    if (path === '/api/auth/login') { if(body.account === 'fixture.customer') config.type='CUSTOMER'; logged = true; attempt = req.headers()['x-login-attempt-id']; return send(200, identity()) }
    if (path === '/api/auth/logout') { logged = false; return send(204) }
    if (path === '/api/auth/password') { state = 'NORMAL'; logged = false; return send(200, { nextAction: 'LOGIN' }) }
    if (path === '/api/asset/me/customer-companies') return send(200, { items: companies })
    if (path.includes('/customer-enrollments/')) {
      if (config.holdRecovery) return delay({operationId:path.split('/')[4],state:'COMPLETED',companyId:'1',nextAction:'NONE'}, held.length === 0 ? config.lateStatus : 200)
      if (method === 'POST') resumeCount++
      return send(200, { operationId: path.split('/')[4], state: method === 'POST' ? 'COMPLETED' : config.needPassword ? 'AWAITING_INITIAL_PASSWORD' : 'MEMBERSHIP_COMMITTED', companyId: '1', account: 'fixture.customer', displayName: '合成客户', nextAction: method === 'POST' ? 'NONE' : config.needPassword ? 'ENTER_INITIAL_PASSWORD' : 'RESUME' })
    }
    if (config.holdRecovery && path.startsWith('/api/asset/operations/')) return delay({operationId:path.split('/').at(-1),state:'COMPLETED',result:companies[0]}, held.length === 0 ? config.lateStatus : 200)
    if (path.startsWith('/api/asset/operations/')) return send(200, { operationId: path.split('/').at(-1), state: 'COMPLETED', result: companies[0] })
    if (path.endsWith('/members')) {
      if (method === 'GET') return send(200, { items: [], total: 0, page: 1, size: 20 })
      if (config.holdEnrollment) return delay({operationId:req.headers()['idempotency-key'],state:'COMPLETED',companyId:'1',nextAction:'NONE'},config.lateStatus)
      if (config.enrollUnknown && writes.filter(w => w.path.endsWith('/members')).length === 1) return send(503, { code: 'OPERATION_RESULT_UNKNOWN', message: '开通结果未知' })
      return send(200, { operationId: req.headers()['idempotency-key'], state: 'COMPLETED', companyId: '1', userId: '4', nextAction: 'NONE' })
    }
    const match = path.match(/^\/api\/asset\/(customer-companies|product-models|devices)(?:\/(\d+))?(\/status)?$/)
    if (match) {
      const [, kind, id, statusAction] = match, data = db[kind]
      if (method === 'GET' && id) {
        if (config.holdDetail && kind === 'devices' && !held.length) { let release; const gate = new Promise(r => release = r); held.push(release); await gate; return config.lateStatus && config.lateStatus !== 200 ? send(config.lateStatus, { code: 'LATE_FIXTURE', message: '迟到回执' }) : send(200, { ...data[0], code: 'OLD_RESPONSE' }) }
        return id === '999' ? send(404, { code: 'TARGET_NOT_ACCESSIBLE', message: '无权查看' }) : send(200, data.find(d => d.id === id) ?? data[0])
      }
      if (method === 'GET') return send(200, { items: data, total: data.length, page: Number(url.searchParams.get('page') || 1), size: 20 })
      if (config.holdWrite && method === 'PATCH') return delay({...data[0],name:'OLD_WRITE_SUCCESS'},config.lateStatus)
      if (config.writeUnknown && kind === 'customer-companies') return send(config.faultStatus ?? 503, { code: 'OPERATION_RESULT_UNKNOWN', message: '提交结果未知' })
      if (config.versionConflict && method === 'PATCH') return send(409, { code: 'VERSION_CONFLICT', message: '版本冲突' })
      const value = id ? data.find(d => d.id === id) : { ...common, id: String(10 + data.length) }
      Object.assign(value, body, { rowVersion: id ? '2' : '1' }); delete value.expectedVersion; delete value.reason
      if (!id) data.push(value)
      return send(200, { ...value, operationId: req.headers()['idempotency-key'] })
    }
    return send(403, { code: 'ACCESS_DENIED', message: '不在本批范围' })
  })
  try { await action({ context, page, calls, writes, held, companies, models, devices, resume: () => resumeCount }); results.push({ name, status: 'PASS' }) }
  catch (e) { results.push({ name, status: 'FAIL', failure: e.message.slice(0,1200) }); await page.screenshot({ path: resolve(out, `failure-${results.length}.png`) }).catch(() => {}) }
  finally { for (const release of held) release(); await context.close() }
  await writeFile(resolve(out, 'batch4-ui-results.json'), JSON.stringify({ mode: 'REAL_CHROME_SYNTHETIC_HTTP_NO_DATABASE', results }, null, 2)); console.log(JSON.stringify(results.at(-1)))
}
const staff = 'http://127.0.0.1:5173/staff', customer = 'http://127.0.0.1:5173/customer'
async function openEnroll(page) { await page.goto(staff + '/assets/companies/1'); await page.getByRole('button', { name: '开通普通客户', exact: true }).click(); await page.getByLabel('客户账号').fill('fixture.customer'); await page.getByLabel('客户姓名').fill('合成客户'); await page.getByLabel('一次性初始密码', { exact: true }).fill('synthetic-only-password'); await page.getByRole('button', { name: '确认开通', exact: true }).click() }
try {
  await scenario('员工企业与型号真实组件建档、客户开通字段', {}, async ({ page, writes }) => {
    for (const [kind, title, code] of [['companies','客户企业','CNEW'],['models','产品型号','MNEW']]) {
      await page.goto(staff + '/assets/' + kind); await page.getByRole('button', { name: '新建' + title }).click(); await page.getByLabel('编码 *', { exact: true }).fill(code); await page.getByLabel('名称 *', { exact: true }).fill(title + '合成新档'); await page.getByRole('button', { name: '确认创建' }).click(); await page.getByRole('heading', { name: title + '合成新档' }).waitFor()
    }
    await openEnroll(page); await page.getByRole('heading', { name: '企业成员' }).waitFor(); assert(writes.some(w => w.body?.account === 'fixture.customer'), 'enrollment not sent')
  })
  await scenario('设备建档关联与保修校验', {}, async ({ page, writes }) => {
    await page.goto(staff + '/assets/devices'); await page.getByRole('button', { name: '新建设备档案' }).click()
    await page.getByLabel('设备名称 *').fill('新设备'); await page.getByLabel('设备编号 *').fill('DNEW'); await page.getByLabel('序列号 *').fill('SNEW')
    await page.getByText('先选择启用的客户企业', {exact:true}).click(); await page.getByRole('option', { name: 'C1 · 合成企业' }).click(); await page.getByText('选择启用的产品型号', {exact:true}).click(); await page.getByRole('option', { name: 'M1 · 合成型号' }).click()
    await page.getByLabel('保修开始日期').fill('2026-10-20'); await page.getByRole('button', { name: '确认创建' }).click(); await page.getByText('保修起止日期须同时填写').waitFor(); assert(!writes.length, 'invalid warranty submitted')
    await page.getByLabel('保修结束日期').fill('2027-10-20'); await page.getByRole('button', { name: '确认创建' }).click(); await page.getByRole('heading', { name: '新设备', exact: true }).waitFor(); assert(writes[0].body.companyId === '1' && writes[0].body.modelId === '2', 'association wrong')
  })
  await scenario('设备编辑不发送不可变字段、启停独立动作', {}, async ({ page, writes }) => {
    await page.goto(staff + '/assets/devices/3'); await page.getByRole('button', { name: '编辑基本资料' }).click(); assert(await page.getByLabel('设备编号 *').isDisabled(), 'device code mutable'); assert(await page.getByLabel('序列号 *').isDisabled(), 'serial mutable'); await page.getByLabel('安装位置').fill('新位置'); await page.getByRole('button', { name: '保存修改' }).click(); await page.getByText('已保存。', { exact: true }).waitFor()
    const patch = writes.find(w => w.body?.location === '新位置'); assert(!('companyId' in patch.body) && !('modelId' in patch.body) && !('serialNumber' in patch.body) && !('code' in patch.body), 'immutable fields sent')
    await page.getByRole('button', { name: '停用', exact: true }).click(); await page.getByText('此档案已停用。', { exact: false }).waitFor(); assert(writes.at(-1).path.endsWith('/status') && writes.at(-1).body.reason, 'status contract wrong')
  })
  await scenario('409刷新并要求重新明确提交', { versionConflict: true }, async ({ page, writes }) => { await page.goto(staff + '/assets/companies/1'); await page.getByRole('button', { name: '编辑基本资料' }).click(); await page.getByLabel('名称 *', { exact: true }).fill('冲突编辑'); await page.getByRole('button', { name: '保存修改' }).click(); await page.getByText(/版本或操作内容冲突/).waitFor(); assert(writes.length === 1, 'conflict automatically replayed'); await page.getByRole('heading', { name: '合成企业', exact: true }).waitFor() })
  for (const faultStatus of [500,502,503]) await scenario(`${faultStatus}资产写隐藏正文并仅查询原键`, { writeUnknown: true, faultStatus }, async ({ page, writes }) => { await page.goto(staff + '/assets/companies/1'); await page.getByRole('button', { name: '编辑基本资料' }).click(); await page.getByLabel('名称 *', { exact: true }).fill('未知编辑'); await page.getByRole('button', { name: '保存修改' }).click(); await page.getByRole('button', { name: '重新核验', exact: true }).click(); await page.getByRole('button', { name: '查询原操作' }).click(); await page.getByText(/原操作已确认完成/).waitFor(); assert(writes.length === 1, 'query wrote again') })
  for (const needPassword of [false, true]) await scenario(needPassword ? '开通未知仅服务端要求后同键重输密码' : '开通未知按原操作显式resume不重输密码', { enrollUnknown: true, needPassword }, async ({ page, writes, resume }) => {
    await openEnroll(page); await page.getByRole('button', { name: '重新核验', exact: true }).click(); await page.getByRole('button', { name: '查询原操作' }).click()
    if (needPassword) { await page.getByLabel('重新输入一次性初始密码').fill('synthetic-new-initial'); await page.getByRole('button', { name: '确认同键继续开通' }).click() }
    else await page.getByRole('button', { name: '显式继续原开通' }).click()
    await page.getByText(/原操作已确认完成/).waitFor(); const enrolls = writes.filter(w => w.path.endsWith('/members')); assert(needPassword ? enrolls.length === 2 && enrolls[0].key === enrolls[1].key : enrolls.length === 1 && resume() === 1, 'wrong recovery operation')
    assert(!(await page.evaluate(() => JSON.stringify(sessionStorage))).includes('synthetic-new-initial'), 'secret persisted')
  })
  await scenario('客户授权设备移动端列表、详情刷新与停用说明', { type: 'CUSTOMER', mobile: true, disabled: true }, async ({ page, calls }) => {
    await page.goto(customer + '/devices'); await page.getByRole('heading', { name: '授权设备' }).waitFor(); await page.getByRole('link', { name: '查看设备详情 →' }).click(); await page.getByText('设备已停用，请联系服务商。保留只读档案。').waitFor(); await page.reload(); await page.getByText('设备已停用，请联系服务商。保留只读档案。').waitFor(); assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'mobile overflow'); assert(!await page.getByRole('button', { name: /报修|新建|编辑/ }).count(), 'out of scope action'); assert(!calls.some(c => c.path.includes('product-models')), 'customer model directory queried'); await page.screenshot({ path: resolve(out, 'customer-mobile.png'), fullPage: true })
  })
  await scenario('客户首登改密前不加载资产并成功后重新登录', { type: 'CUSTOMER', restricted: true }, async ({ page, calls }) => {
    await page.goto(customer + '/devices/3'); await page.getByRole('heading', { name: '首次登录，请修改密码' }).waitFor(); assert(!calls.some(c => c.path.startsWith('/api/asset')), 'restricted asset query'); await page.getByLabel('当前密码').fill('synthetic-old-password'); await page.getByLabel('新密码', { exact: true }).fill('synthetic-new-password'); await page.getByLabel('确认新密码').fill('synthetic-new-password'); await page.getByRole('button', { name: '确认改密' }).click(); await page.getByRole('heading', { name: '客户登录' }).waitFor()
  })
  for (const config of [{ type: 'EMPLOYEE', portal: customer }, { type: 'CUSTOMER', portal: staff }, { type: 'PLATFORM', platform: true, portal: customer }]) await scenario(`${config.type}错误入口拒绝业务`, config, async ({ page, calls }) => { await page.goto(config.portal + (config.portal === customer ? '/devices' : '/assets/companies')); await page.getByText(/此账号不适用于/).waitFor(); assert(!calls.some(c => c.path.startsWith('/api/asset')), 'wrong portal fetched assets') })
  await scenario('无能力员工无资产入口和查询', { noCapability: true }, async ({ page, calls }) => { await page.goto(staff + '/assets/companies'); await page.getByRole('heading', { name: '此页面当前不可访问' }).waitFor(); assert(!calls.some(c => c.path.startsWith('/api/asset')), 'no capability query') })
  for (const lateStatus of [200,401,500,502,503]) await scenario(`客户卸载重挂载迟到${lateStatus}不覆盖新组件`, { type: 'CUSTOMER', holdDetail: true, lateStatus }, async ({ page, held }) => {
    await page.goto(customer + '/devices/3'); await page.waitForFunction(() => document.querySelector('#app').__vue_app__?.config.globalProperties.$pinia?._s.get('session')?.phase === 'NORMAL'); while (!held.length) await page.waitForTimeout(20)
    await page.evaluate(() => { const s = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session'); s.phase = 'AUTH_RECOVERY_PENDING' }); await page.getByRole('heading', { name: '请确认当前状态' }).waitFor()
    await page.evaluate(() => { const s = document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session'); s.generation++; s.identity = { ...s.identity, loginAttemptId: 'new-attempt' }; s.phase = 'NORMAL'; s.notice = '新身份提示'; s.traceId = 'new-trace' })
    await page.getByRole('heading', { name: 'D1', exact: true }).waitFor(); held[0](); await page.waitForTimeout(100); assert(!await page.getByRole('heading', { name: 'OLD_RESPONSE' }).isVisible(), 'stale body rendered'); await page.getByRole('heading', { name: 'D1', exact: true }).waitFor(); assert(await page.getByText('新身份提示', { exact: true }).isVisible(), 'stale error altered notice')
  })

  const waitHeld = async (page,held,n) => {const end=Date.now()+5000;while(held.length<n&&Date.now()<end)await page.waitForTimeout(10);assert(held.length>=n,'held request did not arrive')}
  for (const mode of ['write','enrollment','recovery']) for(const lateStatus of [200,401,500,502,503]) await scenario(`员工${mode}卸载恢复中迟到${lateStatus}不改新请求`, {holdWrite:mode==='write',holdEnrollment:mode==='enrollment',holdRecovery:mode==='recovery',enrollUnknown:mode==='recovery',lateStatus}, async ({page,held,writes}) => {
    if(mode==='write') {await page.goto(staff+'/assets/companies/1');await page.getByRole('button',{name:'编辑基本资料'}).click();await page.getByLabel('名称 *',{exact:true}).fill('等待回执');await page.getByRole('button',{name:'保存修改'}).click()}
    else {await openEnroll(page);if(mode==='recovery'){await page.getByRole('button',{name:'重新核验',exact:true}).click();await page.getByRole('button',{name:'查询原操作'}).click()}}
    await waitHeld(page,held,1)
    if(mode!=='recovery')assert(await page.getByRole('button',{name:'查询原操作'}).isDisabled(),'recovery available during original submit')
    await page.evaluate(()=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session');s.phase='AUTH_RECOVERY_PENDING'})
    await page.getByRole('heading',{name:'请确认当前状态'}).waitFor()
    await page.evaluate(()=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session');s.generation++;s.identity={...s.identity,loginAttemptId:'replacement-attempt'};s.phase='NORMAL';s.notice='replacement-notice';s.traceId='replacement-trace'})
    await page.getByRole('heading',{name:'合成企业',exact:true}).waitFor()
    if(mode==='recovery'){await page.getByRole('button',{name:'查询原操作'}).click();await waitHeld(page,held,2)}
    held[0]();await page.waitForTimeout(120)
    const snapshot=await page.evaluate(()=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session');return {phase:s.phase,attempt:s.identity?.loginAttemptId,notice:s.notice,trace:s.traceId}})
    assert(snapshot.phase==='NORMAL'&&snapshot.attempt==='replacement-attempt'&&snapshot.notice==='replacement-notice'&&snapshot.trace==='replacement-trace','old callback mutated new identity')
    assert(!await page.getByRole('heading',{name:'OLD_WRITE_SUCCESS'}).isVisible(),'old body visible')
    if(mode==='recovery'){assert(await page.getByRole('button',{name:'查询原操作'}).isDisabled(),'old finally released new recovery busy');held[1]();await page.getByText(/原操作已确认完成/).waitFor()}
    else assert(await page.getByRole('button',{name:'查询原操作'}).isEnabled(),'unmount left pending permanently locked')
    assert(writes.length===1,'old callback automatically wrote again')
  })

  await scenario('两个入口同源跨标签页身份变化立即停止旧正文',{},async({context,page,calls})=>{
    await page.goto(staff+'/assets/companies');await page.getByRole('heading',{name:'客户企业',exact:true}).waitFor()
    const other=await context.newPage();other.setDefaultTimeout(6000);other.on('dialog',d=>d.accept());await other.goto(customer+'/login');await other.getByText(/此账号不适用于客户门户/).waitFor();await other.getByRole('button',{name:'退出当前实际会话'}).click();await other.getByRole('heading',{name:'客户登录',exact:true}).waitFor()
    await other.getByLabel('租户编码').fill('AA');await other.getByLabel('账号',{exact:true}).fill('fixture.customer');await other.getByLabel('密码',{exact:true}).fill('synthetic-only-password');await other.getByRole('button',{name:'登录',exact:true}).click();await other.getByRole('heading',{name:'授权设备',exact:true}).waitFor()
    await page.getByText(/此账号不适用于员工管理端|当前浏览器身份已变化/).waitFor();assert(!await page.getByRole('heading',{name:'客户企业',exact:true}).isVisible(),'old staff body survived cross-portal login')
    const login=calls.findLastIndex(c=>c.path==='/api/auth/login');assert(calls.slice(login+1).some(c=>c.path==='/api/auth/me'),'no confirmation');await other.close()
  })
  await scenario('刷新后按原UUID恢复开通查询不提交密码',{},async({page,writes})=>{
    await page.goto(staff+'/assets/companies');await page.getByText('按已有操作键查询').click();await page.getByLabel('原操作键',{exact:true}).fill('11111111-2222-4333-8444-555555555555');await page.getByRole('button',{name:'查询已有操作',exact:true}).click();await page.getByRole('button',{name:'显式继续原开通'}).waitFor();assert(writes.length===0,'query automatically resumed');await page.getByRole('button',{name:'显式继续原开通'}).click();await page.getByText(/原操作已确认完成/).waitFor();assert(writes.length===1&&writes[0].path.endsWith('/11111111-2222-4333-8444-555555555555/resume')&&Object.keys(writes[0].body).length===0,'resume changed key or submitted password')
  })
} finally { await browser.close(); await writeFile(resolve(out, 'batch4-ui-results.json'), JSON.stringify({ mode: 'REAL_CHROME_SYNTHETIC_HTTP_NO_DATABASE', results }, null, 2)); console.log(JSON.stringify({ passed: results.filter(r => r.status === 'PASS').length, failed: results.filter(r => r.status !== 'PASS').length })); if (results.some(r => r.status !== 'PASS')) process.exitCode = 1 }
