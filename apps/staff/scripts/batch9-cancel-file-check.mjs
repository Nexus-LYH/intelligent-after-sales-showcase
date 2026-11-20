import {writeFile} from 'node:fs/promises'
import {resolve} from 'node:path'
process.env.IAS_BATCH9_FIXTURE_ONLY='1'
const {setup,db9,terminate,test,out,results,open,browser,assert}=await import('./batch9-ui-check.mjs')
const {addRequest,replyForm,pick}=await import('./batch8-ui-check.mjs')
for(const source of['upload','upload-query'])for(const status of[200,401,403,404,503])await test(`取消终止未知附件/${source}/late-${status}`,async()=>{
 const db=db9(),s=addRequest(db),held=[];let posts=0,key,sha,replyKey
 const f=await setup('customer',db,async({path,method,req,route,send})=>{
  if(method==='POST'&&path.endsWith('/files')){posts++;key=req.headers()['idempotency-key'];const raw=req.postDataBuffer().toString('latin1'),field=n=>raw.match(new RegExp(`name="${n}"\\r\\n\\r\\n([^\\r]+)`))?.[1];sha=field('sha256');replyKey=field('replyOperationId');if(source==='upload-query'){await route.abort();return true}}
  if(!(method==='POST'&&path.endsWith('/files')||path.includes('/file-uploads/')))return false
  const index=held.length;await new Promise(r=>held.push(r));await send(index===0?status:404,index===0&&status===200?{fileId:'old-file',uploadId:key,ticketId:'7',roundId:db.ticket.roundId,requestId:s.requestId,replyOperationId:replyKey,name:'supplement.png',sha256:sha,size:68,mediaType:'image/png',state:'READY'}:{code:'TARGET_NOT_ACCESSIBLE'});return true
 })
 const wait=async n=>{for(let i=0;i<200&&held.length<n;i++)await f.page.waitForTimeout(15);assert(held.length>=n,'not held')}
 try{await open(f);await replyForm(f.page);await pick(f.page);if(source==='upload-query'){await f.page.getByRole('button',{name:'重新核验',exact:true}).click();await f.page.getByRole('button',{name:'回复当前补充请求',exact:true}).click();await f.page.getByRole('button',{name:'查询原补充上传',exact:true}).click()}await wait(1)
  terminate(db);db.ticket.rowVersion='3';await f.page.getByRole('button',{name:'重新查询',exact:true}).click();await f.page.locator('.cancelled-drafts').waitFor();assert((await f.page.locator('.cancelled-drafts').innerText()).includes(key),'original upload key lost');assert((await f.page.locator('.cancelled-drafts').innerText()).includes(replyKey),'original reply key lost');assert(!await f.page.getByLabel('选择补充附件',{exact:true}).count(),'cancel upload selector visible')
  await f.page.getByRole('button',{name:'查询原补充上传',exact:true}).click();await wait(2);held[0]();await f.page.waitForTimeout(90);assert(await f.page.getByRole('button',{name:'查询原补充上传',exact:true}).isDisabled(),'late finally cleared new query');const session=await f.page.evaluate(()=>{const s=document.querySelector('#app').__vue_app__.config.globalProperties.$pinia._s.get('session');return s.phase});assert(session==='NORMAL','late receipt changed auth');assert((await f.page.locator('.cancelled-drafts article').innerText()).includes('UNKNOWN'),'old response changed retained file');held[1]();await f.page.getByText(/当前上下文不可写，仅保留原File/).waitFor();assert(await f.page.getByRole('button',{name:'显式原键同文件重传',exact:true}).isDisabled(),'cancelled404 allowed retry');assert(posts===1&&db.writes.length===0,'unexpected write/remove')
 }finally{for(const r of held)r();await f.ctx.close()}
})
await writeFile(resolve(out,'batch9-cancel-file-results.json'),JSON.stringify({layer:'REAL_CHROME_SYNTHETIC_UPLOAD_CANCELLATION_LIFECYCLE',results},null,2));await browser.close();console.log(JSON.stringify({passed:results.filter(r=>r.status==='PASS').length,failed:results.filter(r=>r.status==='FAIL').length}));if(results.some(r=>r.status==='FAIL'))process.exitCode=1
