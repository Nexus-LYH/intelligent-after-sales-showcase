<script setup lang="ts">
import{computed,onBeforeUnmount,reactive,ref,watch}from'vue'
import{useRoute}from'vue-router'
import{useSession}from'../../session'
import{captureRequestScope}from'../../request-scope'
import{ObjectLease}from'../../object-lease'
import{createManagerCatalog,type ManagerCatalog,type StockWarehouse,type StockPart}from'./stock-catalog'
import{ownerOf,stockTransport,canManageStock,id,uuid}from'./stock-client'
import{retainedInbounds}from'./inbound-work'
import InboundPanel from'./InboundPanel.vue'
const props=defineProps<{catalog?:ManagerCatalog}>(),auth=useSession(),route=useRoute(),catalog=computed(()=>{const w=String(route.params.warehouseId??''),stamp=route.fullPath;return props.catalog??createManagerCatalog(stockTransport(auth,()=>alive),()=>route.fullPath===stamp)}),warehouses=ref<StockWarehouse[]>([]),warehouse=ref(''),warehousePage=ref(1),warehouseTotal=ref(0),partPage=ref(1),directoryError=ref(''),busy=ref(false),tick=ref(0)
let alive=true,slot=0
const owner=computed(()=>ownerOf(auth)),identity=computed(()=>JSON.stringify(owner.value)),context=()=>JSON.stringify([identity.value,warehouse.value,partPage.value]),lease=reactive(new ObjectLease<{parts:StockPart[];total:number}>(auth,context,async()=>{const r=await catalog.value.parts(warehouse.value,partPage.value);if(r.page!==partPage.value||r.size!==20||!Number.isSafeInteger(r.total)||!Array.isArray(r.items)||r.items.some(p=>!id(p.id)||!p.name)||new Set(r.items.map(p=>p.id)).size!==r.items.length)throw Error('授权备件目录投影不符');return{parts:r.items,total:r.total}}))
const retained=computed(()=>{void tick.value;const o=owner.value;return o?Array.from(retainedInbounds.keys()).map(k=>JSON.parse(k)as string[]).filter(k=>k[0]===o.tenant&&k[1]===o.actor&&k[2]!==warehouse.value).map(k=>k[2]):[]})
async function load(){const currentSlot=++slot,stamp=identity.value,scope=captureRequestScope(auth,()=>alive&&slot===currentSlot&&stamp===identity.value);warehouses.value=[];warehouse.value='';lease.clear();busy.value=true;directoryError.value='';try{const r=await catalog.value.warehouses(warehousePage.value);if(!scope.current())return;if(r.page!==warehousePage.value||r.size!==20||!Number.isSafeInteger(r.total)||!Array.isArray(r.items)||r.items.some(w=>!id(w.id)||!w.name)||new Set(r.items.map(w=>w.id)).size!==r.items.length)throw Error('授权仓库目录投影不符');warehouses.value=r.items;warehouseTotal.value=r.total}catch(e){if(scope.current())directoryError.value=e instanceof Error?e.message:'目录未确认'}finally{if(scope.current())busy.value=false}}
async function choose(){partPage.value=1;lease.clear();if(warehouse.value)await lease.refresh()}
watch(()=>identity.value+route.fullPath,()=>{++slot;warehouses.value=[];warehouse.value='';lease.clear();if(owner.value)void load()},{immediate:true,flush:'sync'})
function visibility(){if(document.visibilityState==='hidden'){++slot;busy.value=false;warehouses.value=[];lease.clear()}else if(owner.value)void load()}
document.addEventListener('visibilitychange',visibility)
let previous=Date.now();const timer=setInterval(()=>{tick.value++;lease.expire();if(Date.now()-previous>=25000){previous=Date.now();if(owner.value&&warehouse.value&&document.visibilityState!=='hidden')void lease.refresh()}},1000)
onBeforeUnmount(()=>{alive=false;++slot;clearInterval(timer);lease.dispose();document.removeEventListener('visibilitychange',visibility)})
</script>
<template><section class="page-heading"><h1>库存入库</h1><p>仅当前仓库授权与stock.manage可提交；页面可见不代替服务端检查。</p></section><section class="panel"><p v-if="route.params.warehouseId" role="status">使用当前仓管授权目录；备件包含尚无余额的启用备件。深链标识不赋予权限，请从在线目录选择。</p><p v-if="directoryError" role="alert">{{directoryError}}</p><button :disabled="busy" @click="load">重新查询授权仓库</button><label for="inbound-warehouse">授权仓库</label><select id="inbound-warehouse" v-model="warehouse" :disabled="busy" @change="choose"><option value="">请选择仓库</option><option v-for="w in warehouses" :key="w.id" :value="w.id">{{w.name}}</option></select><button :disabled="warehousePage<=1||busy" @click="warehousePage--;load()">上一页仓库</button><button :disabled="warehousePage*20>=warehouseTotal||busy" @click="warehousePage++;load()">下一页仓库</button><p v-if="lease.error" role="alert">{{lease.error}}</p><p v-if="lease.busy">正在核验当前授权备件…</p><button v-if="warehouse" :disabled="lease.busy" @click="lease.refresh()">核验当前目录</button><button :disabled="partPage<=1||lease.busy" @click="partPage--;lease.clear();lease.refresh()">上一页备件</button><button :disabled="!lease.value||partPage*20>=lease.value.total||lease.busy" @click="partPage++;lease.clear();lease.refresh()">下一页备件</button></section>
<InboundPanel v-if="warehouse&&owner" :key="identity+warehouse" :warehouse-id="warehouse" :parts="lease.value?.parts??[]" :authorized="!!lease.value&&warehouses.some(w=>w.id===warehouse)"/>
<section v-if="retained.length" class="panel"><h2>本账号原入库恢复（目录核验前只读）</h2><InboundPanel v-for="w in retained" :key="identity+w" :warehouse-id="w" :parts="[]" :authorized="false"/></section></template>
<style scoped>label{display:block;margin:12px 0}select{display:block;width:min(100%,540px);padding:9px;font:inherit}button{padding:8px 12px;margin:6px;font:inherit}p{overflow-wrap:anywhere}</style>
