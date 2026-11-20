process.env.IAS_BATCH10_HELPERS_ONLY='1'
const {config,results,facts,calls,contexts,browser,out,root,assert,pageFor,signIn,api,detail,test,flush,assigned,proposeForm,decideForm,realFault,setActors}=await import('./batch10-real-check.mjs')
const pages={};for(const role of ['service','engineer','target','customer'])pages[role]=await pageFor();setActors(pages);const{service,target}=pages
try{
 await test('恢复轮使用现行四账号，不重新初始化或授权',async()=>{for(const role of Object.keys(pages))await signIn(pages[role],config[role])})
 for(const action of ['REQUEST','ACCEPT','REJECT','WITHDRAW'])for(const mode of ['ABORT','LOST503'])await test(action+'/'+mode+'真实原UUID显式恢复',async()=>{
  const id=await assigned(action+mode);let q;
  await proposeForm(id);
  if(action!=='REQUEST'){await service.getByRole('button',{name:'确认发起转派',exact:true}).click();await service.locator('.current-transfer').waitFor();q=(await api(service,'/ticket/tickets/'+id)).body.currentTransfer}
  const isTarget=['ACCEPT','REJECT'].includes(action),p=isTarget?target:service;
  let pattern,label;
  if(isTarget){await target.goto(root+'/staff/transfers/'+q.transferRequestId);await target.locator('.limited-transfer-summary').waitFor();await decideForm(target,action==='ACCEPT');pattern='**/decision';label='提交转派决定'}
  else if(action==='WITHDRAW'){await service.getByRole('button',{name:'撤回转派',exact:true}).click();await service.getByLabel('内部转派理由',{exact:true}).fill('原键恢复核验，撤回后按服务端当前责任继续协调。');await service.getByRole('button',{name:'预览交接确认',exact:true}).click();await service.getByLabel('确认本次源目标与责任；目标接受前不交接、不授完整工单或附件权',{exact:true}).check();pattern='**/withdraw';label='确认撤回转派'}else{pattern='**/transfer-requests';label='确认发起转派'}
  const before=(await api(service,'/ticket/tickets/'+id)).body;const original=await realFault(p,pattern,label,mode);assert(original?.key,'original key missing');
  const countPost=()=>calls.filter(c=>c.method==='POST'&&(c.path.endsWith('/transfer-requests')||c.path.endsWith('/decision')||c.path.endsWith('/withdraw'))).length;
  const count=countPost();
  if(mode==='ABORT'){
   await p.getByRole('button',{name:isTarget?'查询原转派决定':'查询原动作结果',exact:true}).click();
   const button=p.getByRole('button',{name:isTarget?'显式原UUID原参数重交转派决定':'显式原UUID原参数重交',exact:true});await button.waitFor();assert(countPost()===count,'automatic retry');
   let replay;const observer=r=>{if(r.method()==='POST')replay={key:r.headers()['idempotency-key'],body:r.postData()}};p.on('request',observer);const response=p.waitForResponse(r=>r.request().method()==='POST'&&r.request().headers()['idempotency-key']===original.key);await button.click();assert((await response).status()===200,'replay not success');p.off('request',observer);assert(replay.key===original.key&&replay.body===original.body,'replay changed original key/body');
  }else{
   await p.getByRole('button',{name:isTarget?new RegExp('查询旧转派决定 '+original.key):new RegExp('查询旧上下文原UUID '+original.key)}).click();
   await p.getByText(isTarget?/本人原决定历史回执已核对/:/原动作已完成/).waitFor();assert(countPost()===count,'committed unknown automatically resent');
   if(action==='ACCEPT'){assert(p.url().includes('/staff/transfers/'),'historical ACCEPT automatically navigated');await p.getByRole('button',{name:'核对当前工单访问权限',exact:true}).click();await p.waitForURL('**/staff/tickets/'+id)}
  }
  const after=(await api(service,'/ticket/tickets/'+id)).body,result=await api(p,'/ticket/action-operations/'+original.key);assert(result.status===200,'original receipt not readable');assert(result.body.operationId===original.key&&result.body.sourceAssignmentId===before.assignment.assignmentId,'result origin mismatch');
  const stable=d=>JSON.stringify([d.rowVersion,d.status,d.roundId,d.assignment,d.currentTransfer,d.transferCoordination]);assert(stable((await api(service,'/ticket/tickets/'+id)).body)===stable(after),'GET advanced state');
  assert(after.roundId===before.roundId,'round changed');
  if(action==='ACCEPT')assert(after.assignment.assignmentId!==before.assignment.assignmentId&&after.status==='IN_PROGRESS','accepted not assigned');else assert(after.assignment.assignmentId===before.assignment.assignmentId&&after.status===before.status,'nonaccept changed responsibility');
  facts.push({ticketId:id,action,mode,operationId:original.key,transferRequestId:result.body.transferRequestId,resultState:result.body.state,sourceAssignmentId:before.assignment.assignmentId,currentAssignmentId:after.assignment.assignmentId,roundId:after.roundId,originalParametersPreserved:true,getDidNotAdvance:true});
 })
}finally{await flush();for(const c of contexts)await c.close();await browser.close()}
