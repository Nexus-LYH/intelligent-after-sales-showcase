// Real Chrome + real /api through 5173. No fixtures, SQL, credentials in output, traces or screenshots.
import { chromium } from 'playwright'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { randomBytes, createHash } from 'node:crypto'
import { resolve } from 'node:path'
import { spawnSync } from 'node:child_process'
const base = 'http://127.0.0.1:5173/staff'
const plan = JSON.parse(await readFile(resolve('../../services/identity/bootstrap/local-accounts.json'), 'utf8'))
const secretDir = process.env.IAS_BROWSER_SECRET_DIR ?? 'C:/ias-local/Develop/IAS-MySQL84/secrets'
const originals = JSON.parse(await readFile(resolve(secretDir,'identity-local-passwords.json'), 'utf8'))
const privateStatePath = resolve(secretDir,'frontend-browser-passwords.json')
if (privateStatePath.toLowerCase().startsWith(resolve('../..').toLowerCase())) throw new Error('Browser secrets must remain outside the workspace')
if (process.platform === 'win32') {
  // Protect THIS role's file before writing any generated secret; do not change parent or backend files.
  const acl = spawnSync('powershell.exe', ['-NoProfile','-NonInteractive','-Command', "$ErrorActionPreference='Stop'; $target=$env:IAS_FRONTEND_SECRET_FILE; if(!(Test-Path -LiteralPath $target)){[IO.File]::WriteAllText($target,'{}')}; $acl=[Security.AccessControl.FileSecurity]::new($target,[Security.AccessControl.AccessControlSections]::Access); $acl.SetAccessRuleProtection($true,$false); foreach($rule in @($acl.Access)){[void]$acl.RemoveAccessRuleSpecific($rule)}; $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.WindowsIdentity]::GetCurrent().User,'FullControl','Allow')); $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new([Security.Principal.SecurityIdentifier]::new('S-1-5-18'),'FullControl','Allow')); [IO.File]::SetAccessControl($target,$acl)"], { env: { ...process.env, IAS_FRONTEND_SECRET_FILE: privateStatePath }, stdio: 'pipe' })
  if (acl.status !== 0) throw new Error(`Cannot protect browser-owned secret file: ${acl.error?.code ?? acl.stderr?.toString()}`)
}
let passwords = {}; try { passwords = JSON.parse(await readFile(privateStatePath,'utf8')) } catch(e) { if(e.code!=='ENOENT') throw e }
const evidenceDir = process.env.IAS_BROWSER_EVIDENCE ?? resolve('runtime/browser')
await mkdir(evidenceDir,{recursive:true})
const results=[], calls=[], browser=await chromium.launch({channel:'chrome',headless:true})
const contexts=[]
const ensure=(value,label)=>{if(!value)throw new Error(label)}
async function test(name, action) { try { await action(); results.push({name,status:'PASS'}) } catch (e) { results.push({name,status:'FAIL',failureKind:e.message?.split('\n')[0]}); throw new Error(`Browser assertion failed: ${name}`) } }
const hash=v=>createHash('sha256').update(v).digest('hex')
async function newPage(){const context=await browser.newContext({viewport:{width:1280,height:900}});contexts.push(context);const page=await context.newPage();page.on('response', r=>{const u=new URL(r.url());if(u.pathname.startsWith('/api/'))calls.push({path:u.pathname,method:r.request().method(),status:r.status()})});page.on('dialog',d=>d.accept());return page}
async function login(page, account, password) {
  await page.goto(base+(account.realm==='PLATFORM'?'/platform/login':'/login'))
  await page.getByLabel('账号',{exact:true}).waitFor()
  if(account.realm==='TENANT')await page.getByLabel('租户编码').fill(account.tenantCode)
  await page.getByLabel('账号',{exact:true}).fill(account.account)
  await page.getByLabel('密码',{exact:true}).fill(password)
  let before, after
  const listener=async response=>{const path=new URL(response.url()).pathname;if(response.status()===200&&(path==='/api/auth/login'||path==='/api/auth/me')){const header=await response.headerValue('set-cookie');const value=header?.match(/IAS-SESSION-DEV=([^;]+)/)?.[1];if(value&&path==='/api/auth/login')before=hash(value);if(value&&path==='/api/auth/me'&&!after)after=hash(value)}}
  page.on('response',listener)
  await page.getByRole('button',{name:'登录',exact:true}).click()
  await page.getByRole('heading',{name:/本人账户|首次登录，请修改密码/}).waitFor({timeout:15000})
  page.off('response',listener)
  return {before,after}
}
async function readyAccount(ref) {
  const account=plan.users.find(u=>u.passwordRef===ref);ensure(account,'planned account missing')
  const page=await newPage();let password=passwords[ref]??originals[ref]
  const confirmation=await login(page,account,password)
  if(await page.getByRole('heading',{name:'首次登录，请修改密码'}).isVisible()){
    await test(`${ref}:强改密拒绝管理深链接`,async()=>{await page.goto(base+'/users');await page.getByRole('heading',{name:'首次登录，请修改密码'}).waitFor();ensure(!(await page.getByRole('heading',{name:'租户用户'}).isVisible()),'restricted list leaked')})
    const next=randomBytes(24).toString('base64url')
    await test(`${ref}:错误当前密码拒绝且清输入`,async()=>{await page.getByLabel('当前密码',{exact:true}).fill('deliberately-wrong-not-a-real-credential');await page.getByLabel('新密码',{exact:true}).fill(next);await page.getByLabel('确认新密码',{exact:true}).fill(next);await page.getByRole('button',{name:'确认改密',exact:true}).click();await page.getByText(/CURRENT_PASSWORD_INVALID/).waitFor();ensure(await page.getByLabel('当前密码',{exact:true}).inputValue()==='','password input not cleared');ensure(await page.getByRole('heading',{name:'首次登录，请修改密码'}).isVisible(),'restriction escaped')})
    await page.getByLabel('当前密码',{exact:true}).fill(password)
    await page.getByLabel('新密码',{exact:true}).fill(next)
    await page.getByLabel('确认新密码',{exact:true}).fill(next)
    await page.getByRole('button',{name:'确认改密',exact:true}).click()
    await page.getByRole('heading',{name:/员工登录|平台管理员登录/}).waitFor()
    passwords[ref]=next;await writeFile(privateStatePath,JSON.stringify(passwords),{mode:0o600})
    password=next
    await login(page,account,password)
    ensure(await page.getByRole('heading',{name:'本人账户',exact:true}).isVisible(),'password did not require new login')
  }
  ensure(confirmation.before&&confirmation.after&&confirmation.before!==confirmation.after,'first confirmation did not replace cookie')
  return {page,account,password}
}
try {
  const admin=await readyAccount('tenantA1'), target=await readyAccount('employeeE')
  let targetId
  await test('真实首次确认Cookie更新及HttpOnly属性',async()=>{const cookies=await admin.page.context().cookies();const c=cookies.find(c=>c.name==='IAS-SESSION-DEV');ensure(c?.httpOnly&&c.sameSite==='Lax'&&c.path==='/','cookie attributes invalid');const documentCookie=await admin.page.evaluate(()=>document.cookie);ensure(!documentCookie.includes('IAS-SESSION-DEV'),'HttpOnly exposed');const methods=calls.filter(c=>c.path.startsWith('/api/auth/'));const loginAt=methods.findIndex(c=>c.path==='/api/auth/login');ensure(methods[loginAt+1]?.path==='/api/auth/me'&&methods[loginAt+2]?.path==='/api/auth/csrf','control chain not serial')})
  await test('普通员工管理深链接及接口拒绝',async()=>{await target.page.goto(base+'/users');await target.page.getByRole('heading',{name:'此页面当前不可访问'}).waitFor();const status=await target.page.evaluate(async()=> (await fetch('/api/identity/users')).status);ensure(status===403,'ordinary account server permissions bypass')})
  await test('真实列表查询与详情版本',async()=>{await admin.page.getByRole('link',{name:'租户用户',exact:true}).click();await admin.page.getByLabel('账号关键字').fill(target.account.account);const loaded=admin.page.waitForResponse(r=>new URL(r.url()).pathname==='/api/identity/users'&&new URL(r.url()).searchParams.get('keyword')===target.account.account);await admin.page.getByRole('button',{name:'查询',exact:true}).click();await loaded;await admin.page.getByRole('row').filter({hasText:target.account.account}).getByRole('button',{name:'查看详情'}).click();await admin.page.getByRole('heading',{name:'账号详情与会话撤权'}).waitFor();targetId=new URL(admin.page.url()).pathname.split('/').at(-1);await admin.page.getByText(target.account.account,{exact:true}).first().waitFor()})
  await test('真实撤权→另一上下文401→可重新登录',async()=>{await admin.page.getByRole('button',{name:'撤销全部会话'}).click();await admin.page.getByLabel('撤权原因').fill('前端第三批真实浏览器验收');await admin.page.getByRole('button',{name:'确认撤权',exact:true}).click();await admin.page.getByText(/撤权成功 · 操作/).waitFor();await target.page.goto(base+'/account');await target.page.getByRole('heading',{name:'员工登录',exact:true}).waitFor();await login(target.page,target.account,target.password)})
  await test('自愿改密清会话并显式重登',async()=>{await target.page.getByRole('link',{name:'修改密码',exact:true}).click();const next=randomBytes(24).toString('base64url');await target.page.getByLabel('当前密码',{exact:true}).fill(target.password);await target.page.getByLabel('新密码',{exact:true}).fill(next);await target.page.getByLabel('确认新密码',{exact:true}).fill(next);await target.page.getByRole('button',{name:'确认改密',exact:true}).click();await target.page.getByRole('heading',{name:'员工登录',exact:true}).waitFor();passwords.employeeE=next;await writeFile(privateStatePath,JSON.stringify(passwords),{mode:0o600});await login(target.page,target.account,next)})
  const platform=await readyAccount('platformP')
  await test('平台独立登录与平台账号范围',async()=>{await platform.page.getByRole('link',{name:'平台账号',exact:true}).click();await platform.page.getByRole('heading',{name:'平台账号',exact:true}).waitFor();await platform.page.getByLabel('账号关键字').fill('tenant.admin');const loaded=platform.page.waitForResponse(r=>new URL(r.url()).pathname==='/api/identity/users');await platform.page.getByRole('button',{name:'查询',exact:true}).click();await loaded;ensure(!(await platform.page.getByText('tenant.admin1',{exact:true}).isVisible()),'platform read tenant user')})
  await test('平台不能查询租户详情',async()=>{const status=await platform.page.evaluate(async id=>(await fetch('/api/identity/users/'+id)).status,targetId);ensure(status===404,'platform namespace bypass')})
  await test('平台管理员自撤后独立入口重登',async()=>{await platform.page.getByLabel('账号关键字').fill(platform.account.account);await platform.page.getByRole('button',{name:'查询',exact:true}).click();await platform.page.getByRole('button',{name:'查看详情'}).first().click();await platform.page.getByRole('button',{name:'撤销全部会话'}).click();await platform.page.getByLabel('撤权原因').fill('平台本人成员会话验收');await platform.page.getByRole('button',{name:'确认撤权',exact:true}).click();await platform.page.getByRole('heading',{name:'平台管理员登录',exact:true}).waitFor();await login(platform.page,platform.account,platform.password)})
  await test('深链接刷新与1280布局',async()=>{await admin.page.reload();await admin.page.getByRole('button',{name:'撤销全部会话'}).waitFor();ensure(await admin.page.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth),'full page overflow');await admin.page.screenshot({path:resolve(evidenceDir,'staff-user-detail.png'),fullPage:true})})
  await test('真实提交后503回执丢失以原键恢复不重增版本',async()=>{
    const received=[];let original
    const pattern='**/api/identity/users/'+targetId+'/sessions/revoke'
    await admin.page.route(pattern,async route=>{received.push({key:route.request().headers()['idempotency-key'],body:route.request().postData()});const response=await route.fetch();const body=await response.json();if(received.length===1&&response.status()===200){original=body;await route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'OPERATION_RESULT_UNKNOWN',message:'受控模拟：真实提交已发生，回执未确认'})})}else{ensure(body.operationId===original.operationId&&body.newSecurityVersion===original.newSecurityVersion,'idempotent result changed');await route.fulfill({response})}})
    try{await admin.page.getByRole('button',{name:'撤销全部会话'}).click();await admin.page.getByLabel('撤权原因').fill('真实提交回执未知恢复验收');await admin.page.getByRole('button',{name:'确认撤权',exact:true}).click();await admin.page.getByRole('button',{name:'重新核验'}).waitFor();ensure(!await admin.page.getByRole('heading',{name:'账号详情与会话撤权'}).isVisible(),'old body visible');await admin.page.getByRole('button',{name:'重新核验'}).click();await admin.page.getByRole('button',{name:'使用原键核对恢复'}).click();await admin.page.getByText(/撤权成功 · 操作/).waitFor();ensure(received.length===2&&received[0].key===received[1].key&&received[0].body===received[1].body,'changed replay key or input')}finally{await admin.page.unroute(pattern)}
  })
  await test('真实409版本冲突刷新后需重新确认',async()=>{
    const second=await newPage();await login(second,admin.account,passwords.tenantA1??originals.tenantA1);await second.goto(base+'/users/'+targetId);await second.getByRole('button',{name:'撤销全部会话'}).click();await second.getByLabel('撤权原因').fill('另一会话先行撤权验证');await second.getByRole('button',{name:'确认撤权',exact:true}).click();await second.getByText(/撤权成功 · 操作/).waitFor();await admin.page.getByRole('button',{name:'撤销全部会话'}).click();await admin.page.getByLabel('撤权原因').fill('陈旧版本应被明确拒绝');await admin.page.getByRole('button',{name:'确认撤权',exact:true}).click();await admin.page.getByText(/目标版本已变化/).waitFor();await admin.page.getByRole('button',{name:'确认撤权',exact:true}).waitFor({state:'hidden'});await second.context().close()
  })
  await test('退出后不恢复旧正文',async()=>{await admin.page.getByRole('button',{name:'退出当前会话'}).click();await admin.page.getByRole('heading',{name:'员工登录',exact:true}).waitFor();await admin.page.goto(base+'/users');await admin.page.getByRole('heading',{name:'员工登录',exact:true}).waitFor()})
} catch (e) {
  if (!results.some(r => r.status === 'FAIL')) results.push({name:'真实账号登录/改密准备链',status:'FAIL',failureKind:e.message?.split('\n')[0]})
  throw new Error('Real browser flow incomplete; see sanitized result file')
} finally {
  for(const context of contexts)await context.close()
  await browser.close()
  const passed=results.filter(r=>r.status==='PASS').length
  await writeFile(resolve(evidenceDir,'real-browser-results.json'),JSON.stringify({timestamp:new Date().toISOString(),mode:'REAL_CHROME_REAL_IDENTITY',origin:base,passed,results,calls},null,2))
  console.log(JSON.stringify({passed,failed:results.filter(r=>r.status==='FAIL').length,evidence:'real-browser-results.json'}))
}
