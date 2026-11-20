import { describe, it, expect } from 'vitest'
import type { TicketDetail } from '../src/ticket-api'
import { actionBody, actionPath, cancelAllowed, newActionForm, validateAction, wireAction } from '../src/ticket-action-forms'
import { validCancellation, validCancellationRequest } from '../src/ticket-cancellation-api'
import { validSupplement } from '../src/ticket-supplement-api'
import { resolutionOverdue, validSolution } from '../src/ticket-solution-api'
const ticket = (status='PENDING_ACCEPTANCE') => ({id:'7',roundId:'round',rowVersion:'2',status,impact:'AFFECTED',attachments:[],currentSupplement:null,currentCancellation:null} as unknown as TicketDetail)
describe('B9 cancellation decisions preserve current server facts',()=>{
  it.each(['PENDING_ACCEPTANCE','WAITING_ASSIGNMENT','ASSIGNED','IN_PROGRESS','PENDING_CUSTOMER_CONFIRMATION'])('separates direct and request at %s',status=>{
    const t=ticket(status);expect(cancelAllowed(t,'cancel-direct')).toBe(status==='PENDING_ACCEPTANCE');expect(cancelAllowed(t,'cancel-request')).toBe(status!=='PENDING_ACCEPTANCE')
  })
  it.each(['PENDING_ACCEPTANCE','IN_PROGRESS'])('uses frozen supplement resume rather than first response %s',resume=>{
    const t=ticket('WAITING_INFORMATION');t.firstResponseAt='2026-10-19T00:00:00Z';t.currentSupplement={state:'OPEN',resumeState:resume} as any
    expect(cancelAllowed(t,'cancel-direct')).toBe(resume==='PENDING_ACCEPTANCE');expect(cancelAllowed(t,'cancel-request')).toBe(resume==='IN_PROGRESS')
  })
  it.each(['RESOLVED','CLOSED','REJECTED','CANCELLED'])('terminal %s cannot create cancellation',status=>{
    expect(cancelAllowed(ticket(status),'cancel-direct')).toBe(false);expect(cancelAllowed(ticket(status),'cancel-request')).toBe(false)
  })
  it('omits empty optional direct reason, requires confirmation, preserves exact version and body',()=>{
    const t=ticket(),f=newActionForm(t);expect(validateAction(t,'cancel-direct',f)).toContain('确认');f.acknowledged=true
    expect(validateAction(t,'cancel-direct',f)).toBe('');expect(actionBody(t,'cancel-direct',f)).toEqual({expectedVersion:'2',roundId:'round'});expect(actionPath(t,'cancel-direct')).toBe('/ticket/tickets/7/cancel')
  })
  it('requires public request/review reasons and binds review path to current request',()=>{
    const t=ticket('IN_PROGRESS'),f=newActionForm(t);f.acknowledged=true;expect(validateAction(t,'cancel-request',f)).toContain('5～500');f.reason='请取消当前这次报修';expect(validateAction(t,'cancel-request',f)).toBe('')
    t.currentCancellation={cancelRequestId:'current',roundId:'round',state:'PENDING_REVIEW'} as any
    expect(cancelAllowed(t,'cancel-request')).toBe(false);expect(actionPath(t,'cancel-reject')).toBe('/ticket/tickets/7/cancellation-requests/current/decision');expect(actionBody(t,'cancel-reject',f)).toEqual({expectedVersion:'2',roundId:'round',decision:'REJECT',reason:f.reason});expect(wireAction('cancel-reject')).toBe('cancel-decision')
    t.currentCancellation!.roundId='old';expect(cancelAllowed(t,'cancel-approve')).toBe(false)
  })
  it('does not count cancelled frozen timing as confirmed resolution',()=>{
    const t=ticket('CANCELLED');t.allowedActions=[];t.cancellation={source:'DIRECT',cancelRequestId:null,cancelledBy:'1',cancelledAt:'2026-10-19T00:00:00Z',reason:null,previousStatus:'PENDING_ACCEPTANCE',roundId:'round',assignmentId:null,check:{scope:'ORDINARY_REPAIR_ONLY',relatedWorkflows:'NOT_ENABLED'}}
    t.timing={clockStopped:true,terminalReason:'CANCELLED',terminatedAt:t.cancellation.cancelledAt,terminatedEffectiveResolutionMs:'500',effectiveResolutionMs:'500',finalFrozen:false,finalResolutionOverdue:null,needsCoordination:false} as any
    expect(validCancellation(t)).toBe(true);expect(resolutionOverdue(t.timing!)).toBe(null);t.timing!.finalFrozen=true;expect(validCancellation(t)).toBe(false)
  })
  it('retains rejected request without pretending its submitted state is current',()=>{
    const t=ticket('PENDING_CUSTOMER_CONFIRMATION'),r={cancelRequestId:'r',ticketId:'7',roundId:'round',assignmentId:null,requestedBy:'1',requestedAt:'2026-10-19T00:00:00Z',reason:'申请取消当前报修',submittedStatus:'WAITING_ASSIGNMENT',state:'REJECTED',decision:{decision:'REJECTED',reason:'请按当前方案继续核验',decidedBy:'2',decidedAt:'2026-10-19T00:01:00Z'}} as any
    expect(validCancellationRequest(r,t)).toBe(true);expect(t.status).toBe('PENDING_CUSTOMER_CONFIRMATION');r.internalBody='private';expect(validCancellationRequest(r,t)).toBe(false)
  })
  it('terminated supplement has no fake reply; terminated solution has no fake decision',()=>{
    const t=ticket('CANCELLED'),termination={reason:'CANCELLED',terminatedAt:'2026-10-19T00:00:00Z'},s={requestId:'s',ticketId:'7',roundId:'round',assignmentId:null,question:'请提供资料',state:'CANCELLED',resumeState:'PENDING_ACCEPTANCE',requestedAt:termination.terminatedAt,requestedBy:'2',reply:null,termination} as any
    expect(validSupplement(s,t)).toBe(true);s.reply={};expect(validSupplement(s,t)).toBe(false)
    const solution={solutionId:'s',roundId:'round',assignmentId:'a',engineerUserId:'e',submittedAt:termination.terminatedAt,cause:'cause',resolutionMethod:'method',customerMessage:'message',completedAt:termination.terminatedAt,confirmedWorkHours:'0',attemptNo:1,evidenceFileIds:[],termination,decision:{decision:'CONFIRM'}} as any
    expect(validSolution(solution)).toBe(false);solution.decision=null;expect(validSolution(solution)).toBe(true)
  })
})
