// Real Chrome + synthetic HTTP only. Not a backend/DB acceptance result.
import { chromium } from 'playwright'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { createHash } from 'node:crypto'
const out=process.env.IAS_ID05_EVIDENCE || resolve('runtime/id05-red'), results=[]
await mkdir(out,{recursive:true});const browser=await chromium.launch({channel:'chrome',headless:true})
const key='11111111-2222-4333-8444-555555555555'
const device={id:'1',code:'DEVICE',name:'测试设备',status:'ENABLED',rowVersion:'1',ownershipVersion:'1',companyId:'2',modelId:'3',serialNumber:'SN'}
const summary={id:'7',ticketNo:'T-7',title:'设备无法正常启动',status:'PENDING_ACCEPTANCE',rowVersion:'1',submittedAt:'2026-10-16T01:00:00Z',deviceSnapshot:{...device},companySnapshot:{id:'2',name:'测试企业',code:'C2'}}
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jF1sAAAAASUVORK5CYII=','base64')
const file={fileId:'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',uploadId:'bbbbbbbb-bbbb-4ccc-8ddd-eeeeeeeeeeee',name:'test.png',mediaType:'image/png',size:png.length,sha256:createHash('sha256').update(png).digest('hex'),state:'READY'}
const assert=(v,m)=>{if(!v)throw Error(m)}
async function fixture(extra, employee=false){
 const ctx=await browser.newContext(),page=await ctx.newPage();page.setDefaultTimeout(7000);page.on('dialog',d=>d.accept())
 const writes=[];const me={userId:'101',account:'fixture',displayName:'测试客户',realm:'TENANT',identityType:employee?'EMPLOYEE':'CUSTOMER',tenant:{id:'1',code:'AA',name:'测试租户'},department:null,permissions:employee?['ticket.read']:[],accessState:'NORMAL',loginAttemptId:'attempt',expiresAt:'2099-01-01T00:00:00Z'}
 await page.route('**/api/**',async route=>{
  const req=route.request(),url=new URL(req.url()),path=url.pathname,method=req.method();const send=(status,body)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)})
  if(method!=='GET')writes.push({path,key:req.headers()['idempotency-key'],body:method==='POST'&&!path.endsWith('/files')?req.postDataJSON():null})
  if(await extra?.({route,req,url,path,method,send,writes}))return
  if(path==='/api/auth/me')return send(200,me)
  if(path==='/api/auth/csrf')return send(200,{token:'synthetic',headerName:'X-CSRF-TOKEN',contextKind:'AUTH',loginAttemptId:me.loginAttemptId,expiresAt:me.expiresAt})
  if(path==='/api/asset/devices/1')return send(200,device)
  if(path==='/api/asset/me/customer-companies')return send(200,{items:[{id:'2',code:'C2',name:'测试企业',status:'ENABLED'}]})
  if(path==='/api/ticket/duplicate-candidates')return send(200,{items:[]})
  if(path==='/api/ticket/tickets'&&method==='GET')return send(200,{items:[summary],total:1,page:1,size:20})
  if(path==='/api/ticket/tickets/7')return send(200,{...summary,description:'连续三次开机均无法正常启动，指示灯持续闪烁。',categoryCode:'UNCERTAIN',impact:'AFFECTED',contactName:'联系人',contactPhone:'123',timeline:[{type:'SUBMITTED',at:summary.submittedAt}],attachments:[file]})
  return send(404,{code:'OPERATION_NOT_FOUND',message:'not observed'})
 });return{ctx,page,writes}
}
async function test(name,fn){try{await fn();results.push({name,status:'PASS'})}catch(e){results.push({name,status:'FAIL',failure:e.message.split('\n')[0]})}console.log(JSON.stringify(results.at(-1)));await writeFile(resolve(out,'id05-ui-results.json'),JSON.stringify({mode:'REAL_CHROME_SYNTHETIC_HTTP_NO_DATABASE',results},null,2))}
async function form(page){await page.goto('http://127.0.0.1:5173/customer/repairs/new?deviceId=1');await page.getByLabel('报修标题',{exact:true}).fill(summary.title);await page.getByLabel('故障描述',{exact:true}).fill('连续三次开机均无法正常启动，电源指示灯持续闪烁。');await page.getByLabel('联系人',{exact:true}).fill('联系人');await page.getByLabel('联系电话',{exact:true}).fill('123456')}

