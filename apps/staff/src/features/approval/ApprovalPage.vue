<script setup lang="ts">
import{computed,onBeforeUnmount,reactive,ref,watch}from'vue'
import{useRoute}from'vue-router'
import{useSession}from'../../session'
import{ApiError}from'../../api'
import{captureRequestScope}from'../../request-scope'
import{ObjectLease}from'../../object-lease'
import{tickets,type TicketDetail,type TicketSummary}from'../../ticket-api'
import{currentPartsAssignment}from'./approval-context'
import{validRoundProjection}from'../../ticket-reopen-api'
import{approvalOwner,approvalTransport}from'./approval-integration'
import{createApprovalCatalog,selectionKey,validOption,type ApprovalCatalog,type WarehouseOption,type ApprovalPartOption}from'./approval-catalog'
import{parseApproval,uuid,type ApprovalView,type ApprovalAction,type SubmitInput}from'./approval-client'
import ApprovalPanel from'./ApprovalPanel.vue'
import{retainedApprovalOperations}from'./approval-work'
const props=defineProps<{catalog?:ApprovalCatalog}>(),route=useRoute(),auth=useSession(),owner=computed(()=>approvalOwner(auth))
const identityKey=computed(()=>JSON.stringify([owner.value,auth.identity?.loginAttemptId,auth.phase])),query=ref(''),rows=ref<TicketSummary[]>([]),page=ref(1),total=ref(0),listBusy=ref(false),listError=ref(''),selected=ref(''),partKey=ref(''),quantity=ref(1),purpose=ref(''),chargeMode=ref<''|'FREE'|'CUSTOMER_PAID'>(''),confirmed=ref(false),recoveryTick=ref(0),completionId=ref(''),warehouseId=ref(''),warehousePage=ref(1),partPage=ref(1)
let alive=true,listSlot=0
const mode=ref<'browse'|'create'>('browse'),applicationPage=ref(1)
const applicationId=computed(()=>typeof route.params.applicationId==='string'?route.params.applicationId:''),context=()=>JSON.stringify([route.fullPath,identityKey.value,selected.value,mode.value,applicationPage.value,warehouseId.value,warehousePage.value,partPage.value]),wire=approvalTransport(auth,()=>alive),catalog=computed(()=>{const stamp=context();return props.catalog??createApprovalCatalog(wire.scoped!(()=>context()===stamp))})
interface Snapshot{warehouses:WarehouseOption[];warehouseTotal:number;partTotal:number;ticket:TicketDetail|null;parts:ApprovalPartOption[];application:ApprovalView|null;actions:ApprovalAction[];notice:string;applications?:ApprovalView[];applicationTotal?:number}
const lease=reactive(new ObjectLease<Snapshot>(auth,context,async()=>{
 if(!owner.value)throw Error('请先以当前租户员工身份登录。')
 const readCatalog=catalog.value,readStamp=context(),readWire=wire.scoped!(()=>context()===readStamp)
 if(applicationId.value){
  if(!uuid(applicationId.value))throw Error('申请标识无效。')
  const r=await readWire.request('GET','/api/approval/parts/'+applicationId.value);if(r.status!==200)throw Error('当前申请权限无法确认（HTTP '+r.status+'），已清除正文。')
  const a=parseApproval(r.body);if(a.id!==applicationId.value||a.submission.tenantId!==owner.value.tenantId)throw Error('申请归属无法确认。')
  let actions:ApprovalAction[]=[],notice='';try{actions=await readCatalog.actions(a);if(!Array.isArray(actions)||actions.some(x=>!['decision','withdraw'].includes(x)))throw Error('动作投影无效')}catch(e){actions=[];notice=e instanceof Error?e.message:'动作资格暂不可用'}
  return{warehouses:[],warehouseTotal:0,partTotal:0,ticket:null,parts:[],application:a,actions,notice}
 }
 if(!uuid(selected.value))throw Error('请先选择当前授权工单。')
 const ticket=await tickets.detail(selected.value)
 if(!validRoundProjection(ticket,selected.value))throw Error('当前工单权限无法确认。')
 if(mode.value==='browse'){
  const query=new URLSearchParams({ticketId:ticket.id,page:String(applicationPage.value),size:'20'})
  const result=await readWire.request('GET','/api/approval/parts?'+query)
  if(result.status!==200)throw Error('当前申请列表无法确认（HTTP '+result.status+'）。')
  const p=result.body as {items:unknown[];total:number;page:number;size:number}
  if(!p||!Array.isArray(p.items)||!Number.isSafeInteger(p.total)||p.total<0||p.page!==applicationPage.value||p.size!==20||p.items.length>20)throw Error('申请列表投影无效。')
  const applications=p.items.map(parseApproval)
  if(applications.some(a=>a.submission.ticketId!==ticket.id||a.submission.tenantId!==owner.value!.tenantId)||new Set(applications.map(a=>a.id)).size!==applications.length)throw Error('申请列表归属无法确认。')
  return {warehouses:[],warehouseTotal:0,partTotal:0,ticket,parts:[],application:null,actions:[],notice:'',applications,applicationTotal:p.total}
 }
 if(!currentPartsAssignment(ticket,auth.identity))throw Error('当前已接单工程师资格无法确认；不能继续原选择。')
 let parts:ApprovalPartOption[]=[],warehouses:WarehouseOption[]=[],warehouseTotal=0,partTotal=0,notice='';try{
 if(readCatalog.warehouses){const r=await readCatalog.warehouses(ticket,warehousePage.value);warehouses=r.items;warehouseTotal=r.total;const w=warehouses.find(w=>w.id===warehouseId.value);if(w){const r=await readCatalog.parts(ticket,w,partPage.value);parts=Array.isArray(r)?r:r.items;partTotal=Array.isArray(r)?r.length:r.total}else notice='请先选择当前授权仓库。'}else{const r=await readCatalog.parts(ticket);parts=Array.isArray(r)?r:r.items;partTotal=Array.isArray(r)?r.length:r.total}
 if(parts.some(p=>!validOption(p))||new Set(parts.map(p=>p.warehouseId+':'+p.partId)).size!==parts.length)throw Error('授权备件投影无效')
 }catch(e){parts=[];warehouses=[];notice=e instanceof Error?e.message:'授权目录暂不可用'}
 return{warehouses,warehouseTotal,partTotal,ticket,parts,application:null,actions:[],notice}
},Date.now,30000,e=>{if(e instanceof ApiError&&e.status===401)auth.handleFailure(e)}))
const selectedPart=computed(()=>lease.value?.parts.find(p=>p.warehouseId+':'+p.partId===partKey.value)),selection=computed<SubmitInput|undefined>(()=>{const t=lease.value?.ticket,p=selectedPart.value;return t&&p&&chargeMode.value?{chargeMode:chargeMode.value,ticketId:t.id,assignmentId:t.assignment!.assignmentId,departmentId:t.assignment!.departmentId,warehouseId:p.warehouseId,partId:p.partId,quantity:quantity.value,purpose:purpose.value}:undefined})
const panelContext=computed(()=>applicationId.value?'application:'+applicationId.value:lease.value?.ticket&&selectedPart.value?'submit:'+selectionKey(lease.value.ticket,selectedPart.value):''),actions=computed<ApprovalAction[]>(()=>applicationId.value?lease.value?.actions??[]:selection.value&&confirmed.value?['submit']:[])
async function search(){const slot=++listSlot,key=context(),scope=captureRequestScope(auth,()=>alive&&slot===listSlot&&context()===key);listBusy.value=true;rows.value=[];listError.value='';try{const r=await tickets.list(new URLSearchParams({keyword:query.value,page:String(page.value),size:'20'}));if(!scope.current())return;if(!Array.isArray(r.items)||r.items.some(t=>!uuid(t.id)))throw Error('工单列表投影无效');rows.value=r.items;total.value=r.total}catch(e){if(scope.current()){listError.value=e instanceof Error?e.message:'查询失败';if(e instanceof ApiError&&e.status===401)auth.handleFailure(e)}}finally{if(scope.current())listBusy.value=false}}
async function choose(){chargeMode.value='';warehouseId.value='';warehousePage.value=1;partPage.value=1;applicationPage.value=1;lease.clear();partKey.value='';confirmed.value=false;if(selected.value)await lease.refresh()}
function changeWarehouse(){partKey.value='';partPage.value=1;confirmed.value=false;lease.clear();void lease.refresh()}
function clear(){chargeMode.value='';++listSlot;rows.value=[];listBusy.value=false;lease.clear();confirmed.value=false;partKey.value='';warehouseId.value='';warehousePage.value=1;partPage.value=1}
function create(){chargeMode.value='';mode.value='create';lease.clear();void lease.refresh()}
watch(()=>[identityKey.value,route.fullPath],()=>{clear();selected.value='';completionId.value='';mode.value='browse';if(owner.value){if(typeof route.params.id==='string'&&uuid(route.params.id)){selected.value=route.params.id;mode.value='create'}if(applicationId.value||selected.value)void lease.refresh()}},{immediate:true,flush:'sync'})
watch(()=>JSON.stringify([selection.value,lease.value?.application?.version,lease.value?.actions]),()=>{confirmed.value=false},{flush:'sync'})
const retained=computed(()=>{void recoveryTick.value;const o=owner.value;return o?Array.from(retainedApprovalOperations.entries()).filter(([key,slot])=>{const k=JSON.parse(key);return !!slot.pending&&k[0]===o.namespace&&k[1]===o.tenantId&&k[2]===o.actorId&&k[3]!==panelContext.value}).map(([key,slot])=>({context:JSON.parse(key)[3] as string,pending:slot.pending!})):[]})
function visibility(){if(document.visibilityState==='hidden')clear();else if(owner.value&&(applicationId.value||selected.value))void lease.refresh()}
document.addEventListener('visibilitychange',visibility)
let lastRefresh=Date.now();const timer=setInterval(()=>{recoveryTick.value++;lease.expire();if(Date.now()-lastRefresh>=25000){lastRefresh=Date.now();if(owner.value&&document.visibilityState!=='hidden'&&(applicationId.value||selected.value))void lease.refresh()}},1000)
onBeforeUnmount(()=>{alive=false;++listSlot;clearInterval(timer);lease.dispose();document.removeEventListener('visibilitychange',visibility)})
</script>
<template><section class="page-heading"><h1>备件申请与审批</h1><p>单备件申请；审批通过表示资格批准，不表示库存已预留、出库或维修完成。</p></section>
<section v-if="!applicationId" class="panel"><h2>选择当前授权工单</h2><form @submit.prevent="page=1;search()"><label>工单关键词<input v-model="query" maxlength="200"></label><button :disabled="listBusy || !owner">查询授权工单</button></form><p v-if="listError" role="alert">{{listError}}</p><label for="approval-ticket">工单</label><select id="approval-ticket" v-model="selected" @change="choose"><option value="">请选择工单</option><option v-for="t in rows" :key="t.id" :value="t.id">{{t.ticketNo}} · {{t.title}}</option></select><p>第{{page}}页 · {{total}}项</p><button :disabled="page<=1 || listBusy" @click="page--;search()">上一页</button><button :disabled="page*20>=total || listBusy" @click="page++;search()">下一页</button></section>
<section v-if="completionId" class="notice" role="status">原操作回执已核对；当前申请状态请另行查询。<RouterLink :to="'/approvals/parts/'+completionId">查看当前申请</RouterLink></section>
<section class="panel"><p v-if="lease.busy">正在核验当前对象权限…</p><p v-if="lease.error" role="alert">{{lease.error}}</p><button v-if="applicationId || selected" :disabled="lease.busy || !owner" @click="lease.refresh()">重新核验当前对象</button>
<template v-if="lease.value"><p v-if="lease.value.notice" role="status">{{lease.value.notice}}</p><template v-if="lease.value.ticket"><h2>{{lease.value.ticket.title}}</h2><template v-if="mode==='browse'"><h3>当前工单备件申请</h3><p v-if="!lease.value.applications?.length">当前授权范围内没有申请。</p><article v-for="a in lease.value.applications" :key="a.id"><RouterLink :to="'/approvals/parts/'+a.id">备件申请 · 修订{{a.revision.number}} · {{a.submission.purpose}}</RouterLink></article><button :disabled="applicationPage<=1" @click="applicationPage--;lease.clear();lease.refresh()">上一页申请</button><button :disabled="applicationPage*20>=(lease.value.applicationTotal||0)" @click="applicationPage++;lease.clear();lease.refresh()">下一页申请</button><button v-if="currentPartsAssignment(lease.value.ticket,auth.identity)" @click="create">新建备件申请</button></template>
<template v-else><p>仅当前已接单工程师可继续选择；仓库与备件须服务端当前授权。</p><template v-if="catalog.warehouses"><label for="approval-warehouse">授权仓库</label><select id="approval-warehouse" v-model="warehouseId" @change="changeWarehouse"><option value="">请选择当前授权仓库</option><option v-for="w in lease.value.warehouses" :key="w.id" :value="w.id">{{w.name}}</option></select><button :disabled="warehousePage<=1" @click="warehousePage--;warehouseId='';changeWarehouse()">上一页仓库</button><button :disabled="warehousePage*20>=lease.value.warehouseTotal" @click="warehousePage++;warehouseId='';changeWarehouse()">下一页仓库</button></template><label for="approval-part">授权仓库与备件</label><select id="approval-part" v-model="partKey" :disabled="!lease.value.parts.length"><option value="">请选择仓库和备件</option><option v-for="p in lease.value.parts" :key="p.warehouseId+':'+p.partId" :value="p.warehouseId+':'+p.partId">{{p.warehouseName}} · {{p.partName}}</option></select><template v-if="catalog.warehouses"><button :disabled="partPage<=1" @click="partPage--;partKey='';lease.clear();lease.refresh()">上一页备件</button><button :disabled="partPage*20>=lease.value.partTotal" @click="partPage++;partKey='';lease.clear();lease.refresh()">下一页备件</button></template><label for="approval-charge-mode">费用承担方式</label><select id="approval-charge-mode" v-model="chargeMode"><option value="">请选择费用承担方式</option><option value="FREE">服务商承担</option><option value="CUSTOMER_PAID">客户付费</option></select><p v-if="chargeMode==='CUSTOMER_PAID'">领用与消耗须满足当前报价确认和费用授权条件。</p><label>申请数量<input v-model.number="quantity" type="number" min="1" max="2147483647" step="1"></label><label>申请用途<textarea v-model="purpose" maxlength="1000"/></label><label v-if="selection"><input v-model="confirmed" type="checkbox">确认当前工单、分派、仓库、备件、费用承担方式、数量和用途；不自动重发</label></template></template>
<ApprovalPanel v-if="panelContext" :key="panelContext+identityKey+(lease.value.application?.version ?? '')" :owner="owner" :context-key="panelContext" :transport="wire" :selection="selection" :application-id="applicationId || undefined" :allowed-actions="actions" @changed="id=>completionId=id"/>
</template><p v-else>未核实当前权限时不展示旧正文，也不开放写操作。</p></section><section v-if="retained.length" class="panel"><h2>本账号原操作恢复（只读查询）</h2><p>当前对象未重新核验前不能重交；未知操作不会自动删除或迁移到其他工单。</p><ApprovalPanel v-for="r in retained" :key="r.context+identityKey" :owner="owner" :context-key="r.context" :transport="wire" :allowed-actions="[]" @changed="id=>completionId=id"/></section></template>
<style scoped>label{display:block;margin:14px 0 6px}input,select,textarea,button{font:inherit}input:not([type=checkbox]),select,textarea{box-sizing:border-box;width:min(100%,560px);padding:9px;border:1px solid #cbd5d2;border-radius:6px;display:block}textarea{min-height:90px}input[type=checkbox]{margin-right:8px}button{padding:8px 14px;margin:8px 8px 8px 0;border:1px solid #b9ccc7;border-radius:6px;background:#f6faf8;color:#125e53;cursor:pointer}button:disabled{opacity:.5;cursor:default}.panel{overflow-wrap:anywhere}</style>
