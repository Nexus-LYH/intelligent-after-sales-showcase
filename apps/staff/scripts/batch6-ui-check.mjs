// Actual Chrome/Vue with synthetic HTTP/explicit identity fixtures; no backend/DB acceptance.
import { chromium } from 'playwright'
import { mkdir, writeFile } from 'node:fs/promises'
import { resolve } from 'node:path'
const out=process.env.IAS_BATCH6_EVIDENCE||resolve('runtime/batch6-ui'),results=[];await mkdir(out,{recursive:true})
const browser=await chromium.launch({channel:'chrome',headless:true}),assert=(v,m)=>{if(!v)throw Error(m)},root='http://127.0.0.1:5173'
const round='11111111-2222-4333-8444-555555555555',assignment='aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee'
const base={id:'7',ticketNo:'T-7',title:'第六批流程验证',status:'PENDING_ACCEPTANCE',rowVersion:'1',roundId:round,priority:'P2',initialPriority:'P2',impact:'AFFECTED',categoryCode:'UNCERTAIN',description:'设备启动失败，电源指示灯持续闪烁，保留原始报修。',contactName:'测试客户',contactPhone:'123',submittedAt:'2026-10-16T10:00:00Z',deviceSnapshot:{id:'1',code:'D1',name:'设备',serialNumber:'SN',modelName:'型号',ownershipVersion:'1'},companySnapshot:{id:'2',code:'C2',name:'企业'},attachments:[]}
function state(){return{ticket:structuredClone(base),records:[],operations:new Map(),writes:[]}}
async function fixture(role='service',db=state(),extra){
 const ctx=await browser.newContext(),page=await ctx.newPage();page.setDefaultTimeout(6500);page.on('dialog',d=>d.accept())
 const me={userId:role==='service'?'101':role==='engineer'?'201':'301',account:role,displayName:role,realm:'TENANT',identityType:role==='customer'?'CUSTOMER':'EMPLOYEE',tenant:{id:'1',code:'AA',name:'测试租户'},department:role==='engineer'?{name:'维修部'}:null,permissions:role==='service'?['ticket.read','ticket.accept','ticket.reject','ticket.assign']:role==='engineer'?['ticket.engineer.read','ticket.assignment.accept','ticket.record']:[],accessState:'NORMAL',loginAttemptId:'attempt',expiresAt:'2099-01-01T00:00:00Z'}
 function detail(){const t=structuredClone(db.ticket);t.allowedActions=role==='customer'?[]:role==='engineer'?(t.status==='ASSIGNED'?['assignment-accept']:t.status==='IN_PROGRESS'?['records']:[]):t.status==='PENDING_ACCEPTANCE'?['accept','reject']:t.status==='WAITING_ASSIGNMENT'?['assign']:t.qualificationStatus==='INVALID'&&t.status==='ASSIGNED'?['release-invalid-assignment']:[];t.assignableDepartments=role==='service'?[{id:'11',name:'维修部'}]:[];t.timeline=[{type:'SUBMITTED',at:t.submittedAt},...db.records.filter(r=>role!=='customer'||r.visibility==='CUSTOMER_VISIBLE').map(r=>{const x={...r};if(role==='customer')delete x.workHours;return x})];return t}
 await page.route('**/api/**',async route=>{
  const req=route.request(),url=new URL(req.url()),path=url.pathname,method=req.method(),send=(status,body)=>route.fulfill({status,contentType:'application/json',body:JSON.stringify(body)})
  if(await extra?.({route,req,url,path,method,send,db,me,detail}))return
  if(path==='/api/auth/me')return send(200,me)
  if(path==='/api/auth/csrf')return send(200,{token:'synthetic',headerName:'X-CSRF-TOKEN',contextKind:'AUTH',loginAttemptId:me.loginAttemptId,expiresAt:me.expiresAt})
  if(path==='/api/ticket/tickets/7'&&method==='GET')return send(200,detail())
  if(path==='/api/ticket/tickets'&&method==='GET')return send(200,{items:[detail()],total:1,page:1,size:20})
  if(path==='/api/ticket/tickets/7/records'&&method==='GET'){const items=db.records.filter(r=>role!=='customer'||r.visibility==='CUSTOMER_VISIBLE').map(r=>{const x={...r};if(role==='customer')delete x.workHours;return x});return send(200,{items,total:items.length,page:1,size:20})}
  if(path.endsWith('/assignment-candidates'))return send(200,{items:[{userId:'201',displayName:'合格工程师',departmentId:'11'}],total:1,page:1,size:20,departmentId:'11'})
  if(path.includes('/action-operations/')){const r=db.operations.get(path.split('/').at(-1));return r?send(200,r):send(404,{code:'OPERATION_NOT_FOUND'})}
  if(method==='POST'&&path.startsWith('/api/ticket/tickets/7/')){
   const body=req.postDataJSON(),key=req.headers()['idempotency-key'],action=path.endsWith('/'+assignment+'/accept')?'assignment-accept':path.split('/').at(-1);db.writes.push({path,key,body});if(db.operations.has(key))return send(200,db.operations.get(key));if(body.expectedVersion!==db.ticket.rowVersion)return send(409,{code:'VERSION_CONFLICT'})
   if(action==='accept'){db.ticket.status='WAITING_ASSIGNMENT';db.ticket.categoryCode=body.categoryCode;db.ticket.priority=body.priority;db.ticket.firstResponseAt='2026-10-16T10:05:00Z'}
   if(action==='reject')db.ticket.status='REJECTED'
   if(action==='assign'){db.ticket.status='ASSIGNED';db.ticket.assignment={assignmentId:assignment,departmentId:body.departmentId,engineerUserId:body.engineerUserId,state:'ASSIGNED'};db.ticket.qualificationStatus='VALID'}
   if(action==='assignment-accept'){db.ticket.status='IN_PROGRESS';db.ticket.assignment.state='IN_PROGRESS'}
   if(action==='release-invalid-assignment'){db.ticket.status='WAITING_ASSIGNMENT';db.ticket.assignment=null;db.ticket.qualificationStatus='NONE'}
   db.ticket.rowVersion=String(Number(db.ticket.rowVersion)+1)
   const record={recordId:crypto.randomUUID(),type:action.toUpperCase(),body:body.body||body.acceptanceMessage||body.reason||body.note||'工程师已接单',visibility:action==='accept'||action==='reject'?'CUSTOMER_VISIBLE':action==='records'?body.visibility:'INTERNAL',workHours:body.workHours||'0',actorUserId:me.userId,occurredAt:'2026-10-16T10:05:00Z',rowVersion:db.ticket.rowVersion};db.records.push(record)
   const result={operationId:key,action,state:'COMPLETED',ticketId:'7',status:db.ticket.status,rowVersion:db.ticket.rowVersion,roundId:round,assignmentId:db.ticket.assignment?.assignmentId||null,recordId:record.recordId};db.operations.set(key,result);return send(200,result)
  }
  return send(404,{code:'TARGET_NOT_ACCESSIBLE'})
 });return{ctx,page,db,me,detail}
}
async function test(name,fn){if(process.env.IAS_BATCH6_CASE&&!name.includes(process.env.IAS_BATCH6_CASE))return;try{await fn();results.push({name,status:'PASS'})}catch(e){results.push({name,status:'FAIL',failure:e.message.slice(0,1000)})}console.log(JSON.stringify(results.at(-1)));await writeFile(resolve(out,'batch6-ui-results.json'),JSON.stringify({mode:'REAL_CHROME_SYNTHETIC_HTTP_NO_DATABASE',results},null,2))}
async function accept(page){await page.getByRole('button',{name:'受理',exact:true}).click();await page.getByLabel('故障类别',{exact:true}).selectOption('HARDWARE');await page.getByLabel('客户可见人工受理说明',{exact:true}).fill('已核对报修内容，将安排指定工程师处理。');await page.getByRole('button',{name:'确认受理',exact:true}).click()}
await test('客服受理/分派→工程师专用入口接单→默认内部与公开预览→客户投影',async()=>{
 const db=state(),s=await fixture('service',db),e=await fixture('engineer',db),c=await fixture('customer',db)
 try{await s.page.goto(root+'/staff/tickets/7');await accept(s.page);await s.page.locator('.ticket-status').filter({hasText:'待分派'}).waitFor();await s.page.getByRole('button',{name:'直接分派',exact:true}).click();await s.page.getByLabel('授权处理部门',{exact:true}).selectOption('11');await s.page.getByRole('button',{name:'查询合格候选'}).click();await s.page.getByLabel('指定工程师',{exact:true}).selectOption('201');await s.page.getByRole('button',{name:'确认直接分派'}).click();await s.page.locator('.ticket-status').filter({hasText:'待工程师接单'}).waitFor();await e.page.goto(root+'/staff/tickets/7');await e.page.getByRole('link',{name:'工单队列',exact:true}).waitFor();await e.page.getByRole('button',{name:'本人接单',exact:true}).click();await e.page.getByRole('button',{name:'确认本人接单'}).click();await e.page.getByText('工程师已接单，正在处理，尚无维修完成结果。',{exact:true}).waitFor()
  await e.page.getByRole('button',{name:'添加处理记录',exact:true}).click();assert(await e.page.getByLabel('记录可见性',{exact:true}).inputValue()==='INTERNAL','default public');await e.page.getByLabel('处理记录正文',{exact:true}).fill('内部诊断意见不可向客户展示');await e.page.getByLabel('单条工时',{exact:true}).fill('1.25');await e.page.getByRole('button',{name:'确认添加处理记录'}).click();await e.page.getByText('添加处理记录已确认，正在刷新当前工单。',{exact:true}).waitFor();await e.page.getByRole('button',{name:'添加处理记录',exact:true}).click();await e.page.getByLabel('处理记录正文',{exact:true}).fill('公开进展：完成初步检查，正在继续排查。');await e.page.getByLabel('记录可见性',{exact:true}).selectOption('CUSTOMER_VISIBLE');const count=db.writes.length;await e.page.getByRole('button',{name:'确认添加处理记录'}).click();assert(db.writes.length===count,'public submitted without preview');await e.page.getByRole('button',{name:'预览公开内容'}).click();await e.page.getByRole('checkbox',{name:'确认将这段正文向客户公开'}).check();await e.page.getByLabel('处理记录正文',{exact:true}).fill('公开进展：已核对供电，正在进一步排查故障。');await e.page.getByRole('button',{name:'确认添加处理记录'}).click();assert(db.writes.length===count,'changed public body kept confirmation');await e.page.getByRole('button',{name:'预览公开内容'}).click();await e.page.getByRole('checkbox',{name:'确认将这段正文向客户公开'}).check();await e.page.getByRole('button',{name:'确认添加处理记录'}).click();await e.page.getByText('添加处理记录已确认，正在刷新当前工单。',{exact:true}).waitFor();await c.page.goto(root+'/customer/tickets/7');await c.page.getByText('公开进展：已核对供电，正在进一步排查故障。',{exact:true}).first().waitFor();assert(!await c.page.getByText('内部诊断意见不可向客户展示',{exact:true}).count(),'internal body leaked');assert(!await c.page.getByText(/单条工时/).count(),'internal hours leaked');assert(!await e.page.getByRole('button',{name:/解决|关闭工单|转派|上传处理附件/}).count(),'scope expansion');assert(db.ticket.status==='IN_PROGRESS','record changed into repair complete');await c.page.setViewportSize({width:390,height:844});assert(await c.page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'mobile overflow');await c.page.screenshot({path:resolve(out,'synthetic-public-progress.png'),fullPage:true})
 }finally{await writeFile(resolve(out,'synthetic-service-text.txt'),await s.page.locator('body').innerText());await writeFile(resolve(out,'synthetic-engineer-text.txt'),await e.page.locator('body').innerText());await s.ctx.close();await e.ctx.close();await c.ctx.close()}
})
await test('驳回仅显示客户可见理由，不提供后续动作',async()=>{const db=state(),s=await fixture('service',db),c=await fixture('customer',db);try{await s.page.goto(root+'/staff/tickets/7');await s.page.getByRole('button',{name:'驳回',exact:true}).click();await s.page.getByLabel('客户可见驳回理由',{exact:true}).fill('该问题与当前设备服务范围不符，请联系负责人员。');await s.page.getByRole('button',{name:'确认驳回'}).click();await s.page.locator('.ticket-status').filter({hasText:'已驳回'}).waitFor();await c.page.goto(root+'/customer/tickets/7');await c.page.getByText('该问题与当前设备服务范围不符，请联系负责人员。',{exact:true}).first().waitFor();assert(!await c.page.getByRole('button',{name:/受理|分派|记录/}).count(),'customer write action')}finally{await s.ctx.close();await c.ctx.close()}})

