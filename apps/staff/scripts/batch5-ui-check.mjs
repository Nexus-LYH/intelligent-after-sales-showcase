// Real Chrome + synthetic HTTP only. Not a backend/DB acceptance result.
import { chromium } from 'playwright'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { createHash } from 'node:crypto'
const out=process.env.IAS_BATCH5_EVIDENCE || resolve('runtime/batch5'), results=[]
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
async function test(name,fn){try{await fn();results.push({name,status:'PASS'})}catch(e){results.push({name,status:'FAIL',failure:e.message.split('\n')[0]})}console.log(JSON.stringify(results.at(-1)));await writeFile(resolve(out,'batch5-ui-results.json'),JSON.stringify({mode:'REAL_CHROME_SYNTHETIC_HTTP_NO_DATABASE',results},null,2))}
async function form(page){await page.goto('http://127.0.0.1:5173/customer/repairs/new?deviceId=1');await page.getByLabel('报修标题',{exact:true}).fill(summary.title);await page.getByLabel('故障描述',{exact:true}).fill('连续三次开机均无法正常启动，电源指示灯持续闪烁。');await page.getByLabel('联系人',{exact:true}).fill('联系人');await page.getByLabel('联系电话',{exact:true}).fill('123456')}
await test('空附件真实UI提交/本人列表详情/无处理动作',async()=>{let created;const f=await fixture(async({path,method,send,req})=>{if(path==='/api/ticket/tickets'&&method==='POST'){created=req.headers()['idempotency-key'];await send(201,{operationId:created,ticketId:'7',ticketNo:'T-7',status:'PENDING_ACCEPTANCE',nextAction:'VIEW_TICKET'});return true}if(path.includes('/create-operations/')){await send(200,{operationId:created,state:'COMPLETED',nextAction:'VIEW_TICKET',ticket:{ticketId:'7'}});return true}});try{await form(f.page);await f.page.getByRole('button',{name:'提交报修',exact:true}).click();await f.page.getByRole('heading',{name:'报修提交结果',exact:true}).waitFor();await f.page.getByText('报修已提交，待受理。',{exact:true}).waitFor();await f.page.getByRole('link',{name:'查看待受理报修'}).click();await f.page.getByRole('heading',{name:'报修详情'}).waitFor();assert(f.writes.filter(x=>x.path==='/api/ticket/tickets').length===1,'unexpected duplicate create');assert(!await f.page.getByRole('button',{name:/受理|派工|维修完成/}).count(),'scope expansion');const b=f.writes.find(x=>x.path==='/api/ticket/tickets').body;assert(b.attachmentIds.length===0&&!('tenantId'in b),'bad body');await f.page.screenshot({path:resolve(out,'synthetic-detail.png'),fullPage:true})}finally{await f.ctx.close()}})
await test('提交丢回执后404仅显式原UUID原参重交',async()=>{let created,calls=0;const f=await fixture(async({path,method,send,req,route})=>{if(path==='/api/ticket/tickets'&&method==='POST'){created=req.headers()['idempotency-key'];calls++;if(calls===1)await route.abort();else await send(201,{operationId:created,ticketId:'7',nextAction:'VIEW_TICKET'});return true}});try{await form(f.page);await f.page.getByRole('button',{name:'提交报修',exact:true}).click();await f.page.getByRole('button',{name:'重新核验',exact:true}).click();await f.page.getByRole('button',{name:'显式原UUID、原参数重交'}).waitFor();assert(calls===1,'auto replay');await f.page.getByRole('button',{name:'显式原UUID、原参数重交'}).click();await f.page.getByText('报修已提交，待受理。',{exact:true}).waitFor();const writes=f.writes.filter(x=>x.path==='/api/ticket/tickets');assert(writes.length===2&&writes[0].key===writes[1].key&&JSON.stringify(writes[0].body)===JSON.stringify(writes[1].body),'new key or changed original body')}finally{await f.ctx.close()}})
await test('刷新未知无原正文不提供重建写入',async()=>{const f=await fixture();try{await f.page.goto('http://127.0.0.1:5173/customer/submissions/'+key);await f.page.getByText('当前内存中没有原始参数',{exact:false}).waitFor();assert(!await f.page.getByRole('button',{name:'显式原UUID、原参数重交'}).count(),'recreated body');assert(f.writes.length===0,'GET advanced operation')}finally{await f.ctx.close()}})
await test('授权预览核对真实字节摘要、到期撤销object URL',async()=>{
 const f=await fixture(async({path,route,send})=>{if(path.endsWith('/download-authorizations')){await send(200,{authorizationId:key,expiresAt:new Date(Date.now()+2000).toISOString(),url:`/api/ticket/files/${file.fileId}/content?authorizationId=${key}`});return true}if(path.endsWith('/content')){await route.fulfill({status:200,contentType:'image/png',body:png});return true}})
 try{await f.page.goto('http://127.0.0.1:5173/customer/tickets/7');await f.page.getByRole('button',{name:'授权预览'}).click();await f.page.locator('img[src^="blob:"]').waitFor();await f.page.locator('img[src^="blob:"]').waitFor({state:'hidden'});assert(f.writes.filter(x=>x.path.endsWith('/download-authorizations')).length===1,'background authorization renewal')}finally{await f.ctx.close()}
})
for(const recovery of ['READY','FAILED']) await test(`上传未知保留原File/UUID，查询${recovery}后显式恢复和移除不锁死`,async()=>{
 let uploaded,uploads=0,submission,originalUpload
 const f=await fixture(async({path,method,req,send,route})=>{
  if(path==='/api/ticket/files'&&method==='POST'){
   const data=req.postDataBuffer().toString('latin1'),field=name=>data.match(new RegExp('name="'+name+'"\\r\\n\\r\\n([^\\r]+)'))?.[1]
   assert(data.indexOf('name="submissionId"')<data.indexOf('name="file"'),'file part before metadata')
   uploaded={...file,uploadId:req.headers()['idempotency-key'],submissionId:field('submissionId'),sha256:field('sha256'),size:11}
   if(!uploads++){originalUpload=uploaded.uploadId;submission=uploaded.submissionId;await route.abort()}else{assert(uploaded.uploadId===originalUpload&&uploaded.submissionId===submission,'retry changed keys');await send(201,uploaded)}return true
  }
  if(path.includes('/file-uploads/')){await send(200,{...uploaded,state:recovery,nextAction:recovery==='FAILED'?'RETRY':'NONE'});return true}
  if(method==='DELETE'){await send(200,{fileId:file.fileId,state:'DELETED'});return true}
 })
 try{await form(f.page);await f.page.getByLabel('选择附件',{exact:true}).setInputFiles({name:'test.png',mimeType:'image/png',buffer:Buffer.from('png-fixture')});await f.page.getByRole('button',{name:'显式上传/原键重试'}).click();await f.page.getByRole('button',{name:'重新核验',exact:true}).click();await f.page.getByText('test.png · UNKNOWN',{exact:false}).waitFor();assert(new URL(f.page.url()).searchParams.get('submissionId')===submission,'draft submission changed');assert(uploads===1,'automatic reupload');await f.page.getByRole('button',{name:'查询原上传',exact:true}).click();await f.page.getByText(`test.png · ${recovery}`,{exact:false}).waitFor();if(recovery==='FAILED'){await f.page.getByRole('button',{name:'显式上传/原键重试'}).click();await f.page.getByText('test.png · READY',{exact:false}).waitFor()}await f.page.getByRole('button',{name:'显式移除',exact:true}).click();await f.page.getByText('test.png ·',{exact:false}).waitFor({state:'hidden'});assert(await f.page.getByRole('button',{name:'提交报修',exact:true}).isEnabled(),'delete completion left busy locked');assert(await f.page.getByLabel('报修标题',{exact:true}).inputValue()===summary.title,'original body lost')}finally{await f.ctx.close()}
})
await test('ABORTED仅显式删除旧附件后允许新UUID，不迁移附件',async()=>{
 let uploaded,original
 const f=await fixture(async({path,method,send,req})=>{
  if(path==='/api/ticket/files'&&method==='POST'){const data=req.postDataBuffer().toString('latin1'),field=name=>data.match(new RegExp('name="'+name+'"\\r\\n\\r\\n([^\\r]+)'))?.[1];uploaded={...file,uploadId:req.headers()['idempotency-key'],submissionId:field('submissionId'),sha256:field('sha256'),size:11};await send(201,uploaded);return true}
  if(path==='/api/ticket/tickets'&&method==='POST'){original=req.headers()['idempotency-key'];await send(409,{code:'DUPLICATE_CONFIRMATION_REQUIRED'});return true}
  if(path.includes('/create-operations/')){await send(200,{operationId:original,state:'ABORTED',nextAction:'NONE',reason:'DUPLICATE_CONFIRMATION_REQUIRED',ticket:null});return true}
  if(method==='DELETE'){await send(200,{fileId:file.fileId,state:'DELETED'});return true}
 })
 try{await form(f.page);await f.page.getByLabel('选择附件',{exact:true}).setInputFiles({name:'test.png',mimeType:'image/png',buffer:Buffer.from('png-fixture')});await f.page.getByRole('button',{name:'显式上传/原键重试'}).click();await f.page.getByText('test.png · READY',{exact:false}).waitFor();await f.page.getByRole('button',{name:'提交报修',exact:true}).click();await f.page.getByText('原提交已明确终止',{exact:false}).waitFor();assert(!await f.page.getByRole('link',{name:'明确开始新的报修（新UUID）'}).count(),'new action before file removal');await f.page.getByRole('button',{name:'显式移除旧未引用附件'}).click();await f.page.getByRole('link',{name:'明确开始新的报修（新UUID）'}).click();await f.page.getByLabel('报修标题',{exact:true}).waitFor();assert(new URL(f.page.url()).searchParams.get('submissionId')!==original,'old UUID reused');assert(!await f.page.getByText('test.png ·',{exact:false}).count(),'old files migrated');assert(await f.page.getByRole('button',{name:'提交报修',exact:true}).isEnabled(),'old cleanup finally locked new action')}finally{await f.ctx.close()}
})
for(const action of ['query','resume','create','upload','upload-query','remove','download','download-body'])for(const transition of ['identity-switch','remount-new-request'])for(const status of [200,401,503]){
 await test(`${action}/${transition}/late-${status}`,async()=>{
  const held=[];let queries=0,latestKey=key,uploaded
  const operation={operationId:key,state:'REGISTERED',nextAction:'RESUME',ticket:null}
  const f=await fixture(async({path,method,send,route,req})=>{
   let target=false
   if(path.includes('/create-operations/')){queries++;if(action==='resume'&&method==='GET'&&queries===1){await send(200,operation);return true}target=action==='query'||(action==='resume'&&method==='POST')}
   if(action==='create'&&path==='/api/ticket/tickets'&&method==='POST'){target=true;latestKey=req.headers()['idempotency-key']}
   if(action==='upload'&&path==='/api/ticket/files'&&method==='POST')target=true
   if(['upload-query','remove'].includes(action)&&path==='/api/ticket/files'&&method==='POST'){
    const data=req.postDataBuffer().toString('latin1'),field=name=>data.match(new RegExp('name="'+name+'"\\r\\n\\r\\n([^\\r]+)'))?.[1]
    uploaded={...file,uploadId:req.headers()['idempotency-key'],submissionId:field('submissionId'),sha256:field('sha256'),size:11}
    await send(201,uploaded);return true
   }
   if(action==='upload-query'&&path.includes('/file-uploads/'))target=true
   if(action==='remove'&&method==='DELETE')target=true
   if(action==='download-body'&&path.endsWith('/download-authorizations')){await send(200,{authorizationId:key,expiresAt:new Date(Date.now()+240000).toISOString(),url:`/api/ticket/files/${file.fileId}/content?authorizationId=${key}`});return true}
   if(action==='download-body'&&path.endsWith('/content'))target=true
   if(action==='download'&&path.endsWith('/download-authorizations'))target=true
   if(!target)return false
   const index=held.length;let release;const gate=new Promise(r=>release=r);held.push(release);await gate
   if(index===0&&status!==200)await send(status,{code:'LATE_RESULT',message:'Old response'})
   else if(action==='upload')await send(201,file)
   else if(action==='upload-query')await send(200,uploaded)
   else if(action==='remove')await send(200,{fileId:file.fileId,state:'DELETED'})
   else if(action==='download-body')await route.fulfill({status:200,contentType:'image/png',body:Buffer.from('png-fixture')})
   else if(action==='download'||action==='download-body')await send(200,{authorizationId:key,expiresAt:new Date(Date.now()+240000).toISOString(),url:`/api/ticket/files/${file.fileId}/content?authorizationId=${key}`})
   else await send(200,index===0?{operationId:latestKey,state:'COMPLETED',nextAction:'VIEW_TICKET',ticket:{ticketId:'7'}}:operation)
   return true
  })
  const waitHeld=async n=>{await f.page.waitForFunction(()=>true);const until=Date.now()+7000;while(held.length<n&&Date.now()<until)await f.page.waitForTimeout(10);assert(held.length>=n,'request not held')}
  try{
   if(['create','upload','upload-query','remove'].includes(action)){await form(f.page);if(action==='create')await f.page.getByRole('button',{name:'提交报修',exact:true}).click();else{await f.page.getByLabel('选择附件',{exact:true}).setInputFiles({name:'test.png',mimeType:'image/png',buffer:Buffer.from('png-fixture')});await f.page.getByRole('button',{name:'显式上传/原键重试'}).click();if(['upload-query','remove'].includes(action)){await f.page.getByText('test.png · READY',{exact:false}).waitFor();await f.page.getByRole('button',{name:action==='remove'?'显式移除':'查询原上传',exact:true}).click()}}}
   else if(action==='download'||action==='download-body'){await f.page.goto('http://127.0.0.1:5173/customer/tickets/7');await f.page.getByRole('button',{name:'授权预览'}).click()}
   else {await f.page.goto('http://127.0.0.1:5173/customer/submissions/'+key);if(action==='resume')await f.page.getByRole('button',{name:'显式继续原提交'}).click()}
   await waitHeld(1)
   await f.page.evaluate(()=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session');s.phase='AUTH_RECOVERY_PENDING'})
   await f.page.getByRole('heading',{name:'请确认当前状态',exact:true}).waitFor()
   await f.page.evaluate(transition=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session');s.generation++;s.identity={...s.identity,loginAttemptId:'new-attempt',...(transition==='identity-switch'?{userId:'999',account:'next.actor'}:{})};s.phase='NORMAL';s.notice='new-notice';s.traceId='new-trace'},transition)
   if(transition==='remount-new-request'&&action==='query')await waitHeld(2)
   else if(['create','upload','upload-query','remove'].includes(action))await f.page.getByRole('heading',{name:'提交报修',exact:true}).waitFor()
   else if(action==='download'||action==='download-body')await f.page.getByRole('heading',{name:'报修详情',exact:true}).waitFor()
   else await f.page.getByRole('heading',{name:'报修提交结果',exact:true}).waitFor()
   const snapshot=()=>f.page.evaluate(()=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session');return JSON.stringify([s.generation,s.identity?.userId,s.identity?.loginAttemptId,s.phase,s.notice,s.traceId,location.href])})
   const before=await snapshot();held[0]();await f.page.waitForTimeout(100);assert(before===await snapshot(),'old response changed new identity/navigation');assert(!await f.page.locator('object,img[src^="blob:"]').count(),'old response rebuilt preview')
   if(transition==='remount-new-request'&&action==='query'){assert(await f.page.getByRole('button',{name:'重新查询原结果'}).isDisabled(),'old finally unlocked new query');held[1]();await f.page.getByRole('button',{name:'显式继续原提交'}).waitFor()}
   assert(f.writes.filter(x=>x.path==='/api/ticket/tickets').length===(action==='create'?1:0),'automatic creation replay')
  }finally{for(const release of held)release();await f.ctx.close()}
 })
}
await browser.close();console.log(JSON.stringify({passed:results.filter(x=>x.status==='PASS').length,failed:results.filter(x=>x.status!=='PASS').length}));if(results.some(x=>x.status!=='PASS'))process.exitCode=1
