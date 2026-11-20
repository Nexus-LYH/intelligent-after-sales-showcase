// Actual Chrome/Vue with synthetic HTTP + explicit Pinia lifecycle fixtures.
// Exercises REGISTERED/RESUME and AWAITING_INITIAL_PASSWORD; no DB or real secrets.
import { chromium } from 'playwright'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
const out = process.env.IAS_ID04_EVIDENCE ?? resolve('runtime/id04')
await mkdir(out, { recursive: true })
const browser = await chromium.launch({ channel: 'chrome', headless: true }), results = []
const key = '11111111-2222-4333-8444-555555555555'
const assert = (v, m) => { if (!v) throw new Error(m) }
for (const action of ['query','resume','password']) for (const transition of ['identity-switch','remount-new-request']) for (const status of [200,401,503]) {
  const context = await browser.newContext(), page = await context.newPage(), held = [], writes = []
  page.setDefaultTimeout(6000); page.on('dialog', d => d.accept())
  let queries = 0
  const me = { userId:'101',account:'fixture',displayName:'Synthetic',realm:'TENANT',identityType:'EMPLOYEE',tenant:{id:'1',code:'AA',name:'Synthetic'},department:null,permissions:['asset.read','asset.manage','identity.customer.provision'],accessState:'NORMAL',loginAttemptId:'fixture-attempt',expiresAt:'2099-01-01T00:00:00Z' }
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname, method = req.method()
    const send = (code, data) => route.fulfill({ status:code,contentType:'application/json',body:JSON.stringify(data) })
    if (path === '/api/auth/me') return send(200, me)
    if (path === '/api/auth/csrf') return send(200, { token:'synthetic',headerName:'X-CSRF-TOKEN',contextKind:'AUTH',loginAttemptId:me.loginAttemptId,expiresAt:me.expiresAt })
    if (path === '/api/asset/customer-companies/1') return send(200, { id:'1',code:'C1',name:'Synthetic company',status:'ENABLED',rowVersion:'1',updatedAt:'' })
    if (method === 'GET' && path.endsWith('/members')) return send(200, { items:[],total:0,page:1,size:20 })
    const operation = { operationId:key,state:action === 'password' ? 'AWAITING_INITIAL_PASSWORD' : 'REGISTERED',nextAction:action === 'password' ? 'ENTER_INITIAL_PASSWORD' : 'RESUME',companyId:'1',account:'synthetic.customer',displayName:'Customer' }
    if (path === '/api/asset/customer-enrollments/' + key && method === 'GET') {
      queries++
      if (action !== 'query' && queries === 1) return send(200, operation)
    } else if (method === 'POST' && (path.endsWith('/resume') || path.endsWith('/members'))) {
      const body = req.postDataJSON(); writes.push({ path,key:req.headers()['idempotency-key'],fields:Object.keys(body) })
      assert(action === 'resume' ? Object.keys(body).length === 0 : req.headers()['idempotency-key'] === key, 'wrong explicit recovery payload/key')
    } else return send(403, { code:'ACCESS_DENIED' })
    const index = held.length; let release; const gate = new Promise(r => release = r); held.push(release); await gate
    if (index === 0) return status === 200 ? send(200, { ...operation,state:'COMPLETED',nextAction:'NONE',userId:'777' }) : send(status, { code:'LATE_RESULT',message:'Old response' })
    return send(200, { ...operation,state:'REGISTERED',nextAction:'RESUME' })
  })
  async function waitHeld(n) { const end=Date.now()+6000;while(held.length<n&&Date.now()<end)await page.waitForTimeout(10);assert(held.length>=n,'request was not held') }
  try {
    await page.goto('http://127.0.0.1:5173/staff/assets/companies/1')
    await page.getByText('按已有操作键查询', {exact:true}).click(); await page.getByLabel('原操作键',{exact:true}).fill(key); await page.getByRole('button',{name:'查询已有操作',exact:true}).click()
    if (action === 'resume') { await page.getByRole('button',{name:'显式继续原开通',exact:true}).click() }
    if (action === 'password') { await page.getByLabel('重新输入一次性初始密码').fill('synthetic-only-password'); await page.getByRole('button',{name:'确认同键继续开通',exact:true}).click(); assert(!await page.getByLabel('重新输入一次性初始密码').count() || await page.getByLabel('重新输入一次性初始密码').inputValue() === '', 'submitted password not cleared') }
    await waitHeld(1)
    await page.evaluate(() => { const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session');s.phase='AUTH_RECOVERY_PENDING' })
    await page.getByRole('heading',{name:'请确认当前状态',exact:true}).waitFor()
    await page.evaluate(transition => { const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session');s.generation++;s.identity={...s.identity,loginAttemptId:'new-attempt',...(transition==='identity-switch'?{userId:'999',account:'new.actor'}:{})};s.phase='NORMAL';s.notice='new-notice';s.traceId='new-trace' }, transition)
    await page.getByRole('heading',{name:'Synthetic company',exact:true}).waitFor()
    if (transition === 'remount-new-request') { await page.getByRole('button',{name:'查询原操作',exact:true}).click(); await waitHeld(2) }
    const snapshot = () => page.evaluate(() => { const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session');return {g:s.generation,actor:s.identity?.userId,attempt:s.identity?.loginAttemptId,phase:s.phase,notice:s.notice,trace:s.traceId,path:location.pathname} })
    const before = await snapshot(); held[0](); await page.waitForTimeout(100)
    assert(JSON.stringify(before) === JSON.stringify(await snapshot()), 'old callback changed new identity/notice/navigation')
    assert(!await page.getByText('当前状态：COMPLETED',{exact:true}).isVisible(), 'old success published')
    if (transition === 'remount-new-request') { assert(await page.getByRole('button',{name:'查询原操作',exact:true}).isDisabled(),'old finally unlocked new request');held[1]();await page.getByRole('button',{name:'显式继续原开通',exact:true}).waitFor();assert(await page.getByRole('button',{name:'显式继续原开通',exact:true}).isEnabled(),'current request did not release owner') }
    assert(writes.length === (action === 'query' ? 0 : 1), 'automatic replay happened')
    assert(!JSON.stringify(await page.evaluate(() => ({...sessionStorage}))).includes('synthetic-only-password'),'secret persisted')
    results.push({action,transition,responseStatus:status,status:'PASS'})
  } catch (e) { results.push({action,transition,responseStatus:status,status:'FAIL',failure:e.message.split('\n')[0]}) }
  finally { for(const release of held)release();await context.close() }
  await writeFile(resolve(out,'id04-callback-results.json'),JSON.stringify({mode:'REAL_CHROME_SYNTHETIC_HTTP_NO_DATABASE',results},null,2));console.log(JSON.stringify(results.at(-1)))
}
await browser.close();console.log(JSON.stringify({passed:results.filter(x=>x.status==='PASS').length,failed:results.filter(x=>x.status!=='PASS').length}));if(results.some(x=>x.status!=='PASS'))process.exitCode=1
