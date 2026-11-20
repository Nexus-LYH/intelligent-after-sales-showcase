import {ApiError,request,type Identity} from '../../api'
import {captureRequestScope} from '../../request-scope'
import type {useSession} from '../../session'
import {decimal,uuid} from '../finance/finance-client'
export type MoneyAction='assess'|'lower'|'collect'|'reverse'|'refund'|'rebook'
export const moneyActions:Record<MoneyAction,string>={assess:'RECEIVABLE_ASSESSED',lower:'RECEIVABLE_LOWERED',collect:'COLLECTION_RECORDED',reverse:'COLLECTION_REVERSED',refund:'REFUND_VOUCHER_RECORDED',rebook:'COLLECTION_REBOOKED'}
export const canReadMoney=(m:Identity|null)=>!!m&&m.realm==='TENANT'&&m.identityType==='EMPLOYEE'&&m.permissions.includes('finance.money.read')
export const canWriteMoney=(m:Identity|null,a:MoneyAction)=>canReadMoney(m)&&m!.permissions.includes('finance.money.'+({lower:'adjust',rebook:'reverse'}[a as 'lower'|'rebook']??a))&&(a!=='rebook'||m!.permissions.includes('finance.money.collect'))
export interface Balance{version:string;currency:'CNY';receivableCents:string;collectedCents:string;refundedCents:string;settledCents:string;outstandingCents:string;overpaymentCents:string;notice:string}
const obj=(v:unknown):v is Record<string,unknown>=>!!v&&typeof v==='object'&&!Array.isArray(v)
export function balance(v:unknown):Balance{if(!obj(v)||v.currency!=='CNY'||v.notice!=='人工登记，未接支付渠道'||!['version','receivableCents','collectedCents','refundedCents','settledCents','outstandingCents','overpaymentCents'].every(k=>decimal(v[k])))throw Error('资金余额无法确认');if(BigInt(String(v.collectedCents))-BigInt(String(v.refundedCents))!==BigInt(String(v.settledCents))+BigInt(String(v.overpaymentCents)))throw Error('资金事实不一致');return v as unknown as Balance}
export function receipt(v:unknown,group:string,key:string){if(!obj(v)||v.groupId!==group||v.operationId!==key||!decimal(v.groupVersion)||!Object.values(moneyActions).includes(String(v.action)))throw Error('原资金回执归属无法确认');return {operationId:key,groupId:group,groupVersion:v.groupVersion,action:String(v.action),balance:balance(v.balance)}}
export function yuan(v:string){if(!decimal(v))throw Error('金额无效');return (BigInt(v)/100n)+'.'+(BigInt(v)%100n).toString().padStart(2,'0')}
export function cents(v:string){if(!/^(0|[1-9][0-9]{0,15})(\.\d{1,2})?$/.test(v))throw Error('请输入最多两位小数的非负金额');const [a,b='']=v.split('.');const n=(BigInt(a!)*100n+BigInt(b.padEnd(2,'0'))).toString();if(!decimal(n))throw Error('金额超出范围');return n}
export function moneyTransport(auth:ReturnType<typeof useSession>,active:()=>boolean){return async(method:'GET'|'POST',group:string,suffix='',body?:Record<string,unknown>,current:()=>boolean=()=>true)=>{
 const scope=captureRequestScope(auth,()=>active()&&current());if(!scope.current()||!canReadMoney(auth.identity)||!uuid(group))throw Error('当前资金身份未确认')
 if(!(method==='GET'&&(/^$|^\/entries\?afterVersion=(0|[1-9][0-9]{0,18})&limit=20$/.test(suffix)||suffix.startsWith('/operations/')&&uuid(suffix.slice(12)))||method==='POST'&&/^\/(assess|lower|collect|reverse|refund|rebook)$/.test(suffix)))throw Error('资金路径无效')
 const csrf=auth.csrf;if(method==='POST'&&(!canWriteMoney(auth.identity,suffix.slice(1) as MoneyAction)||!body||!uuid(body.operationId)||!csrf||csrf.contextKind!=='AUTH'||csrf.loginAttemptId!==auth.identity?.loginAttemptId||!Number.isFinite(Date.parse(csrf.expiresAt))||Date.parse(csrf.expiresAt)<=Date.now()))throw Error('当前操作权限或CSRF未确认')
 try{const value=await request<unknown>('/finance/groups/'+group+'/money'+suffix,{method,...(body?{body:JSON.stringify(body),headers:{'Idempotency-Key':String(body.operationId),'X-CSRF-TOKEN':csrf!.token}}:{})});if(!scope.current())throw Error('原资金请求上下文已失效');return value}catch(e){if(scope.current()&&e instanceof ApiError&&(e.status===401||e.code==='CSRF_INVALID'))auth.handleFailure(e);throw e}
}}
