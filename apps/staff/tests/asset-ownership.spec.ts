import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import { createRenderer } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { useSession } from '../src/session'
import { useAssetWork } from '../src/asset-work'
import { api, ApiError, type Identity } from '../src/api'
import { assetBody, formOf, validateAsset } from '../src/asset-forms'
import type { AssetRecord } from '../src/asset-api'
const renderer = createRenderer<object, object>({ patchProp() {}, insert() {}, remove() {}, createElement: () => ({}), createText: () => ({}), createComment: () => ({}), setText() {}, setElementText() {}, parentNode: () => null, nextSibling: () => null })
const me: Identity = { userId:'asset-owner', account:'owner',displayName:'Owner',realm:'TENANT',identityType:'EMPLOYEE',tenant:{id:'asset-tenant',code:'AA',name:'Tenant'},department:null,permissions:['asset.read','asset.manage','identity.customer.provision'],accessState:'NORMAL',loginAttemptId:'attempt',expiresAt:'2099-01-01T00:00:00Z' }
let pinia: ReturnType<typeof createPinia>
const mounted: Array<() => void> = []
function mount() { let work!: ReturnType<typeof useAssetWork>; const app=renderer.createApp({setup(){work=useAssetWork(()=>'/assets/companies/1');return ()=>null}});app.use(pinia);app.mount({});mounted.push(()=>app.unmount());return {work,unmount:()=>app.unmount()} }
beforeEach(()=>{vi.useFakeTimers();pinia=createPinia();setActivePinia(pinia);const values=new Map<string,string>();vi.stubGlobal('sessionStorage',{getItem:(k:string)=>values.get(k)??null,setItem:(k:string,v:string)=>values.set(k,v),removeItem:(k:string)=>values.delete(k)});vi.stubGlobal('window',{addEventListener:vi.fn()});vi.stubGlobal('BroadcastChannel',undefined);const auth=useSession();auth.identity={...me,loginAttemptId:crypto.randomUUID()};auth.phase='NORMAL'})
afterEach(()=>{for(const unmount of mounted.splice(0))unmount();vi.restoreAllMocks();vi.unstubAllGlobals();vi.useRealTimers()})
describe('第四批资产回调与恢复归属',()=>{
  it.each([500,502])('代理%s保留资产原操作并关闭旧身份正文',status=>{const {work}=mount(),auth=useSession(),original=auth.identity!,pending=work.remember('companies','1',false,{expectedVersion:'2',name:'Changed'}),lease=work.begin(pending);work.fail(new ApiError(status,'PROXY_ERROR','unknown'),lease);expect(auth.phase).toBe('IDENTITY_UNAVAILABLE');expect(auth.identity).toBeNull();auth.identity=original;auth.phase='NORMAL';expect(work.owns(pending)).toBe(true);expect(work.isActive(pending)).toBe(false);work.complete(pending)})
  it.each([500,502])('改密收到代理%s不能视为失败后自动重填',async status=>{const auth=useSession();auth.csrf={token:'synthetic',headerName:'X-CSRF-TOKEN',contextKind:'AUTH',loginAttemptId:auth.identity!.loginAttemptId,expiresAt:me.expiresAt};const write=vi.spyOn(api,'password').mockRejectedValue(new ApiError(status,'PROXY_ERROR','unknown'));expect(await auth.changePassword({currentPassword:'synthetic-old',newPassword:'synthetic-new-password',confirmPassword:'synthetic-new-password'})).toBe(false);expect(auth.phase).toBe('PASSWORD_RESULT_UNKNOWN');expect(auth.identity).toBeNull();expect(write).toHaveBeenCalledTimes(1)})
  it.each([200,401,503])('卸载后迟到%s及finally不影响重挂载的原操作恢复',status=>{
    const old=mount(), pending=old.work.remember('companies','1',true,{account:'customer',displayName:'Customer'}), lease=old.work.begin(pending)
    expect(old.work.isActive(pending)).toBe(true);old.unmount()
    const next=mount(), current=next.work.begin(pending);expect(current.current()).toBe(true)
    if(status===200){if(lease.current())old.work.complete(pending)}else old.work.fail(new ApiError(status,'LATE','late'),lease)
    old.work.finish(lease)
    expect(useSession().phase).toBe('NORMAL');expect(next.work.loading.value).toBe(true);expect(next.work.isActive(pending)).toBe(true);expect(next.work.owns(pending)).toBe(true)
    next.work.finish(current);next.work.complete(pending);expect(next.work.isActive(pending)).toBe(false);expect(next.work.owns(pending)).toBe(false)
  })
  it('同组件新slot的busy不能被旧finally清除',()=>{const {work}=mount();const old=work.begin(), latest=work.begin();work.finish(old);expect(work.loading.value).toBe(true);expect(latest.current()).toBe(true);work.finish(latest);expect(work.loading.value).toBe(false)})
  it('同actor换attempt后旧失败不清新身份',()=>{const {work}=mount(), old=work.begin();const auth=useSession();auth.identity={...auth.identity!,loginAttemptId:'new-attempt'};auth.notice='new-notice';work.fail(new ApiError(401,'LATE','late'),old);expect(auth.identity?.loginAttemptId).toBe('new-attempt');expect(auth.notice).toBe('new-notice')})
  it('清操作须比较目标及原内容；开通草稿防御性排除密码',()=>{const {work}=mount(),pending=work.remember('companies','1',true,{account:'customer',displayName:'Customer',initialPassword:'synthetic-never-persist'});expect(pending.body).toEqual({account:'customer',displayName:'Customer'});work.complete({...pending,target:'other'});expect(work.owns(pending)).toBe(true);work.complete({...pending,body:{account:'other',displayName:'Customer'}});expect(work.owns(pending)).toBe(true);work.complete(pending)})
  it('企业/namespace变化不暴露原操作引用',()=>{const {work}=mount(),pending=work.remember('companies','1');const auth=useSession();auth.identity={...auth.identity!,tenant:{id:'different',code:'BB',name:'B'}};expect(work.owned.value.some(p=>p.key===pending.key)).toBe(false);auth.identity={...auth.identity!,tenant:me.tenant};work.complete(pending)})
})
describe('第四批设备契约边界',()=>{
  it('编辑仅发送允许字段及原版本，不发送归属/型号/编号/序列号',()=>{const item:AssetRecord={id:'1',code:'D1',name:'Device',serialNumber:'S1',companyId:'2',modelId:'3',status:'ENABLED',rowVersion:'8',updatedAt:''};const form=formOf('devices',item);form.companyId='999';form.modelId='999';form.code='CHANGED';form.serialNumber='CHANGED';const body=assetBody('devices',form,item);expect(body.expectedVersion).toBe('8');for(const key of ['companyId','modelId','code','serialNumber'])expect(body).not.toHaveProperty(key)})
  it('不完整保修与购入晚于保修结束不能提交',()=>{const form:Record<string,string>={...formOf('devices'),name:'Device',code:'D1',serialNumber:'S1',companyId:'2',modelId:'3',warrantyStart:'2026-10-01'};expect(validateAsset('devices',form)).toContain('同时');form.warrantyEnd='2027-10-01';form.purchaseDate='2028-10-01';expect(validateAsset('devices',form)).toContain('购入日期')})
})
