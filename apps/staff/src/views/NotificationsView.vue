<script setup lang="ts">
import {computed,onBeforeUnmount,reactive,ref,watch} from 'vue'
import {useRoute} from 'vue-router'
import {useSession} from '../session'
import {ObjectLease} from '../object-lease'
import {notificationOwner,notificationTransport,parseMessage,parseMessagePage,type Message,type MessagePage} from '../features/notification/notification-client'
import {NotificationReads} from '../features/notification/notification-read'
import {uuid} from '../features/approval/approval-client'
const auth=useSession(),route=useRoute(),page=ref(1),owner=computed(()=>notificationOwner(auth))
let alive=true
const messageId=computed(()=>typeof route.params.messageId==='string'?route.params.messageId:''),context=()=>JSON.stringify([route.fullPath,owner.value,page.value]),wire=notificationTransport(auth,()=>alive,context),reads=reactive(new NotificationReads(wire,()=>owner.value))
const lease=reactive(new ObjectLease<{list:MessagePage|null;detail:Message|null}>(auth,context,async()=>{
 if(!owner.value)throw Error('请以当前租户身份核验站内信。')
 if(messageId.value){if(!uuid(messageId.value))throw Error('站内信标识无效。');const r=await wire.request('GET','/api/notification/messages/'+messageId.value);if(r.status!==200)throw Error('当前站内信权限无法确认（HTTP '+r.status+'）。');const detail=parseMessage(r.body);if(detail.id!==messageId.value)throw Error('站内信归属无法确认。');return {list:null,detail}}
 const r=await wire.request('GET','/api/notification/messages?'+new URLSearchParams({page:String(page.value),size:'20'}));if(r.status!==200)throw Error('当前收件列表无法确认（HTTP '+r.status+'）。');return {list:parseMessagePage(r.body,page.value),detail:null}
},Date.now,30000))
function refresh(){lease.clear();if(owner.value)void lease.refresh()}
function move(delta:number){page.value+=delta;refresh()}
watch(()=>JSON.stringify([owner.value,route.fullPath]),()=>{reads.bind();page.value=1;refresh()},{immediate:true,flush:'sync'})
async function mark(){const detail=lease.value?.detail;if(!detail||detail.read)return;await reads.start(detail.id);if(alive&&reads.phase==='SUCCEEDED')refresh()}
async function original(query:boolean){if(query)await reads.query();else if(lease.value?.detail?.id===reads.pending?.messageId)await reads.retry();if(alive&&reads.phase==='SUCCEEDED')refresh()}
function visibility(){if(document.visibilityState==='hidden')lease.clear();else refresh()}
document.addEventListener('visibilitychange',visibility)
let last=Date.now();const timer=setInterval(()=>{lease.expire();if(Date.now()-last>=25000){last=Date.now();if(document.visibilityState!=='hidden'&&owner.value&&!lease.busy)void lease.refresh()}},1000)
onBeforeUnmount(()=>{alive=false;clearInterval(timer);lease.dispose();reads.dispose();document.removeEventListener('visibilitychange',visibility)})
</script>
<template><section class="page-heading"><h1>本人站内信</h1><p>消息仅提示业务事实；打开关联申请时会重新核验当前权限。</p></section>
<section class="panel"><button :disabled="!owner || lease.busy" @click="refresh">重新查询当前权限</button><p v-if="lease.busy">正在核验…</p><p v-if="lease.error" role="alert">{{lease.error}}</p>
<template v-if="lease.value?.list"><p v-if="!lease.value.list.items.length">暂无当前可见站内信。</p><ul><li v-for="m in lease.value.list.items" :key="m.id"><RouterLink :to="'/notifications/'+m.id">{{m.read?'':'未读 · '}}{{m.title}}</RouterLink> <time>{{new Date(m.createdAt).toLocaleString('zh-CN',{timeZone:'Asia/Shanghai'})}}</time></li></ul><p>第{{page}}页 · {{lease.value.list.total}}项</p><button :disabled="lease.busy || page<=1" @click="move(-1)">上一页</button><button :disabled="lease.busy || page*20>=lease.value.list.total" @click="move(1)">下一页</button></template>
<article v-if="lease.value?.detail"><h2>{{lease.value.detail.title}}</h2><p>{{lease.value.detail.body}}</p><p>{{lease.value.detail.read?'已读':'未读'}}</p><RouterLink v-if="lease.value.detail.object.type==='APPROVAL_PARTS'" :to="lease.value.detail.object.route">查看当前备件申请</RouterLink><RouterLink v-else-if="lease.value.detail.object.type==='TICKET'" :to="lease.value.detail.object.route">查看当前工单</RouterLink><template v-else><p>关联费用组 {{lease.value.detail.object.id}}；打开业务时重新核验当前权限。</p><RouterLink to="/finance">进入当前授权报价查询</RouterLink></template><button :disabled="lease.value.detail.read || reads.busy || !!reads.pending" @click="mark">标记已读</button></article>
<p v-if="reads.error" role="status">{{reads.error}}</p><section v-if="reads.pending"><h2>原标记操作恢复</h2><p>原操作 {{reads.pending.operationId}}；原消息 {{reads.pending.messageId}}。</p><p>结果未知时只查询原操作，不自动另建标记。</p><button :disabled="reads.busy || !owner" @click="original(true)">查询原操作</button><button v-if="reads.phase==='NOT_OBSERVED'" :disabled="reads.busy || !owner || lease.value?.detail?.id!==reads.pending.messageId" @click="original(false)">确认原操作重新标记</button><RouterLink :to="'/notifications/'+reads.pending.messageId">核验原消息</RouterLink></section>
</section></template>
<style scoped>button{padding:8px 14px;margin:8px 12px 8px 0}li{padding:12px 0}time{font-size:14px;color:#596963}article p{white-space:pre-wrap}section{overflow-wrap:anywhere}</style>
