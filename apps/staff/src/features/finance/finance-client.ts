import {ApiError,request,type Identity} from '../../api'
import {captureRequestScope} from '../../request-scope'
import type {useSession} from '../../session'
import {uuid,id} from '../stock/stock-client'
export {uuid}
export const decimal=(v:unknown):v is string=>typeof v==='string'&&/^(0|[1-9][0-9]{0,18})$/.test(v)&&BigInt(v)<=9223372036854775807n
export interface FinanceOwner{tenant:string;actor:string;generation:number;attempt:string;type:string}
export function financeOwner(auth:ReturnType<typeof useSession>):FinanceOwner|null{const m=auth.identity;return auth.phase==='NORMAL'&&m?.realm==='TENANT'&&m.tenant&&['EMPLOYEE','CUSTOMER'].includes(m.identityType)?{tenant:m.tenant.id,actor:m.userId,generation:auth.generation,attempt:m.loginAttemptId,type:m.identityType}:null}
export const canReadFinance=(m:Identity|null)=>!!m&&m.realm==='TENANT'&&['EMPLOYEE','CUSTOMER'].includes(m.identityType)&&m.permissions.includes('finance.quote.read')
export const canWriteFinance=(m:Identity|null)=>canReadFinance(m)&&m!.identityType==='EMPLOYEE'&&m!.permissions.includes('finance.quote.write')
export const canConfirmFinance=(m:Identity|null)=>canReadFinance(m)&&m!.identityType==='CUSTOMER'&&m!.permissions.includes('finance.quote.confirm')
export interface FinanceTransport{send<T>(method:'GET'|'POST',path:string,body?:Record<string,unknown>,current?:()=>boolean,key?:string):Promise<T>}
export function financeTransport(auth:ReturnType<typeof useSession>,active:()=>boolean):FinanceTransport{
 const u='[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}',group=new RegExp('^/finance/tickets/'+u+'/group$'),read=new RegExp('^/finance/groups/'+u+'/(?:quotes|operations|quote-sources)/'+u+'$'),write=new RegExp('^/finance/groups/'+u+'/(?:quotes|quotes/'+u+'/confirm|quote-sources/'+u+'/abort)$'),references=new RegExp('^/ticket/tickets/'+u+'/finance-quote-references(?:\\?beforeGeneration=[1-9][0-9]{0,18})?$')
 const catalog=new RegExp('^(?:/ticket/tickets/'+u+'|/approval/parts(?:/'+u+'|\\?ticketId='+u+'&page=[1-9][0-9]{0,5}&size=20)|/stock/warehouses/[1-9][0-9]{0,18}/parts\\?ticketId='+u+'&assignmentId='+u+'&page=[1-9][0-9]{0,5}&size=20)$')
 return{async send<T>(method:'GET'|'POST',path:string,body?:Record<string,unknown>,current=()=>true,key?:string){
 const scope=captureRequestScope(auth,()=>active()&&current()),o=financeOwner(auth)
 if(!o||!scope.current()||!(group.test(path)||(method==='GET'?read:write).test(path)||method==='GET'&&(references.test(path)||o.type==='EMPLOYEE'&&catalog.test(path))))throw new ApiError(403,'FINANCE_CONTEXT_UNCONFIRMED','当前费用上下文未确认')
 const csrf=auth.csrf
 if(method==='POST'&&(!csrf||csrf.contextKind!=='AUTH'||csrf.loginAttemptId!==auth.identity?.loginAttemptId||Date.parse(csrf.expiresAt)<=Date.now()||!Number.isFinite(Date.parse(csrf.expiresAt))||!uuid(key)||body?.operationId!==key))throw new ApiError(403,'CSRF_CONTEXT_UNCONFIRMED','请重新核验当前会话')
 try{const r=await request<T>(path,{method,...(body?{body:JSON.stringify(body)}:{}),headers:method==='POST'?{'X-CSRF-TOKEN':csrf!.token,'Idempotency-Key':key!}:{}});if(!scope.current())throw new ApiError(0,'STALE_CONTEXT','原请求已失效');return r}catch(e){if(scope.current()&&e instanceof ApiError&&(e.status===401||e.code==='CSRF_INVALID'))auth.handleFailure(e);throw e}
 }}
}
export interface FinanceGroup{groupId:string;version:string;currentQuoteId?:string|null}
export function parseGroup(v:FinanceGroup){if(!v||!uuid(v.groupId)||!decimal(v.version)||v.currentQuoteId!==undefined&&v.currentQuoteId!==null&&!uuid(v.currentQuoteId))throw Error('费用组投影无法确认');return{groupId:v.groupId,version:v.version,...(v.currentQuoteId!==undefined?{currentQuoteId:v.currentQuoteId}:{})}}
export interface FinanceQuote{quoteId:string;quoteVersion:string;chargesCustomer:boolean;pricingDescription:string;lines:{id:string;ticketId:string;roundId:string;amountCents:string;kind:string}[];current:boolean;termination:string;customerDecision:string;approvalRequestState:string}
/** Whitelist public fields even on employee responses: never retain/render internalBasis. */
export function parseQuote(q:FinanceQuote,quoteId:string):FinanceQuote{if(!q||q.quoteId!==quoteId||!uuid(q.quoteId)||!decimal(q.quoteVersion)||typeof q.chargesCustomer!=='boolean'||typeof q.pricingDescription!=='string'||typeof q.current!=='boolean'||!['NONE','CUSTOMER_QUOTE_REJECTED','QUOTE_WITHDRAWN'].includes(q.termination)||!['PENDING','CONFIRMED','REJECTED','NOT_REQUIRED'].includes(q.customerDecision)||!['NOT_CREATED','RECORDED_LOCALLY'].includes(q.approvalRequestState)||!Array.isArray(q.lines)||q.lines.length<1||q.lines.length>50||q.lines.some(l=>!l||!uuid(l.id)||!uuid(l.ticketId)||!uuid(l.roundId)||!id(l.amountCents)||!['LABOR','PART','OTHER'].includes(l.kind))||new Set(q.lines.map(l=>l.id)).size!==q.lines.length)throw Error('报价版本或公开投影无法确认');return{quoteId:q.quoteId,quoteVersion:q.quoteVersion,chargesCustomer:q.chargesCustomer,pricingDescription:q.pricingDescription,lines:q.lines.map(l=>({id:l.id,ticketId:l.ticketId,roundId:l.roundId,amountCents:l.amountCents,kind:l.kind})),current:q.current,termination:q.termination,customerDecision:q.customerDecision,approvalRequestState:q.approvalRequestState}}
export interface FinanceReceipt{operationId:string;action:string;groupId:string;groupVersion:string;quoteId?:string}
export interface FinanceSource{tenantId:string;actorUserId:string;companyId:string;groupId:string;operationId:string;quoteId:string;expectedGroupVersion:string;commandDigest:string;sourceState:'IN_PROGRESS'|'COMMITTED'|'ABORTED';state:string;resultDigest:string|null;checkedAt:string}
export interface QuoteReference{groupId:string;quoteId:string;quoteVersion:string;roundId:string;generation:string}
export interface QuoteReferences{items:QuoteReference[];nextBeforeGeneration:string|null}
export function parseReferences(r:QuoteReferences,groupId:string,before:string|null){if(!r||!Array.isArray(r.items)||r.items.length>20||r.items.some((v,i)=>!v||v.groupId!==groupId||!uuid(v.quoteId)||!uuid(v.roundId)||!decimal(v.quoteVersion)||!decimal(v.generation)||v.generation==='0'||before!==null&&BigInt(v.generation)>=BigInt(before)||i>0&&BigInt(v.generation)>=BigInt(r.items[i-1]!.generation))||new Set(r.items.map(v=>v.quoteId)).size!==r.items.length||r.nextBeforeGeneration!==null&&(!decimal(r.nextBeforeGeneration)||r.items.length===0||r.nextBeforeGeneration!==r.items[r.items.length-1]!.generation))throw Error('报价引用目录归属或分页无法确认');return{items:r.items.map(v=>({groupId:v.groupId,quoteId:v.quoteId,quoteVersion:v.quoteVersion,roundId:v.roundId,generation:v.generation})),nextBeforeGeneration:r.nextBeforeGeneration}}
