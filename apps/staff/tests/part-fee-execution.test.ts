import {beforeEach,expect,it,vi} from 'vitest'
import {ApiError} from '../src/api'
import {PartFeeExecutionWork,retainedPartFeeExecutions} from '../src/features/fee-execution/part-fee-execution'
import type {FeeExecutionContext,FeeExecutionProjection,FeeExecutionTransport} from '../src/features/fee-execution/fee-execution-client'

const id=(n:number)=>`00000000-0000-4000-8000-${String(n).padStart(12,'0')}`
const owner={tenant:'1',actor:'2',generation:1,attempt:'test'}
const binding={approvalId:id(7),approvalVersion:'1',warehouseId:'3',partId:'4',quantity:'1',unitCode:'PCS',allocationRule:'FULL_LINE_SINGLE_USE_V1' as const}
const context:FeeExecutionContext={companyId:'9',ticketId:id(1),ticketVersion:'2',roundId:id(2),assignmentId:id(3),groupId:id(4),completionId:id(5),quoteId:id(6),quoteVersion:'1',generation:'1',coverageDigest:'a'.repeat(64),lines:[{id:id(8),ticketId:id(1),roundId:id(2),kind:'PART',amountCents:'500',partBinding:binding}],records:[]}
const projection={lines:[{lineId:id(8),kind:'PART',approvedAmountCents:'500',executedAmountCents:'0',remainingAmountCents:'500',approvedQuantity:'1',executedQuantity:'0',remainingQuantity:'1'}]} as FeeExecutionProjection
const order={orderId:id(7),warehouseId:'3',state:'ACCOUNTED',version:'3',expiresAt:null,lines:[{partId:'4',quantity:'1',issued:'1',used:'1',returned:'0'}]}
const use={...order,operationId:id(9)}
function setup(){
 const send=vi.fn().mockResolvedValueOnce(order).mockResolvedValueOnce(use)
 const work=new PartFeeExecutionWork({send} as FeeExecutionTransport,()=>owner,()=>id(10))
 work.bind(context)
 return{send,work}
}
const receipt=(body:Record<string,unknown>)=>({...body,executionReceiptId:id(11),tenantId:'1',ticketId:id(1),roundId:id(2),coverageDigest:'a'.repeat(64),state:'EXECUTED',recordedAt:'2026-11-16T01:00:00Z'})
beforeEach(()=>retainedPartFeeExecutions.clear())

it('requires an authorized stock result before submitting and accepts a committed owner receipt',async()=>{
 const {send,work}=setup()
 await work.start(id(8),projection)
 expect(send).not.toHaveBeenCalled()
 await work.queryUse(id(8),id(9))
 send.mockImplementationOnce(async(_method,_path,body)=>({operationId:id(10),state:'COMMITTED',execution:receipt(body)}))
 await work.start(id(8),projection)
 expect(work.operation?.state).toBe('SUCCEEDED')
 expect(send.mock.calls[2]?.[1]).toBe('/ticket/tickets/'+id(1)+'/part-fee-executions')
 await work.start(id(8),projection)
 expect(send).toHaveBeenCalledTimes(3)
})

it('keeps the same command after a lost response and explicitly completes owner coordination',async()=>{
 const {send,work}=setup()
 await work.queryUse(id(8),id(9))
 send.mockRejectedValueOnce(new ApiError(503,'UNKNOWN','unknown'))
 await work.start(id(8),projection)
 const body=send.mock.calls[2]![2]
 expect(work.operation?.state).toBe('UNKNOWN')
 send.mockResolvedValueOnce(receipt(body))
 await work.recover()
 expect(work.operation?.state).toBe('OWNER_COMMITTED')
 send.mockResolvedValueOnce({operationId:id(10),state:'COMMITTED',execution:receipt(body)})
 await work.recover(false,projection)
 expect(send.mock.calls[4]?.[2]).toEqual(body)
 expect(send.mock.calls[4]?.[4]).toBe(id(10))
 expect(work.operation?.state).toBe('SUCCEEDED')
})

it('hides a stock result returned after unmount',async()=>{
 let resolve!:(value:unknown)=>void
 const send=vi.fn(()=>new Promise(r=>{resolve=r}))
 const work=new PartFeeExecutionWork({send} as FeeExecutionTransport,()=>owner)
 work.bind(context)
 const pending=work.queryUse(id(8),id(9))
 work.dispose();resolve(order);await pending
 expect(work.use).toBeNull()
 expect(send).toHaveBeenCalledTimes(1)
})
