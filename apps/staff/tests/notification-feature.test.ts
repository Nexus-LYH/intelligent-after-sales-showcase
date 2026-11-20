import {describe,it,expect,vi} from 'vitest'
import {ApiError,type Identity,type Csrf,type request} from '../src/api'
import {notificationOwner,notificationTransport,parseMessage,parseMessagePage,type NotificationSession,type NotificationTransport} from '../src/features/notification/notification-client'
import {NotificationReads,type ReadSlot} from '../src/features/notification/notification-read'
const message='11111111-1111-4111-8111-111111111111',operation='22222222-2222-4222-8222-222222222222',approval='33333333-3333-4333-8333-333333333333'
const at='2026-10-22T01:00:00.000Z'
function auth(){return {phase:'NORMAL',generation:1,identity:{userId:'7',realm:'TENANT',identityType:'EMPLOYEE',tenant:{id:'2'},loginAttemptId:'attempt'} as Identity,csrf:{token:'synthetic-current-csrf',contextKind:'AUTH',loginAttemptId:'attempt',expiresAt:'2099-01-01T00:00:00Z'} as Csrf,handleFailure:vi.fn()} as NotificationSession}
function fact(){return {id:message,type:'approval.submitted',title:'有新的备件申请',body:'请核对当前申请。',createdAt:at,occurredAt:at,read:false,readAt:null,object:{type:'APPROVAL_PARTS',id:approval,route:'/approvals/parts/'+approval}}}
function receipt(){return {operationId:operation,messageId:message,state:'READ',readAt:at}}
function deferred<T>(){let resolve!:(x:T)=>void;const promise=new Promise<T>(r=>resolve=r);return {promise,resolve}}
describe('current notification authority and projection',()=>{
 it('accepts only exact application routes and consistent read facts',()=>{
  expect(parseMessage(fact()).object.id).toBe(approval)
  expect(parseMessagePage({items:[fact()],total:1,page:1,size:20},1).items).toHaveLength(1)
  for(const patch of [{read:true},{readAt:at},{object:{type:'APPROVAL_PARTS',id:approval,route:'https://outside.invalid'}},{type:'unknown'}])expect(()=>parseMessage({...fact(),...patch})).toThrow()
  expect(()=>parseMessagePage({items:[fact(),fact()],total:2,page:1,size:20},1)).toThrow()
 })
 it('accepts a customer ticket deep link only with the exact object route',()=>{
  const ticket='44444444-4444-4444-8444-444444444444'
  const customer={...fact(),type:'ticket.accepted',object:{type:'TICKET',id:ticket,route:'/tickets/'+ticket}}
  expect(parseMessage(customer).object.type).toBe('TICKET')
  expect(()=>parseMessage({...customer,object:{...customer.object,route:'/tickets/'+approval}})).toThrow()
  expect(()=>parseMessage({...customer,object:{...customer.object,type:'FEE'}})).toThrow()
  const resolution={...customer,type:'ticket.resolution_submitted'}
  expect(parseMessage(resolution).object.type).toBe('TICKET')
  expect(parseMessage({...customer,type:'ticket.closed'}).object.type).toBe('TICKET')
 })
 it('never sends browser identity claims and always uses current AUTH csrf',async()=>{
  const a=auth(),send=vi.fn(async()=>receipt()),wire=notificationTransport(a,()=>true,()=>'',send as unknown as typeof request)
  expect((await wire.request('POST','/api/notification/messages/'+message+'/read',operation)).status).toBe(200)
  expect(send.mock.calls[0]).toEqual(['/notification/messages/'+message+'/read',{method:'POST',body:'{}',headers:{'Idempotency-Key':operation,'X-CSRF-TOKEN':'synthetic-current-csrf'}}])
 })
 for(const invalid of ['missing','preauth','attempt','expired'])it('cannot mark read with '+invalid+' csrf',async()=>{
  const a=auth();if(invalid==='missing')a.csrf=null;if(invalid==='preauth')a.csrf!.contextKind='PREAUTH';if(invalid==='attempt')a.csrf!.loginAttemptId='other';if(invalid==='expired')a.csrf!.expiresAt='invalid'
  const send=vi.fn();expect((await notificationTransport(a,()=>true,()=>'',send as unknown as typeof request).request('POST','/api/notification/messages/'+message+'/read',operation)).status).toBe(403);expect(send).not.toHaveBeenCalled()
 })
 for(const change of ['identity','attempt','route','unmount'])it('late401 cannot affect current '+change,async()=>{
  const a=auth(),d=deferred<unknown>();let active=true,context='A'
  const send=vi.fn(async()=>{await d.promise;throw new ApiError(401,'SESSION_INVALID','denied')})
  const pending=notificationTransport(a,()=>active,()=>context,send as unknown as typeof request).request('GET','/api/notification/messages/'+message)
  if(change==='identity')a.generation++;if(change==='attempt')a.identity!.loginAttemptId='new';if(change==='route')context='B';if(change==='unmount')active=false
  d.resolve(undefined);expect((await pending).body).toEqual({code:'STALE_CONTEXT'});expect(a.handleFailure).not.toHaveBeenCalled()
 })
 it('rejects foreign or ambiguous endpoints before sending',async()=>{
  const a=auth(),send=vi.fn(),wire=notificationTransport(a,()=>true,()=>'',send as unknown as typeof request)
  for(const path of ['https://outside.invalid','/api/notification/messages?page=1&size=20&tenantId=2','/api/notification/messages?page=1&page=2&size=20','/api/notification/messages?page=0&size=20'])await expect(wire.request('GET',path)).rejects.toThrow('PATH')
  expect(send).not.toHaveBeenCalled()
 })
})
describe('read operation original UUID recovery and lifecycle',()=>{
 function setup(replies:{status:number;body:unknown}[]){const a=auth(),calls:unknown[][]=[],wire:NotificationTransport={async request(...args){calls.push(args);return replies.shift()!}},retained=new Map<string,ReadSlot>(),work=new NotificationReads(wire,()=>notificationOwner(a),()=>operation,retained);work.bind();return {a,work,wire,retained,calls}}
 it('not delivered, original query404, explicit same UUID/body reissue, receipt',async()=>{
  const s=setup([{status:503,body:{}},{status:404,body:{code:'OPERATION_NOT_OBSERVED'}},{status:200,body:receipt()}]);await s.work.start(message);expect(s.work.phase).toBe('UNKNOWN');await s.work.retry();expect(s.calls).toHaveLength(1);await s.work.query();expect(s.work.phase).toBe('NOT_OBSERVED');await s.work.retry();expect(s.work.phase).toBe('SUCCEEDED');expect(s.calls.map(c=>c.slice(0,3))).toEqual([['POST','/api/notification/messages/'+message+'/read',operation],['GET','/api/notification/read-operations/'+operation,undefined],['POST','/api/notification/messages/'+message+'/read',operation]])
 })
 it('committed response lost only queries original UUID and never posts automatically',async()=>{
  const s=setup([{status:503,body:{}},{status:200,body:receipt()}]);await s.work.start(message);await s.work.query();expect(s.work.pending).toBeNull();expect(s.calls.filter(c=>c[0]==='POST')).toHaveLength(1)
 })
 for(const status of [401,403,404,503])it('HTTP '+status+' is not a positive404 retry grant',async()=>{
  const s=setup([{status:503,body:{}},{status,body:{code:'TARGET_NOT_ACCESSIBLE'}}]);await s.work.start(message);await s.work.query();await s.work.retry();expect(s.calls).toHaveLength(2);expect(s.work.pending?.operationId).toBe(operation);expect(s.work.phase).not.toBe('NOT_OBSERVED')
 })
 it('mismatched receipt cannot resolve original message',async()=>{const s=setup([{status:200,body:{...receipt(),messageId:approval}}]);await s.work.start(message);expect(s.work.phase).toBe('UNKNOWN');expect(s.work.pending?.messageId).toBe(message)})
 it('unmount and remount preserve original operation and reject old late completion',async()=>{
  const a=auth(),retained=new Map<string,ReadSlot>(),old=deferred<{status:number;body:unknown}>();const first=new NotificationReads({request:()=>old.promise},()=>notificationOwner(a),()=>operation,retained);first.bind();const sending=first.start(message);first.dispose()
  const next=new NotificationReads({async request(){return {status:200,body:receipt()}}},()=>notificationOwner(a),()=>approval,retained);next.bind();expect(next.pending?.operationId).toBe(operation);old.resolve({status:200,body:receipt()});await sending;expect(next.pending?.operationId).toBe(operation);await next.query();expect(next.phase).toBe('SUCCEEDED')
 })
 it('different actor cannot inherit an unknown mark operation',async()=>{const s=setup([{status:503,body:{}}]);await s.work.start(message);s.a.identity!.userId='8';s.work.bind();expect(s.work.pending).toBeNull();expect(s.retained.size).toBe(2)})
})
