import {ApiError} from '../../api'
import {parsePartBinding,type PartBinding} from '../finance/quote-draft'
import {parseOrder,parseReceipt as parseStockReceipt,type ExecutionOrder,type ExecutionReceipt} from '../stock/execution-work'
import {decimal,uuid,type FeeExecutionContext,type FeeExecutionOwner,type FeeExecutionProjection,type FeeExecutionTransport} from './fee-execution-client'

export interface PartFeeCommand{
 operationId:string;groupId:string;completionId:string;quoteId:string;quoteVersion:string;generation:string;
 lineId:string;orderId:string;useOperationId:string;partId:string;quantity:string;amountCents:string;warehouseId:string;companyId:string
}
export interface PartFeeReceipt extends PartFeeCommand{
 executionReceiptId:string;tenantId:string;ticketId:string;roundId:string;coverageDigest:string;state:'EXECUTED';recordedAt:string
}
export interface PartUseFact{lineId:string;operationId:string;binding:PartBinding;order:ExecutionOrder;receipt:ExecutionReceipt}
export interface PartFeeOperation{
 key:string;slot:string;body:Readonly<PartFeeCommand>;context:FeeExecutionContext;
 state:'UNKNOWN'|'NOT_OBSERVED'|'OWNER_COMMITTED'|'SUCCEEDED';receipt?:PartFeeReceipt;lease:number
}
export const retainedPartFeeExecutions=new Map<string,PartFeeOperation>()
const clone=<T>(value:T):T=>JSON.parse(JSON.stringify(value)) as T
const slotFor=(owner:FeeExecutionOwner,context:FeeExecutionContext)=>JSON.stringify([owner.tenant,owner.actor,context.ticketId,context.roundId,context.assignmentId])
export function partCommand(context:FeeExecutionContext,lineId:string,use:PartUseFact,key:string):PartFeeCommand{
 const line=context.lines.find(l=>l.id===lineId)
 if(!line||line.kind!=='PART'||!decimal(context.companyId,true)||!uuid(key)||use.lineId!==lineId||!uuid(use.operationId))throw Error('备件收费执行上下文无效')
 const binding=parsePartBinding(line.partBinding)
 if(JSON.stringify(binding)!==JSON.stringify(use.binding)||use.order.orderId!==binding.approvalId||use.order.warehouseId!==binding.warehouseId||use.receipt.operationId!==use.operationId)throw Error('原库存回执与批准行绑定不符')
 return{operationId:key,groupId:context.groupId,completionId:context.completionId,quoteId:context.quoteId,quoteVersion:context.quoteVersion,generation:context.generation,lineId,orderId:binding.approvalId,useOperationId:use.operationId,partId:binding.partId,quantity:binding.quantity,amountCents:line.amountCents,warehouseId:binding.warehouseId,companyId:context.companyId}
}
export function parsePartReceipt(value:unknown,context:FeeExecutionContext,body:PartFeeCommand,owner:FeeExecutionOwner):PartFeeReceipt{
 const receipt=value as PartFeeReceipt
 if(!receipt||receipt.state!=='EXECUTED'||receipt.tenantId!==owner.tenant||receipt.ticketId!==context.ticketId||receipt.roundId!==context.roundId||receipt.coverageDigest!==context.coverageDigest||!uuid(receipt.executionReceiptId)||!Number.isFinite(Date.parse(receipt.recordedAt))||Object.entries(body).some(([key,value])=>(receipt as unknown as Record<string,unknown>)[key]!==value))throw Error('原备件收费执行回执与冻结命令不符')
 return receipt
}
export class PartFeeExecutionWork{
 operation:PartFeeOperation|null=null;use:PartUseFact|null=null;busy=false;queryBusy=false;message='';useMessage=''
 private context:FeeExecutionContext|null=null;private stamp='';private epoch=0;private lease=0;private alive=true;private querySlot=0
 constructor(private wire:FeeExecutionTransport,private owner:()=>FeeExecutionOwner|null,private key:()=>string=()=>crypto.randomUUID()){}
 bind(context:FeeExecutionContext){
  this.epoch++;this.querySlot++;this.busy=false;this.queryBusy=false;this.message='';this.useMessage='';this.use=null;this.context=context;this.stamp=JSON.stringify(this.owner());this.operation=null
  const owner=this.owner()
  if(!owner)return
  const operation=retainedPartFeeExecutions.get(slotFor(owner,context))
  if(operation){if(operation.state==='NOT_OBSERVED')operation.state='UNKNOWN';this.lease=++operation.lease;this.operation=operation}
 }
 private current(){
  const epoch=this.epoch,stamp=this.stamp,operation=this.operation,lease=this.lease
  return()=>this.alive&&epoch===this.epoch&&stamp===JSON.stringify(this.owner())&&operation===this.operation&&(!operation||operation.lease===lease&&retainedPartFeeExecutions.get(operation.slot)===operation)
 }
 async queryUse(lineId:string,operationId:string){
  const context=this.context,line=context?.lines.find(l=>l.id===lineId),owner=this.owner()
  this.use=null;this.useMessage=''
  const query=++this.querySlot
  if(!context||!line||line.kind!=='PART'||!owner||!uuid(operationId)||this.operation)return
  const current=this.current(),valid=()=>current()&&query===this.querySlot
  this.queryBusy=true
  try{
   const binding=parsePartBinding(line.partBinding),target={orderId:binding.approvalId,warehouseId:binding.warehouseId,engineering:true}
   const rawOrder=await this.wire.send<ExecutionOrder>('GET','/stock/engineering/orders/'+target.orderId,undefined,valid)
   if(!valid())return
   const order=parseOrder(rawOrder,target)
   if(order.warehouseId!==binding.warehouseId)throw Error('原库存仓库与报价绑定不符')
   const raw=await this.wire.send<ExecutionReceipt>('GET','/stock/engineering/execution-operations/'+operationId,undefined,valid)
   if(!valid())return
   const receipt=parseStockReceipt(raw,target,operationId),used=receipt.lines.find(l=>l.partId===binding.partId)
   if(!['ISSUED','ACCOUNTED'].includes(receipt.state)||!used||BigInt(used.used)<BigInt(binding.quantity))throw Error('原库存回执尚未覆盖本批准行数量')
   this.use={lineId,operationId,binding,order,receipt}
   this.useMessage='原库存回执已核对，提交时由库存所有者核验本次USE与完整批准行的对应关系。'
  }catch(error){if(valid()){this.use=null;this.useMessage=error instanceof Error?error.message:'原库存回执查询失败'}}finally{if(valid())this.queryBusy=false}
 }
 clearUse(){this.querySlot++;this.queryBusy=false;this.use=null;this.useMessage=''}
 async start(lineId:string,projection:FeeExecutionProjection|null){
  const owner=this.owner(),context=this.context,use=this.use
  if(!owner||!context||!use||this.operation||this.busy||!projection)return
  const fact=projection.lines.find(l=>l.lineId===lineId)
  if(!fact||fact.kind!=='PART'||fact.executedAmountCents!=='0'||fact.executedQuantity!=='0'||fact.remainingAmountCents!==fact.approvedAmountCents||fact.remainingQuantity!==fact.approvedQuantity)return
  const key=this.key(),body=partCommand(context,lineId,use,key),slot=slotFor(owner,context)
  this.operation={key,slot,body:Object.freeze(clone(body)),context:clone(context),state:'NOT_OBSERVED',lease:1};this.lease=1
  retainedPartFeeExecutions.set(slot,this.operation)
  await this.recover(false,projection)
 }
 async recover(query=true,projection:FeeExecutionProjection|null=null){
  const operation=this.operation,context=this.context,owner=this.owner()
  if(!operation||!context||!owner||this.busy)return
  if(!query){
   if(!['NOT_OBSERVED','OWNER_COMMITTED'].includes(operation.state)||context.groupId!==operation.body.groupId||context.quoteId!==operation.body.quoteId||context.generation!==operation.body.generation||context.quoteVersion!==operation.body.quoteVersion)return
   const fact=projection?.lines.find(l=>l.lineId===operation.body.lineId)
   if(!fact||fact.kind!=='PART'||operation.state==='NOT_OBSERVED'&&(fact.remainingAmountCents!==operation.body.amountCents||fact.remainingQuantity!==operation.body.quantity))return
  }
  const current=this.current();this.busy=true;this.message=''
  if(!operation.receipt)operation.state='UNKNOWN'
  try{
   const base='/ticket/tickets/'+operation.context.ticketId,path=query?base+'/part-fee-execution-operations/'+operation.key:base+'/part-fee-executions'
   const value=await this.wire.send<unknown>(query?'GET':'POST',path,query?undefined:clone(operation.body),current,query?undefined:operation.key)
   if(!current())return
   if(query){operation.receipt=parsePartReceipt(value,operation.context,operation.body,owner);operation.state='OWNER_COMMITTED';this.message='库存收费事实已确认。请以原UUID核对并完成协调，再重新读取权威投影。'}
   else{
    const result=value as {operationId:string;state:string;execution:unknown}
    if(result.operationId!==operation.key||result.state!=='COMMITTED')throw Error('备件收费协调结果尚未确认')
    operation.receipt=parsePartReceipt(result.execution,operation.context,operation.body,owner);operation.state='SUCCEEDED'
    this.message='原备件收费执行及协调已确认，请重新读取服务端权威投影。'
   }
  }catch(error){
   if(!current())return
   if(query&&error instanceof ApiError&&error.status===404&&!operation.receipt){operation.state='NOT_OBSERVED';this.message='尚未观察到原操作。重新核验当前投影后，可显式以原UUID原参数继续。'}
   else{if(!operation.receipt)operation.state='UNKNOWN';this.message=error instanceof Error?error.message:'备件收费结果未知，请查询原UUID'}
  }finally{if(current())this.busy=false}
 }
 next(){if(this.busy||this.operation?.state!=='SUCCEEDED')return;retainedPartFeeExecutions.delete(this.operation.slot);this.epoch++;this.operation=null;this.clearUse()}
 dispose(){this.alive=false;this.epoch++;this.querySlot++;this.busy=false;this.queryBusy=false;this.operation=null;this.use=null}
}
