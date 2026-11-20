import {expect,it,vi} from 'vitest'
import {loadPartQuoteOptions} from '../src/features/finance/part-quote-catalog'
import type {FinanceTransport} from '../src/features/finance/finance-client'

const ticketId='10000000-0000-4000-8000-000000000001'
const owner={tenant:'1',actor:'2',generation:1,attempt:'test',type:'EMPLOYEE'}

it('uses the actual IN_PROGRESS assignment state after an engineer accepts',async()=>{
 const send=vi.fn().mockResolvedValueOnce({id:ticketId,assignment:{state:'IN_PROGRESS',engineerUserId:'2',assignmentId:ticketId}})
  .mockResolvedValueOnce({items:[],page:1,size:20,total:0})
 const result=await loadPartQuoteOptions({send} as FinanceTransport,owner,ticketId,1,()=>true)
 expect(result.items).toEqual([])
 expect(send.mock.calls[1]?.[1]).toBe('/approval/parts?ticketId='+ticketId+'&page=1&size=20')
})

it.each(['PENDING','TRANSFER_PENDING','ACCEPTED'])('rejects an unsupported assignment state %s before reading parts',async state=>{
 const send=vi.fn().mockResolvedValue({id:ticketId,assignment:{state,engineerUserId:'2',assignmentId:ticketId}})
 await expect(loadPartQuoteOptions({send} as FinanceTransport,owner,ticketId,1,()=>true)).rejects.toThrow('已接单工程师')
 expect(send).toHaveBeenCalledTimes(1)
})

it('does not read a different engineer assignment catalog',async()=>{
 const send=vi.fn().mockResolvedValue({id:ticketId,assignment:{state:'IN_PROGRESS',engineerUserId:'3',assignmentId:ticketId}})
 await expect(loadPartQuoteOptions({send} as FinanceTransport,owner,ticketId,1,()=>true)).rejects.toThrow('已接单工程师')
 expect(send).toHaveBeenCalledTimes(1)
})
