import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createRenderer } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { useSession } from '../src/session'
import { useTicketWork } from '../src/ticket-work'
import { repairValidation, verifyUpload, type RepairInput } from '../src/ticket-api'
import { matchesUploadOrigin, type RepairDraft, type UploadWork } from '../src/ticket-work'
import { api, ApiError, type Identity } from '../src/api'
import { assetBody, formOf, validateAsset } from '../src/asset-forms'
import type { AssetRecord } from '../src/asset-api'
const renderer = createRenderer<object, object>({ patchProp() {}, insert() {}, remove() {}, createElement: () => ({}), createText: () => ({}), createComment: () => ({}), setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null })
const me: Identity = { userId:'asset-owner', account:'owner',displayName:'Owner',realm:'TENANT',identityType:'EMPLOYEE',tenant:{id:'asset-tenant',code:'AA',name:'Tenant'},department:null,permissions:['asset.read','asset.manage','identity.customer.provision'],accessState:'NORMAL',loginAttemptId:'attempt',expiresAt:'2099-01-01T00:00:00Z' }
let pinia: ReturnType<typeof createPinia>
const mounted: Array<() => void> = []
function mount() { let work!: ReturnType<typeof useTicketWork>; const app=renderer.createApp({setup(){work=useTicketWork(()=>'/assets/companies/1');return ()=>null}});app.use(pinia);app.mount({});mounted.push(()=>app.unmount());return {work,unmount:()=>app.unmount()} }
beforeEach(()=>{vi.useFakeTimers();pinia=createPinia();setActivePinia(pinia);const values=new Map<string,string>();vi.stubGlobal('sessionStorage',{getItem:(k:string)=>values.get(k)??null,setItem:(k:string,v:string)=>values.set(k,v),removeItem:(k:string)=>values.delete(k)});vi.stubGlobal('window',{addEventListener:vi.fn()});vi.stubGlobal('BroadcastChannel',undefined);const auth=useSession();auth.identity={...me,loginAttemptId:crypto.randomUUID()};auth.phase='NORMAL'})
afterEach(()=>{for(const unmount of mounted.splice(0))unmount();vi.restoreAllMocks();vi.unstubAllGlobals();vi.useRealTimers()})

describe('第五批报修回调隔离', () => {
  it.each([200,401,503])('卸载后迟到%s不能改变新组件或释放busy', status => {
    const old = mount(), lease = old.work.begin(); old.unmount()
    const current = mount(), next = current.work.begin(); const auth=useSession();auth.notice='current'
    if(status !== 200) old.work.fail(new ApiError(status,'LATE','late'),lease)
    lease.finish(); expect(lease.current()).toBe(false);expect(current.work.busy.value).toBe(true);expect(auth.phase).toBe('NORMAL');expect(auth.notice).toBe('current');next.finish();expect(current.work.busy.value).toBe(false)
  })
  it.each([200,401,503])('同组件身份attempt切换后旧%s不能生效', status => {
    const {work}=mount(), lease=work.begin();const auth=useSession();auth.identity={...auth.identity!,loginAttemptId:'next'};const next=work.begin()
    if(status!==200)work.fail(new ApiError(status,'LATE','late'),lease)
    lease.finish();expect(lease.current()).toBe(false);expect(work.busy.value).toBe(true);expect(auth.phase).toBe('NORMAL');next.finish()
  })
  it('旧slot和旧操作回调不能释放新操作',()=>{const {work}=mount();let key='old';const old=work.begin(()=>key==='old');key='new';const next=work.begin(()=>key==='new');old.finish();expect(work.busy.value).toBe(true);expect(old.current()).toBe(false);expect(next.current()).toBe(true);next.finish()})
})
const valid = ():RepairInput => ({deviceId:'1',expectedOwnershipVersion:'1',title:'设备无法正常启动',description:'连续三次开机均无法正常启动，电源指示灯持续闪烁。',categoryCode:'UNCERTAIN',impact:'AFFECTED',contactName:'联系人',contactPhone:'+86 (123) 456-789 x1',attachmentIds:[],duplicateAcknowledged:false})
describe('报修文本边界',()=>{
  it('码点计数允许补充平面字符',()=>{const b=valid();b.title='😀'.repeat(5);expect(repairValidation(b)).toBe('');b.title='😀'.repeat(101);expect(repairValidation(b)).toContain('标题')})
  it('描述允许换行，不允许非法代理项/隐藏控制字符',()=>{const b=valid();b.description+='\n\t补充说明';expect(repairValidation(b)).toBe('');b.contactName='\ud800';expect(repairValidation(b)).toContain('不允许');b.contactName='名字\u0000';expect(repairValidation(b)).toContain('不允许')})
  it('电话必须含数字，附件不重复且至多5个',()=>{const b=valid();b.contactPhone='分机';expect(repairValidation(b)).toContain('联系电话');b.contactPhone='123';b.attachmentIds=['a','a'];expect(repairValidation(b)).toContain('附件');b.attachmentIds=['1','2','3','4','5','6'];expect(repairValidation(b)).toContain('附件')})
})
describe('真实READY回执绑定',()=>{
  const file = { size: 10 } as File
  const ready = { fileId:'file',uploadId:'upload',submissionId:'submission',name:'test.png',mediaType:'image/png',size:10,sha256:'a'.repeat(64),state:'READY' }
  it('同上传同提交同内容才接受READY',()=>{expect(verifyUpload(ready,'upload','submission',file,'a'.repeat(64))).toBe(ready)})
  it.each(['uploadId','submissionId','sha256','size','fileId'])('不接受%s不匹配的成功',field=>{expect(()=>verifyUpload({...ready,[field]:field==='size'?11:''},'upload','submission',file,'a'.repeat(64))).toThrow('上传回执')})
})
describe('ID-05原上传内容不可替换',()=>{
  function pending() {
    const file = new File(['original'], 'test.png', { type:'image/png' })
    const f: UploadWork = { key:'upload',file,sha256:'a'.repeat(64),state:'NOT_OBSERVED' }
    const d: RepairDraft = { key:'submission',actor:'actor',body:valid(),frozen:false,files:[f],unknown:false }
    f.original = Object.freeze({actor:d.actor,uploadId:f.key,submissionId:d.key,file:f.file,sha256:f.sha256,deviceId:d.body.deviceId,expectedOwnershipVersion:d.body.expectedOwnershipVersion})
    return {d,f}
  }
  it('保留同一个File对象、键、摘要和设备参数',()=>{const {d,f}=pending();expect(matchesUploadOrigin(d,f)).toBe(true)})
  it.each(['actor','submission','upload','file','sha256','device','version'])('%s变化后不能放行原键重传',field=>{
    const {d,f}=pending()
    if(field==='actor')d.actor='other';if(field==='submission')d.key='other';if(field==='upload')f.key='other';if(field==='file')f.file=new File(['original'],'test.png',{type:'image/png'});if(field==='sha256')f.sha256='b'.repeat(64);if(field==='device')d.body.deviceId='other';if(field==='version')d.body.expectedOwnershipVersion='2'
    expect(matchesUploadOrigin(d,f)).toBe(false)
  })
})
