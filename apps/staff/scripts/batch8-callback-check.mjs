// Real browser, controlled HTTP/lifecycle/identity fixtures, no backend claims.
import {mkdir,writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
process.env.IAS_BATCH8_FIXTURE_ONLY='1'
const {setup,db8,addRequest,receipt,request,reply,replyForm,pick,open,browser,assert}=await import('./batch8-ui-check.mjs')
const out=process.env.IAS_BATCH8_EVIDENCE||resolve('runtime/batch8-callbacks'),results=[];await mkdir(out,{recursive:true})
async function test(name,fn){if(process.env.IAS_BATCH8_CASE&&!name.includes(process.env.IAS_BATCH8_CASE))return;try{await fn();results.push({name,status:'PASS'})}catch(e){results.push({name,status:'FAIL',failure:e.stack})}await writeFile(resolve(out,'batch8-callback-results.json'),JSON.stringify({layer:'REAL_CHROME_SYNTHETIC_LATE_HTTP_IDENTITY_COMPONENT_REQUEST_FIXTURES',results},null,2));console.log(JSON.stringify(results.at(-1)))}
for(const kind of['request','reply','history','upload','upload-query','query'])for(const transition of['identity-switch','remount'])for(const status of[200,401,403,404,503])await test(`${kind}/${transition}/late-${status}`,async()=>{
 const db=db8(kind==='request'?'PENDING_ACCEPTANCE':'IN_PROGRESS');if(kind!=='request')addRequest(db)
 const held=[],role=kind==='request'?'service':'customer';let originalKey,uploads=0,posts=0
 const f=await setup(role,db,async({path,method,req,route,send})=>{
  if(method==='POST'){posts++;originalKey=req.headers()['idempotency-key']}
  if(kind==='query'&&method==='POST'&&path.endsWith('/reply')){await route.abort();return true}
  if(kind==='upload-query'&&method==='POST'&&path.endsWith('/files')){uploads++;await route.abort();return true}
  const target=kind==='history'?method==='GET'&&path.endsWith('/supplement-requests'):kind==='upload-query'?path.includes('/file-uploads/'):kind==='query'?path.includes('/action-operations/'):method==='POST'&&(kind==='upload'?path.endsWith('/files'):kind==='request'?path.endsWith('/supplement-requests'):path.endsWith('/reply'))
  if(!target && !(['request','reply'].includes(kind)&&path.includes('/action-operations/')) && !(kind==='upload'&&path.includes('/file-uploads/')))return false
  const index=held.length,key=method==='POST'?req.headers()['idempotency-key']:path.includes('/action-operations/')?path.split('/').at(-1):originalKey
  await new Promise(release=>held.push(release))
  if(index===0){await send(status,status===200?kind==='history'?{items:[{...db.supplements[0],internalBody:'OLD PRIVATE'}],total:99,page:1,size:20}:kind.startsWith('upload')?{fileId:'old-file',uploadId:key,state:'READY'}:receipt(db,key,kind==='request'?'supplement-request':'supplement-reply',db.ticket.currentSupplement||addRequest(db)): {code:status===404?(kind==='query'?'OPERATION_NOT_FOUND':'TARGET_NOT_ACCESSIBLE'):'OLD_RECEIPT'});return true}
  await send(kind==='history'?200:404,kind==='history'?{items:structuredClone(db.supplements),total:db.supplements.length,page:1,size:20}:{code:kind==='query'?'OPERATION_NOT_FOUND':'TARGET_NOT_ACCESSIBLE'});return true
 })
 const waitHeld=async n=>{for(let i=0;i<200&&held.length<n;i++)await f.page.waitForTimeout(15);assert(held.length>=n,'target not held')}
 const begin=async()=>{if(kind==='request')await request(f.page);else if(['reply','query'].includes(kind))await reply(f.page);else if(kind.startsWith('upload')){await replyForm(f.page);await pick(f.page)}}
 try{
  await open(f);if(kind!=='history')await begin();if(['query','upload-query'].includes(kind)){await f.page.getByRole('button',{name:'重新核验',exact:true}).click();if(kind==='upload-query')await f.page.getByRole('button',{name:'回复当前补充请求',exact:true}).click();await f.page.getByRole('button',{name:kind==='query'?'查询原动作结果':'查询原补充上传',exact:true}).click()}await waitHeld(1)
  await f.page.evaluate(()=>{document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session').phase='AUTH_RECOVERY_PENDING'})
  await f.page.getByRole('heading',{name:'请确认当前状态',exact:true}).waitFor()
  await f.page.evaluate(transition=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session');s.generation++;s.identity={...s.identity,loginAttemptId:'next-attempt',...(transition==='identity-switch'?{userId:'999',account:'next-actor'}:{})};s.csrf={...s.csrf,loginAttemptId:'next-attempt'};s.phase='NORMAL';s.notice='fresh-notice';s.traceId='fresh-trace'},transition)
  await f.page.locator('.ticket-status').waitFor()
  let label
  if(kind==='history')await waitHeld(2)
  else if(transition==='remount'){if(kind.startsWith('upload')){await f.page.getByRole('button',{name:'回复当前补充请求',exact:true}).click();label='查询原补充上传'}else label='查询原动作结果';await f.page.getByRole('button',{name:label,exact:true}).click();await waitHeld(2);assert(await f.page.getByRole('button',{name:label,exact:true}).isDisabled(),'new request not busy')}
  const snapshot=()=>f.page.evaluate(()=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session');return JSON.stringify([s.phase,s.generation,s.identity?.userId,s.identity?.loginAttemptId,s.notice,s.traceId,location.href])}),before=await snapshot()
  held[0]();await f.page.waitForTimeout(90);assert(before===await snapshot(),'old callback changed new identity/attempt/route');assert(!await f.page.getByText('OLD PRIVATE',{exact:true}).count(),'old history rendered')
  if(transition==='remount'&&kind!=='history'){assert(await f.page.getByRole('button',{name:label,exact:true}).isDisabled(),'old finally unlocked new owner');if(kind.startsWith('upload'))assert(!await f.page.getByRole('button',{name:'显式原键同文件重传',exact:true}).count(),'old404 granted retry')}
  assert(posts===(kind==='history'?0:1),'automatic POST replay');for(const release of held)release()
 }finally{for(const release of held)release();await f.ctx.close()}
})
for(const status of[200,401,403,404,503])await test(`new-request/new-reply/late-${status}`,async()=>{
 const db=db8('IN_PROGRESS');addRequest(db);const held=[],keys=[];const f=await setup('customer',db,async({path,method,req,send})=>{if(method!=='POST'||!path.endsWith('/reply'))return false;const index=held.length,key=req.headers()['idempotency-key'],s=structuredClone(db.ticket.currentSupplement);keys.push(key);await new Promise(release=>held.push(release));await send(index===0?status:503,index===0&&status===200?receipt(db,key,'supplement-reply',s):{code:'OLD_REPLY'});return true})
 try{await open(f);await reply(f.page);while(held.length<1)await f.page.waitForTimeout(20);await f.page.evaluate(()=>{document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session').phase='AUTH_RECOVERY_PENDING'});await f.page.getByRole('heading',{name:'请确认当前状态',exact:true}).waitFor();db.ticket.currentSupplement.reply={replyId:'fixture-prior-reply',operationId:'fixture-prior-operation',body:'上一请求已有合法回复',repliedBy:'301',repliedAt:'2026-10-17T08:09:00Z',attachments:[]};db.ticket.currentSupplement.state='REPLIED';addRequest(db);db.ticket.rowVersion='5';await f.page.evaluate(()=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session');s.generation++;s.identity={...s.identity,loginAttemptId:'fresh'};s.csrf={...s.csrf,loginAttemptId:'fresh'};s.phase='NORMAL'});await f.page.getByRole('heading',{name:'旧方案或补充请求原操作仅可查询',exact:true}).waitFor();await reply(f.page);while(held.length<2)await f.page.waitForTimeout(20);held[0]();await f.page.waitForTimeout(90);assert(await f.page.getByRole('button',{name:'查询原动作结果',exact:true}).isDisabled(),'old reply finally cleared new request owner');assert((await f.page.locator('body').innerText()).includes(keys[1]),'new pending lost');assert(keys.length===2&&keys[0]!==keys[1],'reply UUID moved across requests')}finally{for(const r of held)r();await f.ctx.close()}
})
await browser.close();console.log(JSON.stringify({passed:results.filter(r=>r.status==='PASS').length,failed:results.filter(r=>r.status==='FAIL').length}));if(results.some(r=>r.status==='FAIL'))process.exitCode=1
