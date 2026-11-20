// Real permission/session denial through public APIs/UI; no capability SQL modifications.
import { chromium } from 'playwright'
import { readFile, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
const out=process.env.IAS_ID04_EVIDENCE??resolve('runtime/id04'),fixture=JSON.parse(await readFile(resolve(out,'真实样本引用.json'),'utf8'))
const plan=JSON.parse(await readFile(resolve('../../services/identity/bootstrap/local-accounts.json'),'utf8'))
const privateFile=resolve(process.env.IAS_BROWSER_SECRET_DIR??'C:/ias-local/Develop/IAS-MySQL84/secrets','frontend-browser-passwords.json'),saved=JSON.parse(await readFile(privateFile,'utf8'))
const browser=await chromium.launch({channel:'chrome',headless:true}),contexts=[],results=[],observations=[]
const sample=fixture.cases[0],op=`/api/asset/customer-enrollments/${sample.operationId}`,assert=(v,m)=>{if(!v)throw new Error(m)}
async function pageFor(){const context=await browser.newContext();contexts.push(context);const page=await context.newPage();page.setDefaultTimeout(15000);return page}
async function login(page,account,password){await page.goto('http://127.0.0.1:5173/staff/login');await page.getByLabel('租户编码').fill(account.tenantCode);await page.getByLabel('账号',{exact:true}).fill(account.account);await page.getByLabel('密码',{exact:true}).fill(password);await page.getByRole('button',{name:'登录',exact:true}).click();await page.getByRole('heading',{name:'本人账户',exact:true}).waitFor()}
async function request(page,path,options={}){return page.evaluate(async({path,options})=>{const r=await fetch(path,{cache:'no-store',credentials:'same-origin',...options});return {status:r.status,body:await r.json().catch(()=>null)}},{path,options})}
async function test(name,run){try{await run();results.push({name,status:'PASS'})}catch(e){results.push({name,status:'FAIL',failure:e.message.split('\n')[0]})}console.log(JSON.stringify(results.at(-1)))}
try{
 await test('真实无开通能力员工：页面拒绝、原操作GET/resume403',async()=>{
  const employee=plan.users.find(u=>u.passwordRef==='employeeE'),page=await pageFor();await login(page,employee,saved.employeeE)
  const me=await request(page,'/api/auth/me');assert(me.status===200&&!me.body.permissions.includes('identity.customer.provision'),'employee fixture unexpectedly has capability')
  await page.goto('http://127.0.0.1:5173/staff/assets/companies/'+sample.companyId);await page.getByRole('heading',{name:'此页面当前不可访问',exact:true}).waitFor()
  const csrf=await request(page,'/api/auth/csrf'),get=await request(page,op),resume=await request(page,op+'/resume',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf.body.token},body:'{}'})
  assert(get.status===403&&resume.status===403,'unprivileged history recovery accepted');observations.push({name:'unprivileged',userId:me.body.userId,get:get.status,resume:resume.status})
 })
 await test('原actor真实自撤后旧会话GET/resume401且旧正文关闭',async()=>{
  const page=await pageFor();await login(page,fixture.actor,saved[fixture.actor.passwordRef])
  const me=await request(page,'/api/auth/me'),csrf=await request(page,'/api/auth/csrf');assert(me.status===200&&csrf.status===200,'actor unavailable')
  await page.goto('http://127.0.0.1:5173/staff/users/'+me.body.userId);await page.getByRole('button',{name:'撤销全部会话',exact:true}).click();await page.getByLabel('撤权原因').fill('ID04原操作恢复失效会话拒绝验证');await page.getByRole('button',{name:'确认撤权',exact:true}).click();await page.getByRole('heading',{name:'员工登录',exact:true}).waitFor()
  const get=await request(page,op),resume=await request(page,op+'/resume',{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf.body.token},body:'{}'});assert(get.status===401&&resume.status===401,'revoked history recovery accepted')
  assert(!await page.getByText('待核对的提交',{exact:true}).isVisible(),'old recovery body retained')
  // Re-login preserves existing password/capabilities and permits only the original actor's operation.
  await login(page,fixture.actor,saved[fixture.actor.passwordRef]);const current=await request(page,op);assert(current.status===200&&current.body.state==='COMPLETED','new authorized login cannot query original result')
  const users=await request(page,'/api/identity/users?keyword='+encodeURIComponent(sample.account)+'&size=100'),members=await request(page,`/api/asset/customer-companies/${sample.companyId}/members?size=100`)
  const matched=users.body.items.filter(u=>u.account===sample.account);assert(matched.length===1&&members.body.items.filter(m=>m.userId===matched[0].id).length===1,'denied calls duplicated resources')
  observations.push({name:'revoked-session',actorId:me.body.userId,get:401,resume:401,reloginGet:current.status,identityCount:1,memberCount:1})
 })
}finally{for(const context of contexts)await context.close();await browser.close();await writeFile(resolve(out,'id04-authorization-results.json'),JSON.stringify({mode:'REAL_CHROME_GATEWAY_IDENTITY_ASSET_MYSQL_NO_SQL',results,observations,limits:'403 uses a real employee without provision capability; runtime removal of the original actor capability is verified separately by backend ID04 integration, not performed by this frontend script.'},null,2));if(results.some(r=>r.status!=='PASS'))process.exitCode=1}
