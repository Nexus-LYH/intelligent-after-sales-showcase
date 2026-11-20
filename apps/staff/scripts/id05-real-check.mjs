// Default final four JARs, real Chrome/gateway/services/MySQL. No SQL or backend mutation.
// Race uses a captured original multipart request delivered late via APIRequestContext,
// concurrently with the user's browser replay. It is controlled HTTP timing, not DB failure.
import { chromium } from 'playwright'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { resolve } from 'node:path'
const out=process.env.IAS_ID05_EVIDENCE||resolve('runtime/id05-real'),project=resolve('../../..'),root='http://127.0.0.1:5173',results=[],contexts=[]
await mkdir(out,{recursive:true})
const target=JSON.parse(await readFile(resolve(project,'协作记录/阶段0/第四批前端运行证据/batch4-real-results.json'),'utf8')).accounts[0]
const saved=JSON.parse(await readFile('C:/ias-local/Develop/IAS-MySQL84/secrets/frontend-browser-passwords.json','utf8'))
const browser=await chromium.launch({channel:'chrome',headless:true}),sha=b=>createHash('sha256').update(b).digest('hex'),assert=(v,m)=>{if(!v)throw Error(m)}
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jF1sAAAAASUVORK5CYII=','base64')
for(const race of [false,true]){
 const ctx=await browser.newContext({viewport:{width:1280,height:900}});contexts.push(ctx);const page=await ctx.newPage();page.setDefaultTimeout(20000);page.on('dialog',d=>d.accept())
 const result={name:race?'真实404后迟到原multipart与显式同键重传竞争→报修下载':'真实首次送达前abort→404→显式原键同File重传→报修下载',mode:'REAL_CHROME_DEFAULT_FINAL_JARS_CONTROLLED_HTTP_TIMING',race,sequence:[]};let posts=0,original,originalWire,uploadResponses=[]
 try{
  await page.goto(root+'/customer/login');await page.getByLabel('租户编码').fill(target.tenantCode);await page.getByLabel('账号',{exact:true}).fill(target.customer);await page.getByLabel('密码',{exact:true}).fill(saved[target.passwordRef]);await page.getByRole('button',{name:'登录',exact:true}).click();await page.getByRole('heading',{name:'授权设备',exact:true}).waitFor()
  await page.goto(root+'/customer/repairs/new?deviceId='+target.deviceId);await page.getByLabel('报修标题',{exact:true}).fill('ID05原上传恢复验证'+Date.now());await page.getByLabel('故障描述',{exact:true}).fill('验证首次上传中断后沿原上传UUID恢复，设备启动失败且电源指示灯持续闪烁。');await page.getByLabel('联系人',{exact:true}).fill('原上传恢复验证');await page.getByLabel('联系电话',{exact:true}).fill('123456789');await page.locator('input[type=checkbox]').check()
  const filename=race?'id05-late-original.png':'id05-before-delivery.png';await page.getByLabel('选择附件',{exact:true}).setInputFiles({name:filename,mimeType:'image/png',buffer:png})
  await page.route('**/api/ticket/files',async route=>{
   if(route.request().method()!=='POST')return route.continue()
   posts++;const req=route.request(),buffer=req.postDataBuffer(),body=buffer.toString('latin1'),field=name=>body.match(new RegExp('name="'+name+'"\\r\\n\\r\\n([^\\r]+)'))?.[1]
   const current={uploadId:req.headers()['idempotency-key'],submissionId:field('submissionId'),deviceId:field('deviceId'),expectedOwnershipVersion:field('expectedOwnershipVersion'),sha256:field('sha256'),fileSha256:sha(Buffer.from(body.slice(body.indexOf('\r\n\r\n',body.indexOf('name="file"'))+4,body.lastIndexOf('\r\n--')),'latin1'))}
   result.sequence.push({event:'browser-upload-attempt',attempt:posts,...current})
   if(posts===1){original=current;originalWire={url:req.url(),headers:await req.allHeaders(),data:buffer};return route.abort()}
   assert(JSON.stringify(current)===JSON.stringify(original),'explicit replay changed original key/File/device parameters')
   let response
   if(race){const responses=await Promise.all([ctx.request.fetch(originalWire.url,{method:'POST',headers:originalWire.headers,data:originalWire.data,timeout:120000}),route.fetch({timeout:120000})]);response=responses[1];for(let i=0;i<responses.length;i++){const r=responses[i],b=await r.json();uploadResponses.push({source:i===0?'captured-original-delivered-late':'explicit-browser-replay',status:r.status(),fileId:b.fileId,code:b.code})}}
   else {response=await route.fetch({timeout:120000});const b=await response.json();uploadResponses.push({source:'explicit-browser-replay',status:response.status(),fileId:b.fileId,code:b.code})}
   await route.fulfill({response})
  })
  await page.getByRole('button',{name:'显式上传/原键重试',exact:true}).click();await page.getByRole('button',{name:'重新核验',exact:true}).click();await page.getByText(filename+' · UNKNOWN',{exact:false}).waitFor()
  const query=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/ticket/file-uploads/'+original.uploadId);await page.getByRole('button',{name:'查询原上传',exact:true}).click();const q=await query;assert(q.status()===404&&(await q.json()).code==='TARGET_NOT_ACCESSIBLE','expected current upload not observed');await page.getByRole('button',{name:'显式原键同文件重传',exact:true}).waitFor();assert(posts===1,'automatic upload after query')
  result.sequence.push({event:'original-upload-query',status:404,interpretation:'not observed on this read; not proof of nonexecution'})
  await page.getByRole('button',{name:'显式移除',exact:true}).click();await page.getByText('须先查询原上传状态，不能将未知上传当作已移除',{exact:false}).waitFor();await page.getByRole('button',{name:'提交报修',exact:true}).click();await page.getByText('所有附件须真实READY；失败或未知项请显式查询、重试或移除',{exact:false}).waitFor();assert(new URL(page.url()).searchParams.get('submissionId')===original.submissionId,'submission changed')
  const reply=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/ticket/files'&&r.request().method()==='POST');await page.getByRole('button',{name:'显式原键同文件重传',exact:true}).click();const upload=await reply;assert([200,201,409].includes(upload.status()),'unexpected upload response');if(upload.status()===409)assert((await upload.json()).code==='OPERATION_BUSY','unexpected conflict')
  await page.getByRole('button',{name:'查询原上传',exact:true}).click();await page.getByText(filename+' · READY',{exact:false}).waitFor();const state=await page.evaluate(async key=>(await fetch('/api/ticket/file-uploads/'+key,{cache:'no-store'})).json(),original.uploadId);assert(state.state==='READY'&&state.uploadId===original.uploadId&&state.submissionId===original.submissionId&&state.sha256===sha(png),'wrong restored file');const ids=new Set(uploadResponses.filter(r=>[200,201].includes(r.status)).map(r=>r.fileId));assert(ids.size===1&&ids.has(state.fileId),'multiple fileIds');assert(posts===2,'automatic or duplicate browser upload')
  result.uploadResponses=uploadResponses;result.fileId=state.fileId;result.sameOriginalParameters=true;result.browserUploadAttempts=posts;result.backendDeliveredUploads=race?2:1
  const create=page.waitForResponse(r=>new URL(r.url()).pathname==='/api/ticket/tickets'&&r.request().method()==='POST');await page.getByRole('button',{name:'提交报修',exact:true}).click();const cr=await create;assert(cr.status()===201,'real repair create rejected');const ticket=await cr.json();assert(ticket.operationId===original.submissionId,'repair UUID changed');await page.getByRole('link',{name:'查看待受理报修'}).click();await page.getByRole('button',{name:'授权下载',exact:true}).waitFor()
  const detail=await page.evaluate(async id=>(await fetch('/api/ticket/tickets/'+id,{cache:'no-store'})).json(),ticket.ticketId);assert(detail.attachments.length===1&&detail.attachments[0].fileId===state.fileId,'file reference count/identity wrong')
  const download=page.waitForEvent('download');await page.getByRole('button',{name:'授权下载',exact:true}).click();const d=await download,path=resolve(out,filename);await d.saveAs(path);const bytes=await readFile(path);assert(sha(bytes)===sha(png),'download content mismatch');result.ticketId=ticket.ticketId;result.ticketNo=ticket.ticketNo;result.attachmentCount=1;result.downloadSha256=sha(bytes);result.status='PASS';await page.screenshot({path:resolve(out,race?'late-original-success.png':'before-delivery-success.png'),fullPage:true})
 }catch(e){result.status='FAIL';result.failure=e.message.split('\n')[0]}finally{await ctx.close();results.push(result);await writeFile(resolve(out,'id05-real-results.json'),JSON.stringify({issue:'ID-05',results,notes:['All calls target default final four JARs, never historical slice flags.','No SQL, service interruption, migration, password change or reused failed samples.','Late original uses captured raw multipart delivered with current original credentials in memory; no headers/body secrets are recorded.','Unique observable uploadId/fileId and one final attachment reference verified; database uniqueness separately covered by backend/independent tests.']},null,2));console.log(JSON.stringify({name:result.name,status:result.status,failure:result.failure}))}
}
for(const c of contexts)await c.close().catch(()=>{});await browser.close();if(results.some(r=>r.status!=='PASS'))process.exitCode=1
