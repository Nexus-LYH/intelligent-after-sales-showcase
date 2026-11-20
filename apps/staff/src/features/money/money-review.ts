import {ApiError,request} from '../../api'
import {captureRequestScope} from '../../request-scope'
import type {useSession} from '../../session'
import {uuid,decimal,canConfirmFinance} from '../finance/finance-client'
import {canReadMoney,canWriteMoney} from './money-client'

export interface CorrectionReview {
 reviewId:string;kind:'CORRECTION';applicantUserId:string;originalPaymentId:string;proposedCents:string;
 accountVersion:string;groupVersion:string;evidenceFileId:string;evidenceDigest:string;reason:string;events:string[];
 context:{ticketId:string;originalAmountCents:string;originalEvidenceFileId:string;originalEvidenceDigest:string;originalReference:string;originalDate:string;originalMethod:string;currentAccountVersion:string;originalReversed:boolean}
}
const hash=(v:unknown):v is string=>typeof v==='string'&&/^[a-f0-9]{64}$/.test(v)
export function correctionReview(value:unknown,id:string):CorrectionReview {
 const r=value as CorrectionReview,c=r?.context
 if(!r||r.reviewId!==id||r.kind!=='CORRECTION'||!decimal(r.applicantUserId)||!uuid(r.originalPaymentId)||!decimal(r.proposedCents)||!decimal(r.accountVersion)||!decimal(r.groupVersion)||!uuid(r.evidenceFileId)||!hash(r.evidenceDigest)||typeof r.reason!=='string'||!Array.isArray(r.events)||r.events.some(e=>!['SUBMITTED','CORRECTION_VERIFIED'].includes(e))||!c||!uuid(c.ticketId)||!decimal(c.originalAmountCents)||!uuid(c.originalEvidenceFileId)||!hash(c.originalEvidenceDigest)||!decimal(c.currentAccountVersion)||typeof c.originalReversed!=='boolean'||!['originalReference','originalDate','originalMethod'].every(k=>typeof c[k as keyof typeof c]==='string'))throw Error('复核记录或证据归属无法确认')
 return r
}
export type ReviewAction='submit'|'confirm'|'approve'|'verify'
export interface ReviewSummary{reviewId:string;kind:'REDUCTION'|'CORRECTION';billId:string;lineId:string|null;proposedCents:string;accountVersion:string;groupVersion:string;coverageDigest:string|null;reason:string;events:string[];applicantUserId?:string}
export const canReadReviews=(identity:Parameters<typeof canReadMoney>[0])=>canReadMoney(identity)||canConfirmFinance(identity)
export function parseReviewSummary(value:unknown,id:string):ReviewSummary{
 const r=value as ReviewSummary
 if(!r||r.reviewId!==id||!['REDUCTION','CORRECTION'].includes(r.kind)||!uuid(r.billId)||!decimal(r.proposedCents)||!decimal(r.accountVersion)||!decimal(r.groupVersion)||typeof r.reason!=='string'||!Array.isArray(r.events)||r.events.some(event=>!['SUBMITTED','CUSTOMER_CONFIRMED','FINANCE_APPROVED','CORRECTION_VERIFIED'].includes(event)))throw Error('审批记录无法确认')
 if(r.kind==='REDUCTION'&&(!uuid(r.lineId)||!hash(r.coverageDigest)))throw Error('应收调整依据无法确认')
 if(r.applicantUserId!==undefined&&(!decimal(r.applicantUserId)||r.applicantUserId==='0'))throw Error('申请人标识无法确认')
 return r
}
export function reviewTransport(auth:ReturnType<typeof useSession>,active:()=>boolean){return async(method:'GET'|'POST',group:string,review:string,key?:string,body?:Record<string,unknown>,current:()=>boolean=()=>true,action:ReviewAction='verify')=>{
 const scope=captureRequestScope(auth,()=>active()&&current())
 if(!scope.current()||!canReadReviews(auth.identity)||!uuid(group)||!uuid(review)||key!==undefined&&!uuid(key))throw Error('当前复核上下文未确认')
 const allowed=action==='confirm'?canConfirmFinance(auth.identity):canWriteMoney(auth.identity,action==='approve'||action==='submit'&&body?.kind==='REDUCTION'?'lower':'reverse')
 const csrf=auth.csrf
 if(method==='POST'&&(!allowed||!key||body?.operationId!==key||!csrf||csrf.contextKind!=='AUTH'||csrf.loginAttemptId!==auth.identity?.loginAttemptId||!Number.isFinite(Date.parse(csrf.expiresAt))||Date.parse(csrf.expiresAt)<=Date.now()))throw Error('当前复核权限或CSRF未确认')
 try{
  const suffix=method==='POST'?'/'+action:key?'/operations/'+key:''
  const path='/finance/groups/'+group+'/money/reviews'+(method==='POST'&&action==='submit'?'':'/'+review+suffix)
  const result=await request<unknown>(path,{method,...(method==='POST'?{body:JSON.stringify(body),headers:{'Idempotency-Key':key!,'X-CSRF-TOKEN':csrf!.token}}:{})})
  if(!scope.current())throw Error('原复核请求上下文已失效');return result
 }catch(e){if(scope.current()&&e instanceof ApiError&&(e.status===401||e.code==='CSRF_INVALID'))auth.handleFailure(e);throw e}
}}
interface PendingReview {group:string;review:string;key:string;body:Record<string,unknown>;action?:ReviewAction;event?:string}
export const retainedReviews=new Map<string,{pending:PendingReview|null;lease:number}>()
export class CorrectionReviewWork {
 pending:PendingReview|null=null;busy=false;phase='IDLE';error=''
 private alive=true;private epoch=0;private lease=0;private slot='';private stamp=''
 constructor(private send:ReturnType<typeof reviewTransport>,private owner:()=>{slot:string;stamp:string}|null){}
 bind(){this.epoch++;this.busy=false;this.error='';this.phase='IDLE';this.pending=null;const o=this.owner();this.slot=o?.slot??'';this.stamp=o?.stamp??'';if(!o)return;let stored=retainedReviews.get(o.slot);if(!stored){stored={pending:null,lease:0};retainedReviews.set(o.slot,stored)}this.lease=++stored.lease;this.pending=stored.pending;if(this.pending)this.phase='UNKNOWN'}
 dispose(){this.alive=false;this.epoch++}
 async start(group:string,review:CorrectionReview,verifiedCents:string,note:string){
  const stored=retainedReviews.get(this.slot)
  if(!this.alive||this.busy||this.pending||!stored||stored.lease!==this.lease||this.owner()?.stamp!==this.stamp)return
  if(!decimal(verifiedCents)||verifiedCents!==review.proposedCents||!note.trim()||note.length>500)throw Error('复核金额须与本申请一致；有差异请重新申请，并填写复核说明')
  const key=crypto.randomUUID();this.pending=Object.freeze({group,review:review.reviewId,key,body:Object.freeze({operationId:key,verifiedCents,evidenceFileId:review.evidenceFileId,evidenceDigest:review.evidenceDigest,reviewerNote:note})});stored.pending=this.pending;await this.perform(false)
 }
 async startProposal(group:string,proposal:Record<string,unknown>){
  if(!uuid(group)||!uuid(proposal.reviewId)||!uuid(proposal.billId)||!uuid(proposal.subjectId)||!decimal(proposal.accountVersion)||!decimal(proposal.groupVersion)||!decimal(proposal.proposedCents)||typeof proposal.reason!=='string'||!proposal.reason.trim()||proposal.reason.length>500)throw Error('请核对调查或调整申请字段')
  if(proposal.kind==='REDUCTION'){
   if(!uuid(proposal.lineId)||proposal.subjectId!==proposal.lineId||!hash(proposal.coverageDigest)||proposal.evidenceFileId!==null||proposal.evidenceDigest!==null)throw Error('应收调整依据无效')
  }else if(proposal.kind==='CORRECTION'){
   if(proposal.lineId!==null||!uuid(proposal.evidenceFileId)||!hash(proposal.evidenceDigest)||proposal.coverageDigest!==null)throw Error('错录调查证据无效')
  }else throw Error('申请类型无效')
  await this.begin(group,proposal.reviewId,'submit','SUBMITTED',proposal)
 }
 async startDecision(group:string,review:ReviewSummary,action:'confirm'|'approve'){
  if(review.kind!=='REDUCTION'||review.events.includes(action==='confirm'?'CUSTOMER_CONFIRMED':'FINANCE_APPROVED')||action==='approve'&&!review.events.includes('CUSTOMER_CONFIRMED'))throw Error('请重新查询当前审批节点')
  await this.begin(group,review.reviewId,action,action==='confirm'?'CUSTOMER_CONFIRMED':'FINANCE_APPROVED',{})
 }
 private async begin(group:string,review:string,action:ReviewAction,event:string,body:Record<string,unknown>){
  const stored=retainedReviews.get(this.slot)
  if(!this.alive||this.busy||this.pending||!stored||stored.lease!==this.lease||this.owner()?.stamp!==this.stamp)return
  const key=crypto.randomUUID()
  this.pending=Object.freeze({group,review,key,action,event,body:Object.freeze(structuredClone({...body,operationId:key}))})
  stored.pending=this.pending;await this.perform(false)
 }
 async query(){if(this.pending)await this.perform(true)}
 async retry(){if(this.phase==='NOT_OBSERVED'&&this.pending)await this.perform(false)}
 private async perform(query:boolean){
  const p=this.pending;if(!p||this.busy||!this.alive)return
  const epoch=++this.epoch,stamp=this.stamp,slot=this.slot,lease=this.lease,current=()=>this.alive&&epoch===this.epoch&&this.owner()?.stamp===stamp&&retainedReviews.get(slot)?.lease===lease
  this.busy=true;this.error=''
  try{const value=await this.send(query?'GET':'POST',p.group,p.review,p.key,query?undefined:p.body,current,p.action) as {operationId:string;reviewId:string;event:string};if(!current())return
   if(!value||value.operationId!==p.key||value.reviewId!==p.review||value.event!==(p.event??'CORRECTION_VERIFIED'))throw Error('原复核操作回执不匹配')
   this.phase='SUCCEEDED';this.pending=null;retainedReviews.get(slot)!.pending=null
  }catch(e){if(current()){this.phase=query&&e instanceof ApiError&&e.status===404?'NOT_OBSERVED':e instanceof ApiError&&[401,403,409].includes(e.status)?'DENIED':'UNKNOWN';this.error=e instanceof Error?e.message:'复核结果未知，请查询原操作'}}finally{if(current())this.busy=false}
 }
}
