// Prepare a real REGISTERED/no-identity operation through public APIs only.
// Deliberately invalid UTF-16 password is a negative input, not a real credential.
// No SQL, runtime configuration change, backend file edit or HTTP mocking.
import { chromium } from 'playwright'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import { resolve } from 'node:path'
const project=resolve('../../..'),out=process.env.IAS_ID04_EVIDENCE??resolve('runtime/id04')
await mkdir(out,{recursive:true})
const plan=JSON.parse(await readFile(resolve(project,'项目源码/services/identity/bootstrap/local-accounts.json'),'utf8'))
const admin=plan.users.find(u=>u.passwordRef==='tenantA1')
const privatePath=resolve(process.env.IAS_BROWSER_SECRET_DIR??'C:/ias-local/Develop/IAS-MySQL84/secrets','frontend-browser-passwords.json'),saved=JSON.parse(await readFile(privatePath,'utf8'))
const qaRoot=resolve(project,'协作记录/阶段0/测试顾问独立验证/第四批'),qaMeta=JSON.parse(await readFile(resolve(qaRoot,'受限凭据位置.json'),'utf8')),failure=JSON.parse(await readFile(resolve(qaRoot,'ID04独立真实复现.json'),'utf8'))
const browser=await chromium.launch({channel:'chrome',headless:true}),context=await browser.newContext(),page=await context.newPage()
page.setDefaultTimeout(15000)
const key=randomUUID(),account='web.id04.'+Date.now().toString(36),actor={tenantCode:admin.tenantCode,account:admin.account,passwordFile:privatePath,passwordRef:'tenantA1'}
let result
try{
 await page.goto('http://127.0.0.1:5173/staff/login');await page.getByLabel('租户编码').fill(admin.tenantCode);await page.getByLabel('账号',{exact:true}).fill(admin.account);await page.getByLabel('密码',{exact:true}).fill(saved.tenantA1);await page.getByRole('button',{name:'登录',exact:true}).click();await page.getByRole('heading',{name:'本人账户',exact:true}).waitFor()
 const response=await page.evaluate(async({key,account,companyId})=>{
  const c=await fetch('/api/auth/csrf',{cache:'no-store'}),csrf=await c.json();if(c.status!==200)throw Error('CSRF unavailable')
  const r=await fetch(`/api/asset/customer-companies/${companyId}/members`,{method:'POST',headers:{'Content-Type':'application/json','X-CSRF-TOKEN':csrf.token,'Idempotency-Key':key},body:JSON.stringify({account,displayName:'真实无账号恢复样本',initialPassword:'synthetic-invalid-utf16-'+String.fromCharCode(0xD800)})});const b=await r.json().catch(()=>null)
  const op=await fetch('/api/asset/customer-enrollments/'+key,{cache:'no-store'}),o=await op.json().catch(()=>null)
  const user=await fetch('/api/identity/users?keyword='+encodeURIComponent(account)+'&size=100',{cache:'no-store'}),u=await user.json().catch(()=>null)
  return {postStatus:r.status,postCode:b?.code,queryStatus:op.status,state:o?.state,nextAction:o?.nextAction,userId:o?.userId??null,userStatus:user.status,identityCount:u?.items?.filter(x=>x.account===account).length}
 },{key,account,companyId:failure.companyId})
 result={mode:'REAL_HTTP_INVALID_UTF16_NEGATIVE_INPUT_NO_SQL',operationId:key,account,companyId:failure.companyId,...response}
 if(response.queryStatus!==200||response.state!=='REGISTERED'||response.nextAction!=='RESUME'||response.userStatus!==200||response.identityCount!==0)throw Error('API negative input did not produce required persisted no-identity state')
 const fixture={actor,cases:[{kind:'existing-pending',operationId:failure.operationId,companyId:failure.companyId,account:failure.account,initialPasswordFile:qaMeta.current,initialPasswordRef:`customer:${admin.tenantCode}:${failure.account}`},{kind:'identity-absent',operationId:key,companyId:failure.companyId,account}]}
 await writeFile(resolve(out,'真实样本引用.json'),JSON.stringify(fixture,null,2));result.status='PASS'
}catch(e){result={...result,status:'FAIL',failure:e.message.split('\n')[0]};process.exitCode=1}
finally{await context.close();await browser.close();await writeFile(resolve(out,'无账号状态准备.json'),JSON.stringify(result,null,2));console.log(JSON.stringify(result))}