await test('normal:首次动作送达前abort→404→用户原UUID原参数显式重交',async()=>{
 const attempts=[];let first=true
 const f=await fixture('service',state(),async({path,method,req,route})=>{if(method==='POST'&&path.endsWith('/accept')){attempts.push({key:req.headers()['idempotency-key'],body:req.postDataJSON()});if(first){first=false;await route.abort();return true}}return false})
 try{await f.page.goto(root+'/staff/tickets/7');await accept(f.page);await f.page.getByRole('button',{name:'重新核验',exact:true}).click();await f.page.getByRole('button',{name:'查询原动作结果',exact:true}).click();await f.page.getByRole('button',{name:'显式原UUID原参数重交'}).waitFor();assert(attempts.length===1,'automatic replay');await f.page.getByRole('button',{name:'显式原UUID原参数重交'}).click();await f.page.locator('.ticket-status').filter({hasText:'待分派'}).waitFor();assert(attempts.length===2&&JSON.stringify(attempts[0])===JSON.stringify(attempts[1]),'key/params/version/round changed');assert(f.db.records.length===1,'duplicate response record')}finally{await f.ctx.close()}
})
await test('normal:已完成动作回执503仅查询原UUID，不再POST',async()=>{
 const f=await fixture('service',state(),async({path,method,req,db,send})=>{if(method==='POST'&&path.endsWith('/accept')){const key=req.headers()['idempotency-key'];db.writes.push({key,body:req.postDataJSON()});db.ticket.status='WAITING_ASSIGNMENT';db.ticket.rowVersion='2';db.operations.set(key,{operationId:key,action:'accept',state:'COMPLETED',ticketId:'7',status:'WAITING_ASSIGNMENT',rowVersion:'2',roundId:round});await send(503,{code:'OPERATION_RESULT_UNKNOWN'});return true}})
 try{await f.page.goto(root+'/staff/tickets/7');await accept(f.page);await f.page.getByRole('button',{name:'重新核验',exact:true}).click();await f.page.getByRole('button',{name:'查询原动作结果',exact:true}).click();await f.page.getByText('原操作已确认完成，已按当前权限刷新。',{exact:true}).waitFor();assert(f.db.writes.length===1,'committed operation replayed')}finally{await f.ctx.close()}
})
await test('normal:409版本冲突关闭旧确认并刷新，不能沿旧候选提交',async()=>{
 const f=await fixture('service',state(),async({path,method,req,db,send})=>{if(method==='POST'&&path.endsWith('/accept')){db.writes.push(req.postDataJSON());db.ticket.rowVersion='2';await send(409,{code:'VERSION_CONFLICT'});return true}})
 try{await f.page.goto(root+'/staff/tickets/7');await accept(f.page);await f.page.getByText('动作未完成：VERSION_CONFLICT。已关闭旧确认，请按最新工单重新选择。',{exact:true}).waitFor();await f.page.getByRole('button',{name:'受理',exact:true}).waitFor();assert(!await f.page.getByRole('button',{name:'确认受理',exact:true}).count(),'old confirmation remained');assert(!await f.page.getByRole('button',{name:'查询原动作结果'}).count(),'definite conflict retained unknown replay');assert(f.db.writes.length===1,'auto retry after409')}finally{await f.ctx.close()}
})
await test('normal:候选依赖503清空旧列表，不冒充空候选或放行分派',async()=>{
 const db=state();db.ticket.status='WAITING_ASSIGNMENT';let reads=0
 const f=await fixture('service',db,async({path,send})=>{if(path.endsWith('/assignment-candidates')&&++reads===2){await send(503,{code:'DEPENDENCY_UNAVAILABLE'});return true}})
 try{await f.page.goto(root+'/staff/tickets/7');await f.page.getByRole('button',{name:'直接分派',exact:true}).click();await f.page.getByLabel('授权处理部门',{exact:true}).selectOption('11');await f.page.getByRole('button',{name:'查询合格候选'}).click();await f.page.getByLabel('指定工程师',{exact:true}).selectOption('201');await f.page.getByRole('button',{name:'查询合格候选'}).click();await f.page.getByRole('button',{name:'重新核验',exact:true}).waitFor();assert(!await f.page.getByRole('option',{name:'合格工程师'}).count(),'old candidate survived failure');await f.page.getByRole('button',{name:'重新核验',exact:true}).click();await f.page.getByRole('button',{name:'直接分派',exact:true}).click();assert(await f.page.getByLabel('指定工程师',{exact:true}).inputValue()==='','old selected engineer restored');await f.page.getByRole('button',{name:'确认直接分派'}).click();assert(db.writes.length===0,'failed candidates allowed assign')}finally{await f.ctx.close()}
})
await test('normal:仅失效未接单候选可释放，处理中失权保留责任待协调',async()=>{
 const db=state();db.ticket.status='ASSIGNED';db.ticket.assignment={assignmentId:assignment,departmentId:'11',engineerUserId:'201',state:'ASSIGNED'};db.ticket.qualificationStatus='INVALID';const f=await fixture('service',db)
 try{await f.page.goto(root+'/staff/tickets/7');await f.page.getByRole('button',{name:'释放失效候选',exact:true}).click();await f.page.getByLabel('释放理由',{exact:true}).fill('当前指定工程师已失去处理资格');await f.page.getByRole('button',{name:'确认释放失效候选'}).click();await f.page.locator('.ticket-status').filter({hasText:'待分派'}).waitFor();db.ticket.status='IN_PROGRESS';db.ticket.assignment={assignmentId:assignment,departmentId:'11',engineerUserId:'201',state:'IN_PROGRESS'};db.ticket.qualificationStatus='INVALID';await f.page.getByRole('button',{name:'重新查询',exact:true}).click();await f.page.getByText('当前负责人资格失效，保留历史责任并等待协调；不能继续记录或自动转派。',{exact:true}).waitFor();assert(!await f.page.getByRole('button',{name:'释放失效候选',exact:true}).count(),'released already in progress');assert(db.writes.length===1,'automatic release/transfer')}finally{await f.ctx.close()}
})
await test('normal:客户响应夹带内部正文或工时立即关闭投影',async()=>{
 const f=await fixture('customer',state(),async({path,method,send})=>{if(method==='GET'&&path.endsWith('/records')){await send(200,{items:[{recordId:'internal',type:'RECORDS',body:'INTERNAL NEVER DISPLAY',visibility:'INTERNAL',workHours:'2',occurredAt:'2026-10-16T10:00:00Z',rowVersion:'2',actorUserId:'201'}],total:99,page:1,size:20});return true}})
 try{await f.page.goto(root+'/customer/tickets/7');await f.page.getByRole('button',{name:'重新核验',exact:true}).waitFor();assert(!await f.page.getByText('INTERNAL NEVER DISPLAY',{exact:true}).count(),'internal body rendered');assert(!await f.page.getByText('Total 99',{exact:true}).count(),'internal total rendered');assert(!await f.page.getByRole('heading',{name:'报修详情',exact:true}).count(),'old business remained after bad projection')}finally{await f.ctx.close()}
})
await test('normal:刷新后无原参数只能查询保存UUID，不创建或换键',async()=>{
 const f=await fixture('service')
 try{await f.page.goto(root+'/staff/tickets/7');await f.page.getByText('按保存的原UUID查询动作结果',{exact:true}).click();await f.page.getByLabel('原动作UUID',{exact:true}).fill(round);await f.page.getByRole('button',{name:'查询已保存UUID'}).click();await f.page.getByText(/本次未观察到原动作，不能断言未执行。/).waitFor();assert(f.db.writes.length===0,'lookup404 created new action');assert(!await f.page.getByRole('button',{name:'显式原UUID原参数重交'}).count(),'recreated missing original params')}finally{await f.ctx.close()}
})
for(const kind of ['accept','record','query','candidates','record-list'])for(const transition of ['identity-switch','remount-new-request'])for(const status of (kind==='query'?[200,401,403,404,503]:[200,401,403,503]))await test(`${kind}/${transition}/late-${status}`,async()=>{
 const db=state();if(kind==='record'){db.ticket.status='IN_PROGRESS';db.ticket.assignment={assignmentId:assignment,departmentId:'11',engineerUserId:'201',state:'IN_PROGRESS'};db.ticket.qualificationStatus='VALID'}if(kind==='candidates')db.ticket.status='WAITING_ASSIGNMENT'
 let operation,queryPreparing=true;const held=[]
 const f=await fixture(kind==='record'?'engineer':kind==='record-list'?'customer':'service',db,async({path,method,req,send,route})=>{
  if(kind==='query'&&method==='POST'&&path.endsWith('/accept')){operation={key:req.headers()['idempotency-key'],action:'accept'};db.writes.push({key:operation.key,body:req.postDataJSON()});await route.abort();return true}
  const target=['accept','record'].includes(kind)&&path.includes('/action-operations/')||kind==='accept'&&method==='POST'&&path.endsWith('/accept')||kind==='record'&&method==='POST'&&path.endsWith('/records')||kind==='query'&&path.includes('/action-operations/')||kind==='candidates'&&path.endsWith('/assignment-candidates')||kind==='record-list'&&method==='GET'&&path.endsWith('/records')
  if(!target)return false
  if(method==='POST'){operation={key:req.headers()['idempotency-key'],action:kind==='record'?'records':'accept'};db.writes.push({key:operation.key,body:req.postDataJSON()})}
  let release;const index=held.length;const gate=new Promise(r=>release=r);held.push(release);await gate
  if(index===0&&status!==200)await send(status,{code:status===404?'OPERATION_NOT_FOUND':'LATE_RESULT',message:'Old response'})
  else if(kind==='candidates')await send(200,{items:[{userId:index===0?'999':'201',displayName:index===0?'OLD STALE CANDIDATE':'合格工程师',departmentId:'11'}],total:1,page:1,size:20})
  else if(kind==='record-list')await send(200,{items:[{recordId:'record',type:'RECORDS',body:index===0?'OLD INTERNAL TEXT':'Current public record',visibility:index===0?'INTERNAL':'CUSTOMER_VISIBLE',...(index===0?{workHours:'1'}:{}),actorUserId:'201',occurredAt:'2026-10-16T10:05:00Z',rowVersion:'2'}],total:1,page:1,size:20})
  else await send(200,{operationId:operation.key,action:operation.action,state:'COMPLETED',ticketId:'7',rowVersion:'2',roundId:round,status:kind==='record'?'IN_PROGRESS':'WAITING_ASSIGNMENT'})
  return true
 })
 const waitHeld=async n=>{const until=Date.now()+6500;while(held.length<n&&Date.now()<until)await f.page.waitForTimeout(10);assert(held.length>=n,'request not held')}
 async function candidates(){await f.page.getByRole('button',{name:'直接分派',exact:true}).click();await f.page.getByLabel('授权处理部门',{exact:true}).selectOption('11');await f.page.getByRole('button',{name:'查询合格候选',exact:true}).click()}
 try{
  await f.page.goto(root+(kind==='record-list'?'/customer/tickets/7':'/staff/tickets/7'))
  if(kind==='accept'||kind==='query')await accept(f.page)
  if(kind==='query'){await f.page.getByRole('button',{name:'重新核验',exact:true}).click();await f.page.getByRole('button',{name:'查询原动作结果',exact:true}).click()}
  if(kind==='record'){await f.page.getByRole('button',{name:'添加处理记录',exact:true}).click();await f.page.getByLabel('处理记录正文',{exact:true}).fill('Owned record body');await f.page.getByRole('button',{name:'确认添加处理记录'}).click()}
  if(kind==='candidates')await candidates()
  await waitHeld(1)
  await f.page.evaluate(()=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session');s.phase='AUTH_RECOVERY_PENDING'})
  await f.page.getByRole('heading',{name:'请确认当前状态',exact:true}).waitFor()
  await f.page.evaluate(transition=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session');s.generation++;s.identity={...s.identity,loginAttemptId:'next-attempt',...(transition==='identity-switch'?{userId:'999',account:'new.actor'}:{})};s.csrf={...s.csrf,loginAttemptId:'next-attempt'};s.phase='NORMAL';s.notice='current-notice';s.traceId='current-trace'},transition)
  await f.page.getByRole('heading',{name:kind==='record-list'?'报修详情':'工单详情',exact:true}).waitFor()
  if(kind==='record-list')await waitHeld(2)
  else if(transition==='remount-new-request'){if(kind==='candidates')await candidates();else await f.page.getByRole('button',{name:'查询原动作结果',exact:true}).click();await waitHeld(2)}
  const snapshot=()=>f.page.evaluate(()=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session');return JSON.stringify([s.phase,s.generation,s.identity?.userId,s.identity?.loginAttemptId,s.notice,s.traceId,location.href])})
  if(transition==='remount-new-request'&&kind!=='record-list')await f.page.waitForFunction(label=>Array.from(document.querySelectorAll('button')).some(b=>b.textContent.trim()===label&&b.disabled),kind==='candidates'?'查询合格候选':'查询原动作结果');const before=await snapshot();held[0]();await f.page.waitForTimeout(80);assert(before===await snapshot(),'old callback changed current identity/navigation');assert(!await f.page.getByText('OLD INTERNAL TEXT',{exact:true}).count(),'old internal projection processed');assert(!await f.page.getByRole('option',{name:'OLD STALE CANDIDATE'}).count(),'old candidate published')
  if(kind==='record-list'){held[1]();await f.page.getByText('Current public record',{exact:true}).waitFor()}
  else if(transition==='remount-new-request'){
   const button=f.page.getByRole('button',{name:kind==='candidates'?'查询合格候选':'查询原动作结果',exact:true});assert(await button.isDisabled(),'old finally unlocked new owner');assert(!await f.page.getByRole('button',{name:'显式原UUID原参数重交'}).count(),'old404 opened replay');held[1]();if(kind==='candidates')await f.page.getByRole('option',{name:'合格工程师',exact:true}).waitFor({state:'attached'});else await f.page.getByText('原操作已确认完成，已按当前权限刷新。',{exact:true}).waitFor()
  }
  assert(db.writes.length===(['accept','record','query'].includes(kind)?1:0),'automatic write/replay')
 }finally{for(const release of held)release();await f.ctx.close()}
})
await browser.close();console.log(JSON.stringify({passed:results.filter(x=>x.status==='PASS').length,failed:results.filter(x=>x.status!=='PASS').length}));if(results.some(x=>x.status!=='PASS'))process.exitCode=1






