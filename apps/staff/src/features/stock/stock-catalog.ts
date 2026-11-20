export interface StockWarehouse{id:string;name:string}
export interface StockPart{id:string;name:string;code:string;unit:string;model:string}
export interface StockPage<T>{items:T[];total:number;page:number;size:number}
/** Warehouse-manager directory differs from engineer ticket catalog. No invented endpoint. */
export interface ManagerCatalog{warehouses(page:number):Promise<StockPage<StockWarehouse>>;parts(warehouseId:string,page:number):Promise<StockPage<StockPart>>}
export function createManagerCatalog(wire:StockTransport,current:()=>boolean):ManagerCatalog{
 const read=async<T>(path:string,page:number,valid:(item:T)=>boolean)=>{if(!Number.isInteger(page)||page<1||page>100000)throw Error('目录页码无效');const r=await wire.send<StockPage<T>>('GET',path+'?page='+page+'&size=20',undefined,current);if(!r||r.page!==page||r.size!==20||!Number.isSafeInteger(r.total)||r.total<0||!Array.isArray(r.items)||r.items.length>20||r.items.some(x=>!valid(x))||new Set(r.items.map(x=>(x as {id:string}).id)).size!==r.items.length)throw Error('仓管授权目录投影无效');return r};
 return{warehouses:page=>read<StockWarehouse>('/stock/warehouse-catalog',page,w=>!!w&&id(w.id)&&typeof w.name==='string'&&!!w.name.trim()),parts:(w,page)=>{if(!id(w))throw Error('仓库标识无效');return read<StockPart>('/stock/warehouse-catalog/'+w+'/parts',page,p=>!!p&&id(p.id)&&['name','code','unit','model'].every(k=>typeof p[k as keyof StockPart]==='string')&&!!p.name.trim()&&!!p.unit.trim())}}
}

import{warehousePath,id,type StockTransport}from'./stock-client'
/** Limited deep-link fallback: only existing balance rows from a currently authorized warehouse.
 * It does not discover warehouse scope, invent part names/units or replace the missing full manager catalog. */
export function balanceBackedCatalog(warehouseId:string,wire:StockTransport,current:()=>boolean):ManagerCatalog{
 const read=async(page:number)=>{const r=await wire.send<{items:{partId:string}[];total:number;page:number;size:number}>('GET',warehousePath(warehouseId)+'/balances?'+new URLSearchParams({page:String(page),size:'20'}),undefined,current);if(!r||r.page!==page||r.size!==20||!Number.isSafeInteger(r.total)||r.total<0||!Array.isArray(r.items)||r.items.some(p=>!id(p.partId))||new Set(r.items.map(p=>p.partId)).size!==r.items.length)throw Error('授权余额目录无法确认');return r}
 return{async warehouses(page){await read(1);return{items:page===1?[{id:warehouseId,name:'当前链接仓库 #'+warehouseId+'（已在线核验）'}]:[],total:1,page,size:20}},async parts(w,page){if(w!==warehouseId)throw Error('仓库上下文不符');const r=await read(page);return{...r,items:r.items.map(p=>({id:p.partId,name:'备件编号 #'+p.partId,code:'#'+p.partId,unit:'单位未提供，请核对原档案',model:''}))}}}
}
