// Run only after BE-0's real service/artifact/account/window handover. No API fixtures or database writes.
if(process.env.IAS_PN_RUNTIME_CONFIRMED!=='1'||!process.env.IAS_PN_NOTIFICATION_BROWSER_CONFIG||!process.env.IAS_PN_NOTIFICATION_EVIDENCE)throw Error('REAL_NOTIFICATION_HANDOVER_REQUIRED')
import {chromium} from 'playwright'
import {readFile,writeFile,mkdir} from 'node:fs/promises'
import {resolve} from 'node:path'
const config=JSON.parse(await readFile(process.env.IAS_PN_NOTIFICATION_BROWSER_CONFIG,'utf8'))
const uuid=x=>typeof x==='string'&&/^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/.test(x)
const assert=(condition,code)=>{if(!condition)throw Error(code)}
const base=config.baseUrl||'http://127.0.0.1:5173'
assert(/^http:\/\/127\.0\.0\.1:(5173|5174|5175)$/.test(base),'UNAPPROVED_BROWSER_ORIGIN')
assert(config.actor&&typeof config.actor.tenantCode==='string'&&typeof config.actor.account==='string'&&typeof config.actor.savedRef==='string','ACTOR_REFERENCE_REQUIRED')
assert(config.messages&&['normal','abort','lost'].every(k=>uuid(config.messages[k])),'THREE_NEW_UNREAD_MESSAGE_IDS_REQUIRED')
const secretPath=resolve(config.secretPath||'C:/ias-local/Develop/IAS-MySQL84/secrets/frontend-browser-passwords.json')
assert(secretPath.toLowerCase().startsWith(resolve('C:/ias-local/Develop/IAS-MySQL84/secrets').toLowerCase()+'\\'),'RESTRICTED_SECRET_REFERENCE_REQUIRED')
const saved=JSON.parse(await readFile(secretPath,'utf8')),password=saved[config.actor.savedRef]
assert(typeof password==='string'&&password.length>0,'CURRENT_PASSWORD_REFERENCE_UNAVAILABLE')
const out=resolve(process.env.IAS_PN_NOTIFICATION_EVIDENCE);await mkdir(out,{recursive:true})
const results=[],facts=[],calls=[]
const browser=await chromium.launch({channel:'chrome',headless:true,args:['--disable-gpu','--renderer-process-limit=1']})
const context=await browser.newContext({viewport:{width:1280,height:950}}),page=await context.newPage();page.setDefaultTimeout(25000)
page.on('response',r=>{const path=new URL(r.url()).pathname;if(path.startsWith('/api/'))calls.push({path,method:r.request().method(),status:r.status()})})
async function flush(){await writeFile(resolve(out,'通知真实浏览器结果.json'),JSON.stringify({layer:'REAL_BROWSER_REAL_HTTP_SERVICES',results,facts,calls,limitations:['No SQL, password reset, permission/bootstrap/config/service changes.','Only read-mark HTTP request/response faults are injected; no database failure is claimed.','Independent database uniqueness and independent acceptance remain separate.']},null,2))}
async function test(name,run){try{await run();results.push({name,status:'PASS'})}catch(error){results.push({name,status:'FAIL',failureType:error instanceof Error?error.message.split('\n')[0]:'UNKNOWN'});throw Error('REAL_NOTIFICATION_CHECK_FAILED')}finally{await flush()}}
async function detail(id){await page.goto(base+'/staff/notifications/'+id);await page.getByRole('heading',{name:'本人站内信',exact:true}).waitFor();await page.locator('article').waitFor();await page.getByRole('button',{name:'标记已读',exact:true}).waitFor();assert(await page.getByRole('button',{name:'标记已读',exact:true}).isEnabled(),'NEW_MESSAGE_ALREADY_READ_OR_UNKNOWN')}
async function currentApi(path){return page.evaluate(async path=>{const response=await fetch('/api'+path,{cache:'no-store',credentials:'same-origin'});return {status:response.status,body:await response.json()}},path)}
try{
 await test('真实账号登录及Cookie首次确认',async()=>{
  let issued,confirmed;const sequence=[]
  const observe=async r=>{const path=new URL(r.url()).pathname;if(!path.startsWith('/api/auth/'))return;sequence.push({path,status:r.status()});if(r.status()!==200)return;const cookie=await r.headerValue('set-cookie'),value=cookie?.match(/IAS-SESSION-DEV=([^;]+)/)?.[1];if(path.endsWith('/login'))issued=value;if(path.endsWith('/me')&&value&&!confirmed)confirmed=value}
  page.on('response',observe);await page.goto(base+'/staff/login');await page.getByLabel('租户编码',{exact:true}).fill(config.actor.tenantCode);await page.getByLabel('账号',{exact:true}).fill(config.actor.account);await page.getByLabel('密码',{exact:true}).fill(password);await page.getByRole('button',{name:'登录',exact:true}).click();await page.getByRole('heading',{name:'本人账户',exact:true}).waitFor();page.off('response',observe)
  const me=sequence.findIndex(x=>x.path.endsWith('/me')&&x.status===200);assert(issued&&confirmed&&issued!==confirmed,'FIRST_CONFIRMATION_COOKIE_NOT_ROTATED');assert(me>=0&&sequence.some((x,i)=>i>me&&x.path.endsWith('/csrf')&&x.status===200),'ME_CSRF_NOT_SERIAL')
  facts.push({cookieRotated:true,serialMeCsrf:true})
 })
 await test('当前收件列表到关联申请及正常已读',async()=>{
  const listed=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/notification/messages'&&r.status()===200);await page.goto(base+'/staff/notifications');await page.getByRole('heading',{name:'本人站内信',exact:true}).waitFor();await listed
  await detail(config.messages.normal);const response=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname==='/api/notification/messages/'+config.messages.normal+'/read');await page.getByRole('button',{name:'标记已读',exact:true}).click();assert((await response).status()===200,'NORMAL_READ_NOT_COMMITTED');await page.getByText('已读',{exact:true}).waitFor()
  const href=await page.getByRole('link',{name:'查看当前备件申请',exact:true}).getAttribute('href');assert(/^\/staff\/approvals\/parts\/[0-9a-f-]{36}$/.test(href||''),'APPLICATION_LINK_NOT_CURRENT_LOCAL_ROUTE');await page.getByRole('link',{name:'查看当前备件申请',exact:true}).click();await page.getByRole('heading',{name:'备件申请与审批',exact:true}).waitFor();await page.getByRole('region',{name:'申请详情',exact:true}).waitFor().catch(()=>page.locator('[aria-label="申请详情"]').waitFor())
  facts.push({messageId:config.messages.normal,currentObjectNavigation:true});await page.screenshot({path:resolve(out,'当前关联申请.png'),fullPage:true})
 })
 for(const fault of ['abort','lost'])await test(fault==='abort'?'未送达后原UUID查询404并显式同键重交':'消费已提交后丢回执仅查询原UUID',async()=>{
  const id=config.messages[fault];await detail(id);const path='/api/notification/messages/'+id+'/read',writes=[];let intercepted=false
  await page.route('**'+path,async route=>{const request=route.request();if(request.method()!=='POST')return route.continue();writes.push({operationId:request.headers()['idempotency-key'],body:request.postData()});if(intercepted)return route.continue();intercepted=true;if(fault==='abort')return route.abort();const actual=await route.fetch();assert(actual.status()===200,'REAL_READ_DID_NOT_COMMIT');return route.fulfill({status:503,contentType:'application/json',body:JSON.stringify({code:'OPERATION_RESULT_UNKNOWN'})})})
  try{
   await page.getByRole('button',{name:'标记已读',exact:true}).click();await page.getByRole('button',{name:'查询原操作',exact:true}).waitFor();assert(writes.length===1&&uuid(writes[0].operationId)&&writes[0].body==='{}','ORIGINAL_READ_UUID_OR_BODY_INVALID')
   await page.getByRole('button',{name:'查询原操作',exact:true}).click()
   if(fault==='abort'){await page.getByRole('button',{name:'确认原操作重新标记',exact:true}).waitFor();assert(writes.length===1,'AUTOMATIC_READ_RETRY');const response=page.waitForResponse(r=>r.request().method()==='POST'&&r.request().headers()['idempotency-key']===writes[0].operationId);await page.getByRole('button',{name:'确认原操作重新标记',exact:true}).click();assert((await response).status()===200,'EXPLICIT_READ_RETRY_NOT_COMMITTED');assert(writes.length===2&&writes[0].operationId===writes[1].operationId&&writes[0].body===writes[1].body,'READ_UUID_OR_BODY_CHANGED')}
   else assert(writes.length===1,'COMMITTED_READ_WAS_REPOSTED')
   await page.getByText('已读',{exact:true}).waitFor();const before=(await currentApi('/notification/messages/'+id)).body,receipt=await currentApi('/notification/read-operations/'+writes[0].operationId),after=(await currentApi('/notification/messages/'+id)).body
   assert(receipt.status===200&&receipt.body.operationId===writes[0].operationId&&receipt.body.messageId===id&&receipt.body.state==='READ','ORIGINAL_READ_RECEIPT_INVALID');assert(before.read&&after.read&&before.readAt===after.readAt,'QUERY_CHANGED_READ_FACT')
   facts.push({fault,messageId:id,operationId:writes[0].operationId,postCount:writes.length,sameOriginalBody:true,queryDidNotChangeReadAt:true});await page.screenshot({path:resolve(out,'原标记恢复-'+fault+'.png'),fullPage:true})
  }finally{await page.unroute('**'+path)}
 })
}finally{await flush();await context.close();await browser.close()}
console.log(JSON.stringify({cases:results.length,passed:results.filter(x=>x.status==='PASS').length,output:out}))
