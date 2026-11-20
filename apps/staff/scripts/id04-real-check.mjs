// Real Chrome -> 5173 -> gateway -> real identity/asset. No SQL/fault injection.
// Backend/test supplies registered operations and the original actor's non-secret references.
import { chromium } from 'playwright'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { randomBytes } from 'node:crypto'
import { resolve } from 'node:path'
const out = process.env.IAS_ID04_EVIDENCE ?? resolve('runtime/id04')
const inputPath = process.env.IAS_ID04_FIXTURES
if (!inputPath) throw new Error('IAS_ID04_FIXTURES must reference the coordinated non-secret fixture manifest')
const fixture = JSON.parse(await readFile(inputPath, 'utf8'))
if (!fixture.actor || !Array.isArray(fixture.cases) || !fixture.cases.length) throw new Error('Fixture metadata missing')
const privatePath = resolve(process.env.IAS_BROWSER_SECRET_DIR ?? 'C:/ias-local/Develop/IAS-MySQL84/secrets','frontend-browser-passwords.json')
const privateState = JSON.parse(await readFile(privatePath,'utf8'))
const readSecret = async (file, ref) => { const values = JSON.parse(await readFile(file,'utf8')); if(typeof values[ref] !== 'string')throw new Error('Referenced secret is unavailable');return values[ref] }
const browser = await chromium.launch({channel:'chrome',headless:true}), results=[], observations=[]
await mkdir(out,{recursive:true})
const assert=(v,m)=>{if(!v)throw new Error(m)}
const root='http://127.0.0.1:5173'
async function login(page, actor, password, portal='staff') {
  await page.goto(`${root}/${portal}/login`);await page.getByLabel('租户编码').fill(actor.tenantCode);await page.getByLabel('账号',{exact:true}).fill(actor.account);await page.getByLabel('密码',{exact:true}).fill(password);await page.getByRole('button',{name:'登录',exact:true}).click();await page.getByRole('heading',{name:/本人账户|首次登录，请修改密码|授权设备/}).waitFor()
}
async function json(page,path,options={}) { return page.evaluate(async({path,options})=>{const r=await fetch(path,{credentials:'same-origin',cache:'no-store',...options});return {status:r.status,body:await r.json().catch(()=>null)}},{path,options}) }
for (const sample of fixture.cases) {
  const actor=sample.actor??fixture.actor, context=await browser.newContext(), page=await context.newPage(), calls=[]
  page.setDefaultTimeout(15000);page.on('dialog',d=>d.accept());page.on('request',r=>{const u=new URL(r.url());if(u.pathname.startsWith('/api/'))calls.push({path:u.pathname,method:r.method(),key:r.headers()['idempotency-key']})})
  const label=sample.kind, operation=`/api/asset/customer-enrollments/${sample.operationId}`, company=`/api/asset/customer-companies/${sample.companyId}`
  let customerContext
  try {
    await login(page,actor,await readSecret(actor.passwordFile,actor.passwordRef))
    const auth=await json(page,'/api/auth/me');assert(auth.status===200&&auth.body.account===actor.account,'actor does not match fixture')
    await page.goto(`${root}/staff/assets/companies/${sample.companyId}`);await page.getByRole('heading',{name:'基本资料',exact:true}).waitFor()
    await page.getByText('按已有操作键查询',{exact:true}).click();await page.getByLabel('原操作键',{exact:true}).fill(sample.operationId)
    if (sample.kind==='permission-lost') {
      assert(!auth.body.permissions.includes('identity.customer.provision')||!auth.body.permissions.includes('asset.manage'),'fixture actor still has provision permissions')
      const pending=page.waitForResponse(r=>new URL(r.url()).pathname===operation&&r.request().method()==='GET');await page.getByRole('button',{name:'查询已有操作',exact:true}).click();assert((await pending).status()===403,'lost capability GET accepted')
      assert(!await page.getByRole('button',{name:'显式继续原开通',exact:true}).isVisible(),'unauthorized resume button')
      const csrf=await json(page,'/api/auth/csrf');assert(csrf.status===200,'csrf unavailable')
      const denial=await json(page,operation+'/resume',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf.body.token},body:'{}'});assert(denial.status===403,'lost capability resume accepted')
      observations.push({kind:label,operationId:sample.operationId,actorId:auth.body.userId,getStatus:403,resumeStatus:denial.status})
      results.push({name:label,status:'PASS'});continue
    }
    const users=async()=>{const r=await json(page,'/api/identity/users?keyword='+encodeURIComponent(sample.account)+'&page=1&size=100');assert(r.status===200,'identity count lookup unavailable');return r.body.items.filter(u=>u.account===sample.account)}
    const members=async()=>{const r=await json(page,company+'/members?page=1&size=100');assert(r.status===200,'membership lookup unavailable');const all=[...r.body.items];for(let n=2;all.length<r.body.total;n++){const next=await json(page,company+`/members?page=${n}&size=100`);assert(next.status===200&&next.body.items.length,'membership pagination failed');all.push(...next.body.items)}return all}
    const initialUsers=await users(), initialMembers=await members(), getSnapshots=[]
    assert(initialUsers.length===(sample.kind==='existing-pending'?1:0),'fixture identity existence differs from expected branch')
    for(let n=0;n<3;n++) {
      const waiting=page.waitForResponse(r=>new URL(r.url()).pathname===operation&&r.request().method()==='GET')
      await page.getByRole('button',{name:n===0?'查询已有操作':'查询原操作',exact:true}).click();const response=await waiting,body=await response.json()
      assert(response.status()===200&&body.state==='REGISTERED'&&body.nextAction==='RESUME','REGISTERED must expose RESUME without progressing GET')
      getSnapshots.push({state:body.state,nextAction:body.nextAction,userId:body.userId??null})
    }
    assert(JSON.stringify(getSnapshots[0])===JSON.stringify(getSnapshots[2]),'GET changed operation state')
    assert((await users()).length===initialUsers.length&&(await members()).length===initialMembers.length,'GET created account/member')
    assert(!calls.some(c=>c.method==='POST'&&c.path.startsWith('/api/asset/')),'GET path automatically advanced operation')
    assert(!await page.getByLabel('重新输入一次性初始密码').count(),'REGISTERED requested password before explicit resume')
    const resumeResponse=page.waitForResponse(r=>new URL(r.url()).pathname===operation+'/resume'&&r.request().method()==='POST')
    await page.getByRole('button',{name:'显式继续原开通',exact:true}).click();const response=await resumeResponse, resumed=await response.json();assert(response.status()===200&&resumed.operationId===sample.operationId,'explicit resume failed or changed operation')
    let initialPassword
    if (sample.kind==='identity-absent') {
      assert(resumed.state==='AWAITING_INITIAL_PASSWORD'&&resumed.nextAction==='ENTER_INITIAL_PASSWORD','no-identity resume must ask for explicit initial password')
      assert((await users()).length===0&&(await members()).length===initialMembers.length,'password-free resume created account/member')
      const ref=`customer:${actor.tenantCode}:${sample.account}`;initialPassword=randomBytes(24).toString('base64url');privateState[ref]=initialPassword;await writeFile(privatePath,JSON.stringify(privateState,null,2))
      await page.getByLabel('重新输入一次性初始密码').fill(initialPassword)
      const creation=page.waitForResponse(r=>new URL(r.url()).pathname===company+'/members'&&r.request().method()==='POST');await page.getByRole('button',{name:'确认同键继续开通',exact:true}).click();const reply=await creation,body=await reply.json()
      assert(reply.status()===200&&reply.request().headers()['idempotency-key']===sample.operationId&&body.operationId===sample.operationId&&body.state==='COMPLETED','same-key password continuation failed')
    } else {
      assert(resumed.state==='COMPLETED','existing PENDING did not complete without password')
      initialPassword=await readSecret(sample.initialPasswordFile,sample.initialPasswordRef)
      assert(!calls.some(c=>c.method==='POST'&&c.path.endsWith('/members')),'existing PENDING resubmitted a password')
    }
    await page.getByText(/原操作已确认完成/).waitFor()
    const finalUsers=await users();assert(finalUsers.length===1,'duplicate identity account')
    const userId=finalUsers[0].id, finalMembers=(await members()).filter(m=>m.userId===userId)
    assert(finalMembers.length===1&&finalMembers[0].state==='ACTIVE','duplicate or inactive membership')
    const csrf=await json(page,'/api/auth/csrf');assert(csrf.status===200,'csrf unavailable')
    // Explicit API replays supplement the actual button flow after UI completion hides the action.
    for(let n=0;n<2;n++){const replay=await json(page,operation+'/resume',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf.body.token},body:'{}'});assert(replay.status===200&&replay.body.operationId===sample.operationId&&replay.body.state==='COMPLETED','completed operation replay failed')}
    assert((await users()).length===1&&(await members()).filter(m=>m.userId===userId).length===1,'explicit replay duplicated account/member')
    customerContext=await browser.newContext();const customerPage=await customerContext.newPage();customerPage.setDefaultTimeout(15000)
    await login(customerPage,{tenantCode:actor.tenantCode,account:sample.account},initialPassword,'customer');await customerPage.getByRole('heading',{name:'首次登录，请修改密码',exact:true}).waitFor()
    // Do not change this account's password: original one remains valid and first-login restriction remains enforced.
    assert(!JSON.stringify(await page.evaluate(()=>({...sessionStorage}))).includes(initialPassword),'password persisted in browser storage')
    observations.push({kind:label,operationId:sample.operationId,actorId:auth.body.userId,companyId:sample.companyId,account:sample.account,userId,memberId:finalMembers[0].id,getSnapshots,identityCount:1,memberCount:1,originalInitialPasswordStillValid:true,calls})
    await page.screenshot({path:resolve(out,`id04-${label}.png`),fullPage:true});results.push({name:label,status:'PASS'})
  } catch(e) {results.push({name:label,status:'FAIL',failure:e.message.split('\n')[0]})}
  finally {if(customerContext)await customerContext.close();await context.close();await writeFile(resolve(out,'id04-real-results.json'),JSON.stringify({mode:'REAL_CHROME_GATEWAY_IDENTITY_ASSET_MYSQL_NO_FRONTEND_SQL',results,observations},null,2));console.log(JSON.stringify(results.at(-1)))}
}
await browser.close();console.log(JSON.stringify({passed:results.filter(r=>r.status==='PASS').length,failed:results.filter(r=>r.status!=='PASS').length}));if(results.some(r=>r.status!=='PASS'))process.exitCode=1
