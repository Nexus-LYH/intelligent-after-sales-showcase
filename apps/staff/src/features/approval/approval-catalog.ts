import type{TicketDetail}from'../../ticket-api'
import{decimal,type ApprovalView,type ApprovalAction,type ApprovalTransport}from'./approval-client'
export interface WarehouseOption{id:string;name:string}
export interface CatalogPage<T>{items:T[];total:number;page:number;size:number}
export interface ApprovalPartOption{warehouseId:string;warehouseName:string;partId:string;partName:string}
export interface ApprovalCatalog{warehouses?(ticket:TicketDetail,page?:number):Promise<CatalogPage<WarehouseOption>>;parts(ticket:TicketDetail,warehouse?:WarehouseOption,page?:number):Promise<ApprovalPartOption[]|CatalogPage<ApprovalPartOption>>;actions(view:ApprovalView):Promise<ApprovalAction[]>}
function pageOf<T>(body:unknown,page:number,valid:(x:T)=>boolean):CatalogPage<T>{const r=body as CatalogPage<T>;if(!r||!Array.isArray(r.items)||r.page!==page||r.size!==20||!Number.isSafeInteger(r.total)||r.total<0||r.items.length>20||r.items.some(x=>!valid(x)))throw Error('授权目录投影无法确认');return r}
export function createApprovalCatalog(transport:ApprovalTransport):ApprovalCatalog{
 const get=async(path:string)=>{const r=await transport.request('GET',path);if(r.status!==200)throw Error('当前授权目录不可用（HTTP '+r.status+'），不能提交申请。');return r.body}
 const query=(t:TicketDetail,page:number)=>{if(!t.assignment||!Number.isSafeInteger(page)||page<1||page>100000)throw Error('当前分派或目录分页无效');return new URLSearchParams({ticketId:t.id,assignmentId:t.assignment.assignmentId,page:String(page),size:'20'})}
 return{
  async warehouses(t,page=1){const r=pageOf<WarehouseOption>(await get('/api/stock/warehouses?'+query(t,page)),page,x=>!!x&&decimal(x.id,true)&&typeof x.name==='string'&&!!x.name);if(new Set(r.items.map(x=>x.id)).size!==r.items.length)throw Error('仓库目录重复');return r},
  async parts(t,w,page=1){if(!w||!decimal(w.id,true))throw Error('请先选择当前授权仓库。');const r=pageOf<{id:string;code:string;name:string;model:string;unit:string}>(await get('/api/stock/warehouses/'+w.id+'/parts?'+query(t,page)),page,x=>!!x&&decimal(x.id,true)&&['code','name','model','unit'].every(k=>typeof x[k as keyof typeof x]==='string')&&!!x.name);if(new Set(r.items.map(x=>x.id)).size!==r.items.length)throw Error('备件目录重复');return{...r,items:r.items.map(p=>({warehouseId:w.id,warehouseName:w.name,partId:p.id,partName:p.code+' · '+p.name+' '+p.model+'（'+p.unit+'）'}))}},
  async actions(view){if(!view.allowedActions)throw Error('当前申请动作资格未提供；不开放审核和撤回。');return[...(view.allowedActions.includes('approve')&&view.allowedActions.includes('reject')?['decision' as const]:[]),...(view.allowedActions.includes('withdraw')?['withdraw' as const]:[])]}
 }
}
export function selectionKey(t:TicketDetail,p:ApprovalPartOption){return JSON.stringify([t.id,t.roundId,t.assignment?.assignmentId,t.assignment?.departmentId,p.warehouseId,p.partId])}
export function validOption(p:ApprovalPartOption){return decimal(p.warehouseId,true)&&decimal(p.partId,true)&&!!p.warehouseName&&!!p.partName}
