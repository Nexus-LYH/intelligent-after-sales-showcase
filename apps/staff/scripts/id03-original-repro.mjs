import {chromium} from 'playwright'
import {writeFile} from 'node:fs/promises'
const browser=await chromium.launch({channel:'chrome',headless:true}),results=[]
for(const status of [200,401,503]){
 const context=await browser.newContext(),page=await context.newPage();let logged=false,attempt='',held,entered
 const arrived=new Promise(r=>entered=r),release=new Promise(r=>held=r)
 const identity=()=>({userId:'101',account:'synthetic.admin',displayName:'合成管理员',realm:'TENANT',identityType:'EMPLOYEE',tenant:{id:'1',code:'AA',name:'合成租户'},department:null,permissions:['identity.session.revoke'],accessState:'NORMAL',loginAttemptId:attempt,expiresAt:'2099-01-01T00:00:00Z'})
 await page.route('**/api/**',async route=>{const req=route.request(),path=new URL(req.url()).pathname;const send=(s,b)=>route.fulfill({status:s,contentType:'application/json',body:JSON.stringify(b)})
  if(path==='/api/auth/me')return logged?send(200,identity()):send(401,{code:'SESSION_INVALID'})
  if(path==='/api/auth/csrf')return send(200,{token:'synthetic',headerName:'X-CSRF-TOKEN',contextKind:logged?'AUTH':'PREAUTH',...(logged?{loginAttemptId:attempt}:{}),expiresAt:'2099-01-01T00:00:00Z'})
  if(path==='/api/auth/login'){attempt=req.headers()['x-login-attempt-id'];logged=true;return send(200,identity())}
  if(path.endsWith('/sessions/revoke')){entered();await release;return status===200?send(200,{operationId:'700',targetUserId:'102',newSecurityVersion:'2',revokedAt:'2026-10-15T01:00:00Z',scope:'ALL_SESSIONS'}):send(status,{code:status===401?'SESSION_INVALID':'OPERATION_RESULT_UNKNOWN',message:'合成迟到回执'})}
  if(path.startsWith('/api/identity/users/'))return send(200,{id:'102',account:'synthetic.target',displayName:'合成目标',identityType:'EMPLOYEE',status:1,mustChangePassword:false,bindingState:'NOT_APPLICABLE',securityVersion:'1',updatedAt:'2026-10-15T01:00:00Z'})
  return send(403,{code:'ACCESS_DENIED'})
 })
 try{
  await page.goto('http://127.0.0.1:5173/staff/login');await page.getByLabel('租户编码').fill('AA');await page.getByLabel('账号',{exact:true}).fill('synthetic.admin');await page.getByLabel('密码',{exact:true}).fill('synthetic-not-a-real-secret');await page.getByRole('button',{name:'登录',exact:true}).click();await page.getByRole('heading',{name:'本人账户',exact:true}).waitFor()
  await page.goto('http://127.0.0.1:5173/staff/users/102');await page.getByRole('button',{name:'撤销全部会话'}).click();await page.getByLabel('撤权原因').fill('旧代际合成撤权回执');await page.getByRole('button',{name:'确认撤权',exact:true}).click();await arrived
  const before=await page.evaluate(async()=>{const {useSession}=await import('/staff/src/session.ts');const s=useSession();s.generation++;s.identity={...s.identity,userId:'999',account:'synthetic.new',loginAttemptId:'new-attempt'};s.csrf={...s.csrf,loginAttemptId:'new-attempt'};s.revocationPending={id:'103',account:'synthetic.newtarget',reason:'新代际未知操作',version:'1',key:'new-pending-key',actor:'999'};return {generation:s.generation,phase:s.phase,actor:s.identity.userId,key:s.revocationPending.key}})
  held();await page.waitForTimeout(500)
  const after=await page.evaluate(async()=>{const {useSession}=await import('/staff/src/session.ts');const s=useSession();return {generation:s.generation,phase:s.phase,actor:s.identity?.userId??null,key:s.revocationPending?.key??null}})
  results.push({responseStatus:status,mode:'REAL_CHROME_SYNTHETIC_HTTP_NO_DATABASE',before,after,status:after.actor===before.actor&&after.key===before.key&&after.phase===before.phase?'PASS':'FAIL'})
 }catch{results.push({responseStatus:status,status:'HARNESS_ERROR'})}finally{held();await context.close()}
}
await browser.close();await writeFile(process.env.IAS_ID03_RESULT ?? new URL('../runtime/id03-original-repro.json',import.meta.url),JSON.stringify(results,null,2));console.log(JSON.stringify(results))

if(results.some(r=>r.status!=='PASS'))process.exitCode=1
