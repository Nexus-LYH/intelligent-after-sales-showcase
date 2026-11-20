// Callback ownership only: actual Vue/Chrome, synthetic HTTP and explicit Pinia
// fixtures. Never connects to DB, reads credentials, or claims real identity switch.
import { chromium } from 'playwright'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
const out=process.env.IAS_ID03_EVIDENCE??resolve('runtime/id03')
await mkdir(out,{recursive:true})
const browser=await chromium.launch({channel:'chrome',headless:true}), results=[]
const assert=(condition,message)=>{if(!condition)throw new Error(message)}
const sleep=ms=>new Promise(r=>setTimeout(r,ms))
for(const variant of ['generation-and-identity','attempt-only','pending-only','unmount-remount-new-operation','unmount-remount-original-operation','unmount-self','route-reuse','current-self'].filter(v=>!process.env.IAS_ID03_VARIANT||v===process.env.IAS_ID03_VARIANT)) {
 for(const status of (variant==='current-self'?[200]:[200,401,503])) {
  const context=await browser.newContext(),page=await context.newPage()
  page.setDefaultTimeout(5000)
  let logged=false,attempt='',writes=0;const held=[],arrivals=[]
  const identity=()=>({userId:'101',account:'synthetic.admin',displayName:'合成管理员',realm:'TENANT',identityType:'EMPLOYEE',tenant:{id:'1',code:'AA',name:'合成租户'},department:null,permissions:['identity.session.revoke'],accessState:'NORMAL',loginAttemptId:attempt,expiresAt:'2099-01-01T00:00:00Z'})
  const target=variant==='unmount-self'||variant==='current-self'?'101':'102'
  await page.route('**/api/**',async route=>{
   const req=route.request(),path=new URL(req.url()).pathname
   const send=(code,body)=>route.fulfill({status:code,contentType:'application/json',body:JSON.stringify(body)})
   if(path==='/api/auth/me')return logged?send(200,identity()):send(401,{code:'SESSION_INVALID'})
   if(path==='/api/auth/csrf')return send(200,{token:'synthetic-memory',headerName:'X-CSRF-TOKEN',contextKind:logged?'AUTH':'PREAUTH',...(logged?{loginAttemptId:attempt}:{}),expiresAt:'2099-01-01T00:00:00Z'})
   if(path==='/api/auth/login'){attempt=req.headers()['x-login-attempt-id'];logged=true;return send(200,identity())}
   if(path.endsWith('/sessions/revoke')) {
    const index=writes++, gate=new Promise(r=>{held[index]=r});arrivals[index]=true
    await gate
    const code=index===0?status:200
    return code===200?send(200,{operationId:index===0?'700':'800',targetUserId:target,newSecurityVersion:'2',revokedAt:'2026-10-15T02:00:00Z',scope:'ALL_SESSIONS'}):send(code,{code:code===401?'SESSION_INVALID':'OPERATION_RESULT_UNKNOWN',message:'合成迟到回执'})
   }
   if(path.startsWith('/api/identity/users/'))return send(200,{id:path.split('/').at(-1),account:'synthetic.target',displayName:'合成目标',identityType:'EMPLOYEE',status:1,mustChangePassword:false,bindingState:'NOT_APPLICABLE',securityVersion:'1',updatedAt:'2026-10-15T02:00:00Z'})
   return send(403,{code:'ACCESS_DENIED'})
  })
  const waitForWrite=async index=>{const end=Date.now()+5000;while(!arrivals[index]&&Date.now()<end)await sleep(10);assert(arrivals[index],'request never arrived')}
  const snapshot=()=>page.evaluate(async()=>{const {useSession}=await import('/staff/src/session.ts');const s=useSession();return {generation:s.generation,actor:s.identity?.userId,attempt:s.identity?.loginAttemptId,phase:s.phase,key:s.revocationPending?.key,notice:s.notice,trace:s.traceId,busy:s.busy,path:location.pathname}})
  try {
   await page.goto('http://127.0.0.1:5173/staff/login')
   await page.getByLabel('租户编码').fill('AA');await page.getByLabel('账号',{exact:true}).fill('synthetic.admin');await page.getByLabel('密码',{exact:true}).fill('synthetic-not-a-real-secret')
   await page.getByRole('button',{name:'登录',exact:true}).click();await page.getByRole('heading',{name:'本人账户',exact:true}).waitFor()
   await page.goto('http://127.0.0.1:5173/staff/users/'+target)
   await page.getByRole('button',{name:'撤销全部会话'}).click();await page.getByLabel('撤权原因').fill('旧请求原始合成撤权')
   await page.getByRole('button',{name:'确认撤权',exact:true}).click();await waitForWrite(0)
   if(variant.startsWith('unmount')) {
    await page.evaluate(async()=>{const {useSession}=await import('/staff/src/session.ts');useSession().phase='AUTH_RECOVERY_PENDING'})
    await page.getByRole('heading',{name:'账号详情与会话撤权'}).waitFor({state:'hidden'})
    await page.evaluate(async({variant})=>{const {useSession}=await import('/staff/src/session.ts');const s=useSession();if(variant==='unmount-remount-new-operation')s.revocationPending={...s.revocationPending,key:'new-pending-key',reason:'新组件明确操作'};if(variant!=='unmount-self')s.phase='NORMAL';s.notice='new-flow-notice';s.traceId='new-flow-trace'}, {variant})
    if(variant!=='unmount-self') {
     await page.getByRole('button',{name:'使用原键核对恢复',exact:true}).waitFor()
     assert(writes===1,'remount automatically resent write')
     await page.getByRole('button',{name:'使用原键核对恢复',exact:true}).click();await waitForWrite(1)
    } else await page.getByRole('heading',{name:'请确认当前状态',exact:true}).waitFor()
   } else if(variant==='route-reuse') {
    // Fixture forces component reuse past its normal busy-leave UI protection.
    // This is not a production navigation/permission bypass.
    await page.evaluate(async()=>{const {router}=await import('/staff/src/router.ts');const {useSession}=await import('/staff/src/session.ts');const s=useSession();s.revocationPending={...s.revocationPending,id:'103',key:'new-route-key'};for(const record of router.currentRoute.value.matched)record.leaveGuards.clear();await router.push('/users/103');s.notice='new-flow-notice';s.traceId='new-flow-trace'})
    await page.getByRole('button',{name:'撤销全部会话',exact:true}).waitFor()
   } else if(variant!=='current-self') {
    await page.evaluate(async variant=>{const {useSession}=await import('/staff/src/session.ts');const s=useSession();if(variant==='generation-and-identity'){s.generation++;s.identity={...s.identity,userId:'999',account:'new.actor',loginAttemptId:'new-attempt'};s.revocationPending={...s.revocationPending,key:'new-pending-key',actor:'999'}}if(variant==='attempt-only')s.identity={...s.identity,loginAttemptId:'same-actor-new-attempt'};if(variant==='pending-only')s.revocationPending={...s.revocationPending,key:'new-pending-key'};s.notice='new-flow-notice';s.traceId='new-flow-trace';s.busy=true},variant)
   }
   const before=await snapshot()
   let localBusy
   if(variant.includes('remount'))localBusy=await page.getByRole('button',{name:'使用原键核对恢复',exact:true}).isDisabled()
   held[0]();await page.waitForTimeout(120)
   if(variant==='current-self')await page.getByRole('heading',{name:'员工登录',exact:true}).waitFor()
   const after=await snapshot()
   if(variant==='current-self')assert(after.phase==='ANONYMOUS'&&!after.actor&&!after.key&&after.path==='/staff/login','owned self revocation did not complete navigation')
   else assert(JSON.stringify(before)===JSON.stringify(after),'old callback changed newer state/operation/navigation')
   assert(!await page.getByText(/撤权成功 · 操作 700/).isVisible(),'old success rendered')
   if(variant.includes('remount')) {
    assert(localBusy&&await page.getByRole('button',{name:'使用原键核对恢复',exact:true}).isDisabled(),'old finally unlocked replacement request')
    assert(writes===2,'write auto replayed')
    held[1]();await page.getByText(/撤权成功 · 操作 800/).waitFor()
    const latest=await snapshot();assert(!latest.key,'current request could not complete its own pending')
   } else assert(writes===1,'old callback started another write')
   results.push({variant,responseStatus:status,mode:'REAL_CHROME_SYNTHETIC_HTTP_NO_DATABASE',before,after,status:'PASS'})
  } catch(e) {results.push({variant,responseStatus:status,status:'FAIL',failure:e.message.split('\n')[0]});await page.screenshot({path:resolve(out,`${variant}-${status}-failure.png`)}).catch(()=>{})}
  finally{for(const release of held)release?.();await context.close()}
  await writeFile(resolve(out,'callback-browser-results.json'),JSON.stringify(results,null,2))
  console.log(JSON.stringify(results.at(-1)))
 }
}
await browser.close()
await writeFile(resolve(out,'callback-browser-results.json'),JSON.stringify(results,null,2))
console.log(JSON.stringify({passed:results.filter(r=>r.status==='PASS').length,failed:results.filter(r=>r.status!=='PASS').length}))
if(results.some(r=>r.status!=='PASS'))process.exitCode=1
