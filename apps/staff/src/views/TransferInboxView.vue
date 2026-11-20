<script setup lang="ts">
import { computed, ref, watch, onBeforeUnmount } from 'vue'
import { useRoute,useRouter } from 'vue-router'
import { ApiError } from '../api'
import { actorKey,useTicketWork } from '../ticket-work'
import { useTicketActionWork,type PendingTicketAction } from '../ticket-action-work'
import { actionApi,type ActionResult } from '../ticket-action-api'
import { transferApi,validInbox,transferStamp,transferStates,verifyTransferResult,type TransferInbox } from '../ticket-transfer-api'
import { inboxOrigin,rememberInboxOrigin,type InboxOrigin } from '../transfer-inbox-work'
import { tickets } from '../ticket-api'
import { validText,statusLabel } from '../ticket-flow'
const route=useRoute(),router=useRouter(),reader=useTicketWork(()=>route.fullPath)
const continuation=useTicketWork(()=>route.fullPath)
const summary=ref<TransferInbox|null>(null),origin=ref<InboxOrigin|null>(null),items=ref<TransferInbox[]>([]),total=ref(0),notice=ref('')
const action=ref(''),reason=ref(''),preview=ref(''),confirmed=ref(false),missing=ref(false),uuid=ref('')
const context=()=>JSON.stringify([origin.value,summary.value&&transferStamp(summary.value)])
const work=useTicketActionWork(()=>route.fullPath,()=>origin.value?.ticketId||'',context)
const signature=computed(()=>JSON.stringify([context(),action.value,reason.value]))
const canWrite=computed(()=>reader.auth.phase==='NORMAL'&&reader.auth.identity?.realm==='TENANT'&&reader.auth.identity.identityType==='EMPLOYEE'&&reader.auth.identity.permissions.includes('ticket.engineer.read')&&reader.auth.identity.permissions.includes('ticket.assignment.accept')&&reader.auth.csrf?.contextKind==='AUTH'&&reader.auth.csrf.loginAttemptId===reader.auth.identity.loginAttemptId)
const actions=computed(()=>summary.value?.state==='PENDING_CONFIRMATION'&&summary.value.effectiveState==='PENDING_CONFIRMATION'?summary.value.allowedActions.filter(a=>a!=='transfer-accept'||reader.auth.identity?.permissions.includes('ticket.record')):[])
watch(signature,()=>{confirmed.value=false})
async function load(){
 const scope=reader.begin();summary.value=null;items.value=[];total.value=0;action.value='';reason.value='';confirmed.value=false;missing.value=false
 origin.value=inboxOrigin(actorKey(reader.auth),String(route.params.requestId||''))
 try{
  if(route.params.requestId){const r=await transferApi.summary(String(route.params.requestId));if(!scope.current())return
   if(r.transferRequestId!==route.params.requestId||!validInbox(r,reader.auth.identity!.userId))throw new ApiError(503,'TRANSFER_INBOX_PROJECTION_INVALID','有限收件摘要无法确认，已停止显示')
   summary.value=r;origin.value=rememberInboxOrigin(actorKey(reader.auth),r)
  }else{const r=await transferApi.inbox(Number(route.query.page)||1);if(!scope.current())return
   if(!Array.isArray(r.items)||r.items.some(x=>!validInbox(x,reader.auth.identity!.userId)))throw new ApiError(503,'TRANSFER_INBOX_PROJECTION_INVALID','收件范围或有限投影无法确认')
   items.value=r.items;total.value=r.total
  }
  return true
 }catch(e){if(!scope.current())return;reader.fail(e,scope)}finally{scope.finish()}
}
watch(()=>[route.fullPath,actorKey(reader.auth)],()=>void load(),{immediate:true})
watch(()=>[context(),work.current.value],()=>{const p=work.current.value;if(p&&summary.value&&(p.body.expectedVersion!==summary.value.rowVersion||p.body.sourceAssignmentId!==summary.value.sourceAssignmentId||summary.value.effectiveState!=='PENDING_CONFIRMATION'||p.path.split('/transfer-requests/')[1]?.split('/')[0]!==summary.value.transferRequestId)){const scope=work.begin(p);work.archive(p,scope)}},{immediate:true})
function choose(name:string){if(!canWrite.value||work.busy.value||reader.busy.value||work.current.value||!actions.value.includes(name))return;action.value=name;reason.value='';preview.value='';confirmed.value=false}
function replayAllowed(p:PendingTicketAction){const r=summary.value;return !!r&&canWrite.value&&actions.value.includes(p.action)&&r.rowVersion===p.body.expectedVersion&&r.roundId===p.body.roundId&&r.sourceAssignmentId===p.body.sourceAssignmentId&&r.transferRequestId===p.path.split('/transfer-requests/')[1]?.split('/')[0]&&r.targetEngineerUserId===reader.auth.identity?.userId}
async function currentAccess(expectedAssignment?:string){
 if(!origin.value||reader.busy.value)return
 const id=origin.value.ticketId,stamp=context(),scope=reader.begin(()=>stamp===context())
 try{const r=await tickets.detail(id);if(!scope.current())return
  if(r.id!==id)throw new ApiError(503,'TICKET_RESPONSE_MISMATCH','工单响应不符')
  if(expectedAssignment&&(r.assignment?.assignmentId!==expectedAssignment||r.assignment.engineerUserId!==reader.auth.identity?.userId)){notice.value='这是历史接受回执，当前负责人已变化；不会按旧回执自动进入当前负责人工单。';return}
  await router.push(`/tickets/${encodeURIComponent(id)}`)
 }catch(e){if(!scope.current())return;if(e instanceof ApiError&&(e.status===403||e.status===404))notice.value='当前没有该完整工单访问权限；原收件与窄回执不授正文或附件权。';else reader.fail(e,scope)}finally{scope.finish()}
}
async function send(p:PendingTicketAction){
 if(work.busy.value||reader.busy.value||!replayAllowed(p))return
 const scope=work.begin(p);missing.value=false
 const original=origin.value,follow=continuation.begin(()=>!!original && origin.value?.ticketId===original.ticketId && origin.value?.transferRequestId===original.transferRequestId && origin.value?.sourceAssignmentId===original.sourceAssignmentId && origin.value?.targetEngineerUserId===original.targetEngineerUserId && origin.value?.targetDepartmentId===original.targetDepartmentId)
 try{const r=await actionApi.send(p.path,p.key,p.body,reader.auth.csrf!.token);if(!scope.current())return;verifyTransferResult(r,p)
  if(work.complete(p,scope)){notice.value='本次转派决定已确认，正在重新核对当前归属；不根据历史状态恢复责任。';const loaded=await load();if(loaded && follow.current() && r.state==='ACCEPTED')await currentAccess(r.assignmentId||undefined)}
 }catch(e){if(!scope.current())return
  if(e instanceof ApiError&&(e.status===409||e.status===400)){if(work.archive(p,scope)){notice.value=`决定未完成：${e.code}。旧确认已关闭，请按当前源分派、请求和版本重新决定。`;await load()}}
  else work.fail(e,scope)
 }finally{scope.finish();follow.finish()}
}
function submit(){const r=summary.value;if(!r||!canWrite.value||work.current.value||work.busy.value||!actions.value.includes(action.value))return
 if(action.value==='transfer-reject'&&(!reason.value.trim()||!validText(reason.value,5,500,true))){work.message.value='拒绝理由须为5～500字符';return}
 if(preview.value!==signature.value||!confirmed.value){work.message.value='请预览并确认原请求、源分派及目标责任';return}
 const body={expectedVersion:r.rowVersion,roundId:r.roundId,sourceAssignmentId:r.sourceAssignmentId,decision:action.value==='transfer-accept'?'ACCEPT':'REJECT',...(action.value==='transfer-reject'?{reason:reason.value}:{})}
 const p=work.remember(action.value,`/ticket/tickets/${encodeURIComponent(r.ticketId)}/transfer-requests/${encodeURIComponent(r.transferRequestId)}/decision`,body,undefined,{targetDepartmentId:r.targetDepartmentId,targetEngineerUserId:r.targetEngineerUserId});void send(p)
}
async function query(p:PendingTicketAction,archived=false){
 if(work.busy.value||reader.busy.value)return
 const scope=work.begin(archived?undefined:p);missing.value=false
 try{const r=await actionApi.operation(p.key);if(!scope.current())return;verifyTransferResult(r,p)
  if(archived||work.complete(p,scope)){scope.finish();notice.value='本人原决定历史回执已核对；这不代表当前拥有工单，访问全文须重新核验。';await load()}
 }catch(e){if(!scope.current())return;if(e instanceof ApiError&&e.status===404&&e.code==='OPERATION_NOT_FOUND'){if(!archived)missing.value=true;work.message.value='本次未观察到原决定，不证明未执行；只有当前收件仍可决定时，才可显式原UUID原参数重交。'}else work.fail(e,scope)}finally{scope.finish()}
}
async function refreshReadOnly(){
 if(reader.busy.value||work.busy.value||reader.auth.phase!=='NORMAL')return
 if(!route.params.requestId){void load();return}
 const previous=summary.value,stamp=context(),scope=reader.begin(()=>stamp===context())
 try{const r=await transferApi.summary(String(route.params.requestId));if(!scope.current())return;if(!validInbox(r,reader.auth.identity!.userId)||r.transferRequestId!==route.params.requestId)throw new ApiError(503,'TRANSFER_INBOX_PROJECTION_INVALID','有限收件摘要无法确认')
 if(!previous||transferStamp(r)!==transferStamp(previous)||JSON.stringify(r.allowedActions)!==JSON.stringify(previous.allowedActions)){scope.finish();void load()}
 }catch(e){if(!scope.current())return;scope.finish();summary.value=null;items.value=[];action.value='';confirmed.value=false;reader.fail(e,reader.begin())}finally{scope.finish()}
}
const readTimer=setInterval(()=>void refreshReadOnly(),30000)
function inboxVisibility(){if(document.visibilityState==='hidden'){summary.value=null;items.value=[];action.value='';reason.value='';confirmed.value=false;reader.begin().finish();work.begin().finish();continuation.begin().finish()}else if(reader.auth.phase==='NORMAL')void load()}
document.addEventListener('visibilitychange',inboxVisibility)
onBeforeUnmount(()=>{clearInterval(readTimer);document.removeEventListener('visibilitychange',inboxVisibility)})
function pageChanged(page:number){void router.push({query:{page}})}
async function lookup(){if(work.busy.value||reader.busy.value||!uuid.value)return;const key=uuid.value,scope=work.begin()
 try{const r:ActionResult=await actionApi.operation(key);if(!scope.current()||key!==uuid.value)return
  if(r.operationId!==key||r.action!=='transfer-decision'||!['ACCEPTED','REJECTED'].includes(r.state)||route.params.requestId&&r.transferRequestId!==route.params.requestId)throw new ApiError(503,'TRANSFER_RESPONSE_MISMATCH','非当前本人原转派决定回执')
  notice.value=`原决定 ${r.state==='ACCEPTED'?'接受':'拒绝'} 已确认；历史回执不授完整工单或附件权。`
 }catch(e){if(!scope.current())return;if(e instanceof ApiError&&e.status===404)work.message.value='本次未观察到该本人原决定；没有原参数时只能查询，不重建或重交。';else work.fail(e,scope)}finally{scope.finish()}
}
</script>
<template><section class="page-heading"><h1>发给我的转派</h1><p>接受前仅提供必要交接摘要，不开放完整工单、客户联系方式、内部处理记录或附件。</p><RouterLink v-if="route.params.requestId" to="/transfers">← 本人收件列表</RouterLink></section>
 <section class="panel transfer-inbox"><p v-if="notice" role="status">{{notice}}</p><p v-if="reader.message.value" role="alert">{{reader.message.value}} {{reader.trace.value}}</p><p v-if="work.message.value" role="alert">{{work.message.value}} {{work.trace.value}}</p>
 <article v-for="r in items" :key="r.transferRequestId" class="record-entry"><RouterLink :to="`/transfers/${r.transferRequestId}`">{{r.ticketNo}} · {{r.handoffSummary.title}}</RouterLink><p>原负责人 {{r.sourceEngineerUserId}} → 指定目标 {{r.targetEngineerUserId}} · 期限 {{r.expiresAt}}</p></article>
 <section v-if="summary" class="limited-transfer-summary"><h2>有限交接摘要</h2><p>{{summary.ticketNo}} · {{summary.handoffSummary.title}}</p><p>设备：{{summary.handoffSummary.deviceName}}</p><p style="white-space:pre-wrap">必要故障摘要：{{summary.handoffSummary.faultSummary}}</p><p>当前主状态 {{statusLabel(summary.status)}} · 版本 {{summary.rowVersion}} · 轮次 {{summary.roundId}}</p><p>源分派 {{summary.sourceAssignmentId}} · 原工程师 {{summary.sourceEngineerUserId}} / {{summary.sourceDepartmentId}} → 本人 {{summary.targetEngineerUserId}} / {{summary.targetDepartmentId}}</p><p>转派内部理由：{{summary.reason}}</p><p>{{transferStates[summary.effectiveState]}} · 服务端期限 {{summary.expiresAt}}</p><p v-if="summary.expiredAtPending">服务器已判断到期，持久超时事实待系统落账；打开页面不会推进超时处理。</p><p>本人确认接受前，原工程师继续负责。拒绝/撤回/超时不会把主状态恢复成发起时旧状态。</p></section>
 <section v-if="work.current.value" aria-label="原转派决定恢复"><h2>原决定结果待确认</h2><p>原UUID {{work.current.value.key}} · 源分派 {{work.current.value.body.sourceAssignmentId}}</p><el-button :disabled="work.busy.value || reader.busy.value" @click="query(work.current.value)">查询原转派决定</el-button><el-button v-if="missing" :disabled="work.busy.value || reader.busy.value || !replayAllowed(work.current.value)" @click="send(work.current.value)">显式原UUID原参数重交转派决定</el-button></section>
 <template v-else><div class="actions"><el-button v-for="name in actions" :key="name" :disabled="!canWrite || work.busy.value || reader.busy.value" @click="choose(name)">{{name==='transfer-accept'?'接受转派':'拒绝转派'}}</el-button></div>
 <form v-if="action && summary" class="action-form" @submit.prevent="submit"><h3>{{action==='transfer-accept'?'接受交接':'拒绝交接'}}确认</h3><fieldset :disabled="work.busy.value || reader.busy.value"><label v-if="action==='transfer-reject'">内部拒绝理由<el-input v-model="reason" type="textarea" aria-label="内部拒绝理由"/></label><p>接受无需理由；接受将建立已接单的新分派，无需再点普通接单。</p><el-button @click="preview=signature;confirmed=false">预览转派决定</el-button><section v-if="preview===signature" class="notice"><p>请求 {{summary.transferRequestId}} · 源 {{summary.sourceAssignmentId}} · 目标 {{summary.targetEngineerUserId}} / {{summary.targetDepartmentId}}</p><p style="white-space:pre-wrap">{{reason}}</p><label><input v-model="confirmed" type="checkbox"/>确认本次源分派、请求、目标和交接责任</label></section><el-button native-type="submit" :disabled="!canWrite">提交转派决定</el-button></fieldset></form></template>
 <section v-if="work.archived.value.length" class="notice"><h3>旧转派决定仅可查询</h3><p>请求或源分派/版本已变化，旧理由不迁移到新请求，不按历史接受回执恢复负责人。</p><el-button v-for="p in work.archived.value" :key="p.key" :disabled="work.busy.value || reader.busy.value" @click="query(p,true)">查询旧转派决定 {{p.key}}</el-button></section>
 <el-button v-if="summary?.state==='ACCEPTED'" :disabled="reader.busy.value||work.busy.value" @click="currentAccess()">核对当前工单访问权限</el-button>
 <el-button :disabled="reader.busy.value||work.busy.value" @click="load">重新查询收件</el-button><el-pagination v-if="!route.params.requestId" :current-page="Number(route.query.page)||1" :total="total" :page-size="20" layout="prev, pager, next, total" @current-change="pageChanged"/>
 <details><summary>仅查询本人原转派决定UUID</summary><form class="actions" @submit.prevent="lookup"><el-input v-model="uuid" aria-label="原转派决定UUID"/><el-button native-type="submit" :disabled="work.busy.value||reader.busy.value">查询本人原转派决定</el-button></form></details>
 </section></template>
<style scoped>.transfer-inbox :deep(.el-button){max-width:100%;height:auto;min-height:32px;white-space:normal;overflow-wrap:anywhere}</style>
