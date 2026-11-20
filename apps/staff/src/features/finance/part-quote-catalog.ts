import {parseApproval,type ApprovalView} from '../approval/approval-client'
import type {TicketDetail} from '../../ticket-api'
import {uuid,id} from '../stock/stock-client'
import {parsePartBinding,type PartBinding} from './quote-draft'
import type {FinanceOwner,FinanceTransport} from './finance-client'

export interface PartQuoteOption{label:string;binding:PartBinding}
export interface PartQuotePage{items:PartQuoteOption[];page:number;total:number}
interface Page<T>{items:T[];page:number;size:number;total:number}
interface CatalogPart{id:string;name:string;code:string;unit:string}
function pageOf<T>(value:Page<T>,page:number):Page<T>{
 if(!value||value.page!==page||value.size!==20||!Number.isSafeInteger(value.total)||value.total<0||!Array.isArray(value.items)||value.items.length>20)throw Error('当前授权备件目录分页无效')
 return value
}
export async function loadPartQuoteOptions(wire:FinanceTransport,owner:FinanceOwner,ticketId:string,page:number,current:()=>boolean):Promise<PartQuotePage>{
 if(!uuid(ticketId)||!Number.isSafeInteger(page)||page<1||page>100000)throw Error('工单或备件申请页码无效')
 const ticket=await wire.send<TicketDetail>('GET','/ticket/tickets/'+ticketId,undefined,current)
 if(ticket.id!==ticketId||!ticket.assignment||ticket.assignment.state!=='IN_PROGRESS'||ticket.assignment.engineerUserId!==owner.actor)throw Error('当前已接单工程师与备件申请上下文无法确认')
 const result=pageOf(await wire.send<Page<ApprovalView>>('GET','/approval/parts?ticketId='+ticketId+'&page='+page+'&size=20',undefined,current),page)
 const applications=result.items.map(parseApproval)
 if(new Set(applications.map(a=>a.id)).size!==applications.length||applications.some(a=>a.submission.ticketId!==ticketId||a.submission.tenantId!==owner.tenant))throw Error('备件申请目录归属无效')
 const items:PartQuoteOption[]=[]
 for(const row of applications){
  if(row.state!=='APPROVED'||row.submission.assignmentId!==ticket.assignment.assignmentId||row.submission.applicantId!==owner.actor)continue
  const application=parseApproval(await wire.send('GET','/approval/parts/'+row.id,undefined,current))
  if(application.id!==row.id||application.submission.tenantId!==owner.tenant||application.submission.ticketId!==ticketId||application.submission.assignmentId!==ticket.assignment.assignmentId||application.submission.applicantId!==owner.actor)throw Error('当前备件申请归属已变化')
  if(application.state!=='APPROVED'||application.chargeMode!=='CUSTOMER_PAID')continue
  const original=application.submission
  let part:CatalogPart|undefined
  let partPage=1
  while(!part){
   const query=new URLSearchParams({ticketId,assignmentId:ticket.assignment.assignmentId,page:String(partPage),size:'20'})
   const catalog=pageOf(await wire.send<Page<CatalogPart>>('GET','/stock/warehouses/'+original.warehouseId+'/parts?'+query,undefined,current),partPage)
   if(catalog.items.some(p=>!p||!id(p.id)||typeof p.name!=='string'||typeof p.code!=='string'||typeof p.unit!=='string')||new Set(catalog.items.map(p=>p.id)).size!==catalog.items.length)throw Error('当前备件或单位目录无效')
   part=catalog.items.find(p=>p.id===original.partId)
   if(part||partPage*20>=catalog.total)break
   partPage++
   if(partPage>100000)throw Error('备件目录超过支持页码')
  }
  if(!part)throw Error('批准申请的备件当前不在授权目录')
  const binding=parsePartBinding({approvalId:application.id,approvalVersion:application.version,warehouseId:original.warehouseId,partId:original.partId,quantity:String(original.quantity),unitCode:part.unit,allocationRule:'FULL_LINE_SINGLE_USE_V1'})
  items.push({label:part.code+' · '+part.name+' · '+binding.quantity+' '+binding.unitCode+' · 申请 '+application.id,binding})
 }
 return{items,page,total:result.total}
}
