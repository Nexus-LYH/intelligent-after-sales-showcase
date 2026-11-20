import {describe,it,expect,vi} from 'vitest'
import {ApiError} from '../src/api'
import {CorrectionReviewWork,retainedReviews,parseReviewSummary,type ReviewSummary,type CorrectionReview} from '../src/features/money/money-review'
const group='11111111-1111-4111-8111-111111111111'
const review={reviewId:group,proposedCents:'100',evidenceFileId:group,evidenceDigest:'a'.repeat(64)} as CorrectionReview
describe('independent correction review recovery',()=>{
 it('requires an explicit matching amount and explanation before any write',async()=>{
  retainedReviews.clear();const send=vi.fn(),w=new CorrectionReviewWork(send,()=>({slot:'a',stamp:'1'}));w.bind()
  await expect(w.start(group,review,'99','checked')).rejects.toThrow()
  await expect(w.start(group,review,'100',' ')).rejects.toThrow()
  expect(send).not.toHaveBeenCalled();expect(w.pending).toBeNull()
 })
 it('queries a lost response and retries only the original immutable attestation',async()=>{
  retainedReviews.clear();const calls:any[]=[]
  const send=vi.fn(async(...args:any[])=>{calls.push(args);if(calls.length===1)throw Error('lost');if(calls.length===2)throw new ApiError(404,'NOT_FOUND','missing');return {operationId:args[3],reviewId:group,event:'CORRECTION_VERIFIED'}})
  const w=new CorrectionReviewWork(send,()=>({slot:'a',stamp:'1'}));w.bind();await w.start(group,review,'100','凭据核对完成')
  expect(w.phase).toBe('UNKNOWN');await w.retry();expect(calls).toHaveLength(1)
  await w.query();expect(w.phase).toBe('NOT_OBSERVED');await w.retry()
  expect(calls[2][3]).toBe(calls[0][3]);expect(calls[2][4]).toEqual(calls[0][4]);expect(w.phase).toBe('SUCCEEDED')
 })
 it('retains an unresolved original operation when the reviewer context changes',async()=>{
  retainedReviews.clear();let actor='1',resolve!:(v:unknown)=>void
  const send=()=>new Promise(r=>{resolve=r}),w=new CorrectionReviewWork(send,()=>({slot:actor,stamp:actor}));w.bind()
  const running=w.start(group,review,'100','checked'),key=w.pending!.key;actor='2';w.bind()
  resolve({operationId:key,reviewId:group,event:'CORRECTION_VERIFIED'});await running
  expect(w.pending).toBeNull();expect(retainedReviews.get('1')!.pending?.key).toBe(key)
 })
})

const proposal=()=>({reviewId:group,kind:'REDUCTION',accountVersion:'3',groupVersion:'3',subjectId:group,billId:group,lineId:group,proposedCents:'500',coverageDigest:'a'.repeat(64),evidenceFileId:null,evidenceDigest:null,reason:'客户申请降低其他费用'})
it('submits a fixed review proposal and recovers its SUBMITTED event with the same UUID',async()=>{
 retainedReviews.clear()
 const send=vi.fn().mockRejectedValueOnce(new Error('lost')).mockImplementationOnce(async(...args:any[])=>({operationId:args[3],reviewId:group,event:'SUBMITTED'}))
 const work=new CorrectionReviewWork(send,()=>({slot:'proposal',stamp:'1'}));work.bind()
 await work.startProposal(group,proposal())
 expect(work.phase).toBe('UNKNOWN')
 expect(work.pending?.action).toBe('submit')
 const key=work.pending!.key
 await work.query()
 expect(work.phase).toBe('SUCCEEDED')
 expect(send.mock.calls[1]?.[3]).toBe(key)
 expect(send.mock.calls[0]?.[6]).toBe('submit')
})
it('validates the reduction source before committing any proposal',async()=>{
 retainedReviews.clear()
 const send=vi.fn(),work=new CorrectionReviewWork(send,()=>({slot:'invalid',stamp:'1'}));work.bind()
 await expect(work.startProposal(group,{...proposal(),subjectId:'bad'})).rejects.toThrow()
 await expect(work.startProposal(group,{...proposal(),coverageDigest:null})).rejects.toThrow()
 expect(send).not.toHaveBeenCalled()
})
it('keeps customer confirmation and independent approval as distinct current events',async()=>{
 retainedReviews.clear()
 const send=vi.fn(async(...args:any[])=>({operationId:args[3],reviewId:group,event:args[6]==='confirm'?'CUSTOMER_CONFIRMED':'FINANCE_APPROVED'}))
 const work=new CorrectionReviewWork(send,()=>({slot:'decision',stamp:'1'}));work.bind()
 const summary={...proposal(),events:['SUBMITTED']} as ReviewSummary
 await expect(work.startDecision(group,summary,'approve')).rejects.toThrow()
 await work.startDecision(group,summary,'confirm')
 expect(work.phase).toBe('SUCCEEDED')
 await work.startDecision(group,{...summary,events:['SUBMITTED','CUSTOMER_CONFIRMED']},'approve')
 expect(work.phase).toBe('SUCCEEDED')
 expect(send.mock.calls.map(args=>args[6])).toEqual(['confirm','approve'])
})
it('accepts public reduction metadata while rejecting missing coverage',()=>{
 const summary={...proposal(),events:['SUBMITTED']}
 expect(parseReviewSummary(summary,group).kind).toBe('REDUCTION')
 expect(()=>parseReviewSummary({...summary,coverageDigest:null},group)).toThrow()
 expect(()=>parseReviewSummary({...summary,applicantUserId:'0'},group)).toThrow()
 expect(()=>parseReviewSummary({...summary,applicantUserId:'invalid'},group)).toThrow()
})
