import {beforeEach,describe,expect,it,vi} from 'vitest'
import {ApprovalWork,retainedApprovalOperations} from '../src/features/approval/approval-work'
import {parseApproval,validateCommand,type ApprovalOwner,type ApprovalTransport,type SubmitInput,type ApprovalView,type WireResponse} from '../src/features/approval/approval-client'
const op='10000000-0000-0000-0000-000000000001',id='20000000-0000-0000-0000-000000000001'
const body:SubmitInput={ticketId:'30000000-0000-0000-0000-000000000001',assignmentId:'40000000-0000-0000-0000-000000000001',departmentId:'11',warehouseId:'11',partId:'101',quantity:1,purpose:'维修更换故障配件'}
let owner:ApprovalOwner
function result():ApprovalView{return{id,submission:{...body,operationId:op,tenantId:'1',applicantId:'10'},state:'PENDING',version:'0',submittedAt:'2026-10-21T01:00:00Z',decision:null,decidedAt:null,revision:{rootApplicationId:id,number:'1',previousApplicationId:null},withdrawal:null,withdrawnAt:null}}
function transport(...responses:WireResponse[]){return{request:vi.fn(async()=>responses.shift()??{status:503,body:{code:'DEPENDENCY_UNAVAILABLE'}})} as ApprovalTransport&{request:ReturnType<typeof vi.fn>}}
function work(t:ApprovalTransport){const value=new ApprovalWork(t,()=>owner,()=>op);value.bind('ticket');return value}
function deferred<T>(){let resolve!:(value:T)=>void;const promise=new Promise<T>(r=>resolve=r);return{promise,resolve}}
beforeEach(()=>{retainedApprovalOperations.clear();owner={tenantId:'1',actorId:'10',generation:1,namespace:'TENANT_EMPLOYEE'}})
describe('approval wire guards',()=>{
 it('accepts explicit cost bearers and rejects an unknown bearer',()=>{
  for(const chargeMode of ['FREE','CUSTOMER_PAID'])validateCommand('submit',{...body,operationId:op,chargeMode})
  for(const chargeMode of ['UNKNOWN','',null])expect(()=>validateCommand('submit',{...body,operationId:op,chargeMode})).toThrow()
  expect(()=>parseApproval({...result(),chargeMode:'INVALID'})).toThrow()
 })
 it('keeps string ID/version and validates terminal fact consistency',()=>{expect(parseApproval(result()).version).toBe('0');expect(()=>parseApproval({...result(),version:0})).toThrow();expect(()=>parseApproval({...result(),state:'APPROVED'})).toThrow();expect(()=>parseApproval({...result(),revision:{...result().revision,number:'0'}})).toThrow()})
 it('requires positive BIGINT strings and a single-line integral quantity',()=>{validateCommand('submit',{...body,operationId:op});for(const patch of [{warehouseId:11},{warehouseId:'9223372036854775808'},{partId:'01'},{quantity:1.5},{quantity:'1'},{tenantId:'2'}])expect(()=>validateCommand('submit',{...body,operationId:op,...patch})).toThrow()})
 it('rejects numeric versions, blank reject reason and non-contract fields',()=>{for(const patch of [{expectedVersion:0},{reason:'a'},{tenantId:'1'}])expect(()=>validateCommand('decision',{operationId:op,expectedVersion:'0',outcome:'REJECT',reason:'维修申请不符合当前范围',...patch})).toThrow()})
})
describe('approval original operation recovery',()=>{
 it.each(['FREE','CUSTOMER_PAID'])('verifies the immutable %s policy through the authorized current read',async chargeMode=>{
  const t=transport({status:200,body:result()},{status:200,body:{...result(),chargeMode}}),w=work(t)
  await w.submit({...body,chargeMode} as SubmitInput)
  expect(w.phase).toBe('SUCCEEDED')
  expect(w.pending).toBeNull()
  expect(t.request.mock.calls.map(c=>[c[0],c[1]])).toEqual([['POST','/api/approval/parts'],['GET',`/api/approval/parts/${id}`]])
  expect(t.request.mock.calls[0]?.[2].chargeMode).toBe(chargeMode)
 })
 it('retains the original command when cost policy is unknown or mismatched',async()=>{
  for(const chargeMode of ['UNKNOWN','CUSTOMER_PAID']){
   retainedApprovalOperations.clear()
   const t=transport({status:200,body:result()},{status:200,body:{...result(),chargeMode}}),w=work(t)
   await w.submit({...body,chargeMode:'FREE'} as SubmitInput)
   expect(w.phase).toBe('UNKNOWN')
   expect(w.pending?.operationId).toBe(op)
   expect(w.detail).toBeNull()
  }
 })
 it('recovers an unavailable policy with the same operation and only GET requests',async()=>{
  const t=transport({status:200,body:result()},{status:503,body:{}},{status:200,body:result()},{status:200,body:{...result(),chargeMode:'FREE'}}),w=work(t)
  await w.submit({...body,chargeMode:'FREE'} as SubmitInput)
  expect(w.phase).toBe('UNKNOWN')
  expect(w.pending?.body.chargeMode).toBe('FREE')
  await w.queryOriginal()
  expect(w.phase).toBe('SUCCEEDED')
  expect(t.request.mock.calls.map(c=>c[0])).toEqual(['POST','GET','GET','GET'])
 })
 it('rejects a cost policy from another submission',async()=>{
  const t=transport({status:200,body:result()},{status:200,body:{...result(),chargeMode:'FREE',submission:{...result().submission,operationId:id}}}),w=work(t)
  await w.submit({...body,chargeMode:'FREE'} as SubmitInput)
  expect(w.phase).toBe('UNKNOWN')
  expect(w.pending?.operationId).toBe(op)
 })
 it('ignores a late cost-policy read after identity changes',async()=>{
  const policy=deferred<WireResponse>()
  const t={request:vi.fn().mockResolvedValueOnce({status:200,body:result()}).mockImplementationOnce(()=>policy.promise)}
  const w=work(t),pending=w.submit({...body,chargeMode:'FREE'})
  await vi.waitFor(()=>expect(t.request).toHaveBeenCalledTimes(2))
  owner={...owner,generation:2}
  w.bind('ticket')
  policy.resolve({status:200,body:{...result(),chargeMode:'FREE'}})
  await pending
  expect(w.phase).toBe('UNKNOWN')
  expect(w.pending?.body.chargeMode).toBe('FREE')
  expect(w.detail).toBeNull()
  expect(w.busy).toBe(false)
 })
 it('503 only queries; exact operation404 enables explicit same key/body post',async()=>{const t=transport({status:503,body:{}},{status:404,body:{code:'OPERATION_NOT_OBSERVED'}},{status:200,body:result()});const w=work(t);await w.submit(body);await w.retryOriginal();expect(t.request).toHaveBeenCalledTimes(1);await w.queryOriginal();expect(w.phase).toBe('NOT_OBSERVED');await w.retryOriginal();expect(w.phase).toBe('SUCCEEDED');expect(t.request.mock.calls[0]?.[2]).toEqual(t.request.mock.calls[2]?.[2]);expect(t.request.mock.calls[2]?.[3]).toEqual({'Idempotency-Key':op})})
 it.each([401,403,503])('query%s never permits replay or a fresh submission',async status=>{const t=transport({status:503,body:{}},{status,body:{code:'OPERATION_NOT_OBSERVED'}});const w=work(t);await w.submit(body);await w.queryOriginal();await w.retryOriginal();await w.submit({...body,quantity:2});expect(t.request).toHaveBeenCalledTimes(2);expect(w.pending?.operationId).toBe(op)})
 it('object404 does not mean original operation was not observed',async()=>{const t=transport({status:503,body:{}},{status:404,body:{code:'TARGET_NOT_ACCESSIBLE'}}),w=work(t);await w.submit(body);await w.queryOriginal();await w.retryOriginal();expect(t.request).toHaveBeenCalledTimes(2);expect(w.phase).toBe('ERROR')})
 it('committed receipt503 recovery never POSTs again',async()=>{const t=transport({status:503,body:{}},{status:200,body:result()}),w=work(t);await w.submit(body);await w.queryOriginal();expect(w.phase).toBe('SUCCEEDED');expect(t.request.mock.calls.map(c=>c[0])).toEqual(['POST','GET']);expect(w.detail?.id).toBe(id)})
 it('rejects changed original parameters, actor and projection',async()=>{for(const change of [{submission:{...result().submission,quantity:2}},{submission:{...result().submission,applicantId:'99'}},{submission:{...result().submission,tenantId:'2'}}]){retainedApprovalOperations.clear();const w=work(transport({status:200,body:{...result(),...change}}));await w.submit(body);expect(w.phase).toBe('UNKNOWN');expect(w.detail).toBeNull();expect(w.pending?.operationId).toBe(op)}})
 it('an original receipt is separate from the current detail GET',async()=>{const t=transport({status:200,body:result()},{status:200,body:{...result(),version:'2'}}),w=work(t);await w.submit(body);expect(w.phase).toBe('SUCCEEDED');await w.load(id);expect(w.phase).toBe('IDLE');expect(w.detail?.version).toBe('2')})
 it('decision and withdrawal use current version with same key guard',async()=>{const approved={...result(),state:'APPROVED',version:'1',decision:{operationId:op,tenantId:'1',actorId:'10',expectedVersion:'0',outcome:'APPROVE',reason:''},decidedAt:'2026-10-21T01:01:00Z'};const t=transport({status:200,body:approved}),w=work(t);await w.decide(id,{expectedVersion:'0',outcome:'APPROVE',reason:''});expect(w.phase).toBe('SUCCEEDED');expect(t.request.mock.calls[0]?.[1]).toBe(`/api/approval/parts/${id}/decisions`);retainedApprovalOperations.clear();const withdrawn={...result(),state:'WITHDRAWN',version:'1',withdrawal:{operationId:op,tenantId:'1',actorId:'10',expectedVersion:'0',reason:''},withdrawnAt:'2026-10-21T01:01:00Z'};const next=work(transport({status:200,body:withdrawn}));await next.withdraw(id,{expectedVersion:'0'});expect(next.phase).toBe('SUCCEEDED')})
})
describe('approval callback ownership',()=>{
 it('identity changes require a fresh binding before any new read/write',async()=>{const t=transport({status:200,body:result()}),w=work(t);owner={...owner,generation:2};await w.submit(body);await w.load(id);expect(t.request).not.toHaveBeenCalled()})
 it.each([200,401,403,503])('identity generation change makes late%s inert',async status=>{const d=deferred<WireResponse>(),t={request:()=>d.promise},w=work(t);const old=w.submit(body);owner={...owner,generation:2};w.bind('ticket');expect(w.phase).toBe('UNKNOWN');d.resolve({status,body:result()});await old;expect(w.phase).toBe('UNKNOWN');expect(w.pending?.operationId).toBe(op);expect(w.detail).toBeNull();expect(w.busy).toBe(false)})
 it('different actors do not inherit pending payloads',async()=>{const w=work(transport({status:503,body:{}}));await w.submit(body);owner={...owner,actorId:'11',generation:2};w.bind('ticket');expect(w.pending).toBeNull();expect(w.detail).toBeNull()})
 it('remount retains original command while old callback cannot release new busy',async()=>{const d=deferred<WireResponse>(),old=work({request:()=>d.promise});const sent=old.submit(body);old.dispose();const q=deferred<WireResponse>(),next=work({request:()=>q.promise});expect(next.pending?.operationId).toBe(op);const queried=next.queryOriginal();expect(next.busy).toBe(true);d.resolve({status:200,body:result()});await sent;expect(next.busy).toBe(true);expect(next.phase).toBe('UNKNOWN');q.resolve({status:200,body:result()});await queried;expect(next.phase).toBe('SUCCEEDED')})
 it('new context draft cannot be polluted by old result',async()=>{const d=deferred<WireResponse>(),w=work({request:()=>d.promise});const old=w.submit(body);w.bind('other-ticket');d.resolve({status:200,body:result()});await old;expect(w.pending).toBeNull();expect(w.detail).toBeNull();expect(w.phase).toBe('IDLE')})
})
