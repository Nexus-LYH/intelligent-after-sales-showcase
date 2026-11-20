import{readFile}from'node:fs/promises';import{resolve}from'node:path';import{createHash}from'node:crypto';process.env.IAS_BATCH10_HELPERS_ONLY='1';
const{config,facts,contexts,browser,assert,pageFor,signIn,api,detail,test,flush}=await import('./batch10-real-check.mjs');
const base=resolve('../../../协作记录/阶段0/第十批前端运行证据');const main=JSON.parse(await readFile(resolve(base,'真实修正轮/batch10-real-results.json'),'utf8')),recovery=JSON.parse(await readFile(resolve(base,'真实恢复首轮/batch10-real-results.json'),'utf8'));
const service=await pageFor(),customer=await pageFor();
try{
 await signIn(service,config.service);await signIn(customer,config.customer);
 for(const f of main.facts.filter(f=>f.transferRequestId&&f.fileId))await test('最终CLOSED/工时与方案历史/下载SHA/'+f.ticketId,async()=>{
  const path='/ticket/tickets/'+f.ticketId,d=(await api(service,path)).body;assert(d.status==='CLOSED'&&Number(d.recordedWorkHours)===f.expectedHours&&d.assignment.assignmentId===f.newAssignmentId,'final status/hours/assignment mismatch');
  const transfers=(await api(service,path+'/transfer-requests')).body,solutions=(await api(service,path+'/solutions')).body,records=(await api(service,path+'/records?size=100&page=1')).body;
  assert(transfers.items.length===1&&transfers.items[0].state==='ACCEPTED'&&transfers.items[0].transferRequestId===f.transferRequestId,'handoff history not unique');
  assert(solutions.items.length===2&&solutions.items.map(s=>s.decision?.decision).sort().join(',')==='CONFIRM,RETURN','solution decisions altered');
  assert(new Set(records.items.map(r=>r.recordId)).size===records.items.length,'record ids duplicated');const sum=records.items.reduce((n,r)=>n+Number(r.workHours||0),0);assert(sum===f.expectedHours,'work records sum double counted');
  await detail(customer,f.ticketId,'customer');const pub=(await api(customer,path)).body;assert(pub.transferHistory.length===1&&pub.transferHistory[0].state==='ACCEPTED'&&!pub.transferHistory[0].reason&&!pub.transferHistory[0].decision?.reason,'public handoff invalid');
  const publicRecords=(await api(customer,path+'/records?size=100&page=1')).body;assert(publicRecords.items.every(r=>r.visibility==='CUSTOMER_VISIBLE'&&!('workHours'in r)),'internal record/hour projected');
  const file=pub.attachments[0],download=customer.waitForEvent('download');await customer.getByRole('button',{name:'授权下载',exact:true}).first().click();const bytes=await readFile(await(await download).path());assert(createHash('sha256').update(bytes).digest('hex')===file.sha256,'actual download digest mismatch');
  const stable=x=>JSON.stringify([x.rowVersion,x.status,x.roundId,x.assignment,x.currentTransfer,x.transferCoordination,x.recordedWorkHours,x.timing]);assert(stable((await api(service,path)).body)===stable(d),'final GET changed frozen facts');facts.push({ticketId:f.ticketId,status:d.status,roundId:d.roundId,recordSum:sum,recordedWorkHours:d.recordedWorkHours,transferCount:transfers.items.length,solutionCount:solutions.items.length,currentAssignmentId:d.assignment.assignmentId,fileId:file.fileId,downloadShaMatches:true,publicRecordsOnly:true});
 });
 for(const f of recovery.facts.filter(f=>f.action&&f.mode))await test('恢复样本最终事实与客户公开范围/'+f.action+'/'+f.mode,async()=>{
  const path='/ticket/tickets/'+f.ticketId,d=(await api(service,path)).body,history=(await api(service,path+'/transfer-requests')).body;
  assert(d.roundId===f.roundId&&d.assignment.assignmentId===f.currentAssignmentId&&history.items.length===1&&history.items[0].state===f.resultState,'recovery facts mismatch');
  const stable=x=>JSON.stringify([x.rowVersion,x.status,x.assignment,x.currentTransfer,x.transferCoordination]);assert(stable((await api(service,path)).body)===stable(d),'GET changed recovery facts');
  const publicHistory=(await api(customer,path+'/transfer-requests')).body,pub=(await api(customer,path)).body;const expected=f.action==='ACCEPT'?1:0;assert(pub.transferHistory.length===expected&&publicHistory.total===expected&&publicHistory.items.length===expected,'customer saw proposed/rejected/withdrawn targets');
  assert(!pub.currentTransfer&&!pub.transferCoordination&&!pub.timeline.some(e=>e.visibility==='INTERNAL'),'private workflow projected');
  if(f.action!=='REQUEST'&&f.action!=='ACCEPT')assert(d.transferCoordination.required&&!d.currentTransfer,'coordination missing');facts.push({ticketId:f.ticketId,action:f.action,mode:f.mode,mainStatus:d.status,transferState:history.items[0].state,historyCount:history.items.length,publicHandoffCount:expected,rowVersion:d.rowVersion,roundId:d.roundId});
 });
}finally{await flush();for(const c of contexts)await c.close();await browser.close()}