await test('ID05:首次上传未送达→原查询404→显式原键同文件重传',async()=>{
 let uploads=0,original,metadata
 const f=await fixture(async({path,method,req,route,send})=>{
  if(path.includes('/file-uploads/')){await send(404,{code:'TARGET_NOT_ACCESSIBLE',message:'本次未观察到可访问上传'});return true}
  if(path==='/api/ticket/files'&&method==='POST'){
   uploads++;const data=req.postDataBuffer().toString('latin1'),field=name=>data.match(new RegExp('name="'+name+'"\\r\\n\\r\\n([^\\r]+)'))?.[1]
   const current={key:req.headers()['idempotency-key'],submissionId:field('submissionId'),deviceId:field('deviceId'),version:field('expectedOwnershipVersion'),sha256:field('sha256'),filePart:data.slice(data.indexOf('\r\n\r\n',data.indexOf('name="file"'))+4,data.lastIndexOf('\r\n--'))}
   if(uploads===1){original=current;await route.abort()}else{assert(JSON.stringify(original)===JSON.stringify(current),'upload UUID/body/File parameters changed');metadata={...file,uploadId:current.key,submissionId:current.submissionId};await send(201,metadata)}return true
  }
 })
 try{await form(f.page);await f.page.getByLabel('选择附件',{exact:true}).setInputFiles({name:'test.png',mimeType:'image/png',buffer:png});await f.page.getByRole('button',{name:'显式上传/原键重试',exact:true}).click();await f.page.getByRole('button',{name:'重新核验',exact:true}).click();await f.page.getByText('test.png · UNKNOWN',{exact:false}).waitFor();const reply=f.page.waitForResponse(r=>new URL(r.url()).pathname.includes('/file-uploads/'));await f.page.getByRole('button',{name:'查询原上传',exact:true}).click();assert((await reply).status()===404,'not 404');await f.page.waitForTimeout(50);const retry=f.page.getByRole('button',{name:'显式原键同文件重传',exact:true});assert(await retry.count()===1&&await retry.isEnabled(),'ID05: no explicit original-key same-file replay after 404');assert(uploads===1,'automatic replay');await retry.click();await f.page.getByText('test.png · READY',{exact:false}).waitFor();assert(uploads===2,'unexpected replay count');assert(new URL(f.page.url()).searchParams.get('submissionId')===original.submissionId,'submission changed')}finally{await f.ctx.close()}
})

