import { chromium } from 'playwright';import fs from 'node:fs';
const base='http://127.0.0.1:5173/customer';const pw=JSON.parse(fs.readFileSync('C:/ias-local/Develop/IAS-MySQL84/secrets/batch6-current-passwords.json','utf8'));const title='DEEPSEEK-CUSTOMER-E2E-'+Date.now();const desc='客户浏览器端到端验收：设备 B6_A_D1 报修提交，仅用于本地验收，非真实故障。';
const browser=await chromium.launch({channel:'chrome',headless:true});const page=await (await browser.newContext({viewport:{width:1280,height:900}})).newPage();const calls=[];page.on('response',r=>{const u=new URL(r.url());if(u.pathname.startsWith('/api/'))calls.push({path:u.pathname,method:r.request().method(),status:r.status()})});page.on('dialog',d=>d.accept());
try{
 await page.goto(base+'/login');await page.getByLabel('租户编码').fill('IAS6_A');await page.getByLabel('账号',{exact:true}).fill('customer.1');await page.getByLabel('密码',{exact:true}).fill(pw.b6ACustomer1);await page.getByRole('button',{name:'登录',exact:true}).click();await page.waitForURL(/\/customer\/devices/);
 await page.goto(base+'/repairs/new?deviceId=157');await page.waitForTimeout(800);
 await page.locator('input[aria-label="报修标题"]').fill(title);await page.locator('textarea[aria-label="故障描述"]').fill(desc);
 await page.locator('input[aria-label="联系人"]').fill('A企业1客户');await page.locator('input[aria-label="联系电话"]').fill('13800000000');
 const check=page.getByRole('checkbox');if(await check.count()) {await check.first().check();}
 await page.getByRole('button',{name:'提交报修',exact:true}).click();
 await page.waitForTimeout(3500);
 const afterUrl=page.url();const afterBody=(await page.locator('body').innerText()).slice(0,1600);
 await page.goto(base+'/tickets');await page.waitForTimeout(1200);const listBody=(await page.locator('body').innerText()).slice(0,2000);
 await page.screenshot({path:'C:/ias-local/Codex/Work/Intelligent After-Sales/协作记录/阶段0/公共集成运行证据/20261029DeepSeekContinuation/browser-final/customer-repair-submit.png',fullPage:true});
 const out={title,afterUrl,afterBody,listBody,calls,passed:listBody.includes(title)||afterBody.includes(title)};fs.writeFileSync('C:/ias-local/Codex/Work/Intelligent After-Sales/协作记录/阶段0/公共集成运行证据/20261029DeepSeekContinuation/browser-final/customer-repair-submit.json',JSON.stringify(out,null,2));console.log(JSON.stringify(out,null,2));if(!out.passed)process.exitCode=2;
}catch(e){const out={title,error:String(e.message||e),calls,url:page.url()};fs.writeFileSync('C:/ias-local/Codex/Work/Intelligent After-Sales/协作记录/阶段0/公共集成运行证据/20261029DeepSeekContinuation/browser-final/customer-repair-submit.json',JSON.stringify(out,null,2));console.error(JSON.stringify(out,null,2));process.exitCode=1}finally{await browser.close()}