for(const status of [401,403,503,404]) await test(`原上传查询${status}拒绝不得授予404恢复许可`,async()=>{
 let posts=0,queries=0
 const f=await fixture(async({path,method,route,send})=>{if(path==='/api/ticket/files'&&method==='POST'){posts++;await route.abort();return true}if(path.includes('/file-uploads/')){queries++;await send(queries===1?404:status,{code:queries===1?'TARGET_NOT_ACCESSIBLE':status===404?'ROUTE_NOT_FOUND':'DENIED'});return true}})
 try{await form(f.page);await f.page.getByLabel('选择附件',{exact:true}).setInputFiles({name:'test.png',mimeType:'image/png',buffer:png});await f.page.getByRole('button',{name:'显式上传/原键重试',exact:true}).click();await f.page.getByRole('button',{name:'重新核验',exact:true}).click();await f.page.getByRole('button',{name:'查询原上传',exact:true}).click();await f.page.getByRole('button',{name:'显式原键同文件重传',exact:true}).waitFor();await f.page.getByRole('button',{name:'查询原上传',exact:true}).click();if([403,503].includes(status)){await f.page.getByRole('button',{name:'重新核验',exact:true}).click();await f.page.getByText('test.png · UNKNOWN',{exact:false}).waitFor()}else if(status===401)await f.page.getByRole('heading',{name:'客户登录',exact:true}).waitFor();else await f.page.getByText('test.png · UNKNOWN',{exact:false}).waitFor();assert(!await f.page.getByRole('button',{name:'显式原键同文件重传',exact:true}).count(),'denial kept replay permission');assert(posts===1,'denial caused replay')}finally{await f.ctx.close()}
})
for(const action of ['query','retry'])for(const transition of ['identity-switch','remount-new-request'])for(const status of (action==='query'?[200,401,403,404,503]:[200,401,403,503]))await test(`ID05 ${action}/${transition}/late-${status}`,async()=>{
 let first=true,queries=0,original;const held=[]
 const f=await fixture(async({path,method,req,route,send})=>{
  if(path==='/api/ticket/files'&&method==='POST'){
   const data=req.postDataBuffer().toString('latin1'),field=name=>data.match(new RegExp('name="'+name+'"\\r\\n\\r\\n([^\\r]+)'))?.[1];original={...file,uploadId:req.headers()['idempotency-key'],submissionId:field('submissionId')}
   if(first){first=false;await route.abort();return true}
  }else if(path.includes('/file-uploads/')){if(++queries===1){await send(404,{code:'TARGET_NOT_ACCESSIBLE'});return true}}else return false
  let release;const index=held.length;const gate=new Promise(r=>release=r);held.push(release);await gate
  if(index===0&&status!==200)await send(status,{code:status===404?'TARGET_NOT_ACCESSIBLE':'LATE_RESULT',message:'late'})
  else await send(200,original)
  return true
 })
 const waitHeld=async n=>{const until=Date.now()+7000;while(held.length<n&&Date.now()<until)await f.page.waitForTimeout(10);assert(held.length>=n,'request not held')}
 try{await form(f.page);await f.page.getByLabel('选择附件',{exact:true}).setInputFiles({name:'test.png',mimeType:'image/png',buffer:png});await f.page.getByRole('button',{name:'显式上传/原键重试',exact:true}).click();await f.page.getByRole('button',{name:'重新核验',exact:true}).click();await f.page.getByRole('button',{name:'查询原上传',exact:true}).click();await f.page.getByRole('button',{name:'显式原键同文件重传',exact:true}).waitFor();await f.page.getByRole('button',{name:action==='retry'?'显式原键同文件重传':'查询原上传',exact:true}).click();await waitHeld(1)
  await f.page.evaluate(()=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session');s.phase='AUTH_RECOVERY_PENDING'})
  await f.page.getByRole('heading',{name:'请确认当前状态',exact:true}).waitFor()
  await f.page.evaluate(transition=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session');s.generation++;s.identity={...s.identity,loginAttemptId:'next-attempt',...(transition==='identity-switch'?{userId:'999',account:'other'}:{})};s.csrf={...s.csrf,loginAttemptId:'next-attempt'};s.phase='NORMAL';s.notice='current-notice';s.traceId='current-trace'},transition)
  await f.page.getByRole('heading',{name:'提交报修',exact:true}).waitFor()
  if(transition==='remount-new-request'){await f.page.getByRole('button',{name:action==='query'?'显式原键同文件重传':'查询原上传',exact:true}).click();await waitHeld(2)}
  const snapshot=()=>f.page.evaluate(()=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session');return JSON.stringify([s.phase,s.generation,s.identity?.userId,s.identity?.loginAttemptId,s.notice,s.traceId,location.href])})
  const before=await snapshot();held[0]();await f.page.waitForTimeout(80);assert(before===await snapshot(),'old callback changed current identity/navigation')
  if(transition==='remount-new-request'){assert(await f.page.getByRole('button',{name:'查询原上传',exact:true}).isDisabled(),'old finally unlocked current query');assert(!await f.page.getByText('test.png · READY',{exact:false}).count(),'old callback published READY');if(action==='query')assert(!await f.page.getByText('test.png · NOT_OBSERVED',{exact:false}).count(),'late404 reset current upload');held[1]();await f.page.getByText('test.png · READY',{exact:false}).waitFor();assert(await f.page.getByRole('button',{name:'查询原上传',exact:true}).isEnabled(),'current query stuck')}
  assert(f.writes.filter(x=>x.path==='/api/ticket/files').length===((action==='retry'||transition==='remount-new-request')?2:1),'automatic write or replacement key')
 }finally{for(const release of held)release();await f.ctx.close()}
})
await browser.close();console.log(JSON.stringify({passed:results.filter(x=>x.status==='PASS').length,failed:results.filter(x=>x.status!=='PASS').length}));if(results.some(x=>x.status!=='PASS'))process.exitCode=1

