<script setup lang="ts">
import { computed, shallowReactive } from 'vue'
import { useRoute } from 'vue-router'
import { ApiError } from '../api'
import { actorKey, customerWriter, useTicketWork } from '../ticket-work'
import type { TicketDetail } from '../ticket-api'
import { replyMatches, replyFiles, verifyReplyFile, type ReplyDraft, type ReplyFile } from '../ticket-supplement-work'
import { supplementStamp } from '../ticket-supplement-api'
import { validText } from '../ticket-flow'
const props = defineProps<{ draft: ReplyDraft; ticket: TicketDetail; readOnly?: boolean }>(), emit = defineEmits<{busy:[value:boolean]}>(), route = useRoute(), work = useTicketWork(() => route.fullPath)
const writable = computed(() => !props.readOnly && customerWriter(work.auth) && replyMatches(props.draft,actorKey(work.auth),props.ticket) && props.ticket.allowedActions?.includes('supplement-reply') && !props.draft.frozen)
const removable = (f: ReplyFile) => f.state === 'READY' || f.state === 'RETRY' && f.retryKind === 'FAILED' && f.result?.state === 'FAILED'
function readable(d: ReplyDraft) { return d.actor === actorKey(work.auth) && d.ticketId === props.ticket.id && d.roundId === props.ticket.roundId }
function start(d: ReplyDraft, f?: ReplyFile) { const stamp = supplementStamp(props.ticket); const scope = work.begin(() => props.draft === d && readable(d) && stamp === supplementStamp(props.ticket) && (!f || d.files.includes(f))); emit('busy',true); return {current:scope.current,finish:()=>{if(scope.current()) emit('busy',false); scope.finish()} } }
async function selected(event: Event) {
  const input = event.target as HTMLInputElement, file = input.files?.[0]; input.value = ''
  if (!file || !writable.value || work.busy.value) return
  const d = props.draft
  if (d.files.filter(f => f.state !== 'REMOVED').length >= 5 || file.size < 1 || file.size > 20971520 || !['image/jpeg','image/png','application/pdf'].includes(file.type) || !validText(file.name,1,200) || /[\\/:]/.test(file.name)) { work.message.value = '最多5份PNG/JPEG/PDF，每份非空且≤20MiB，文件名须为1～200字符且不能含路径或控制字符；服务端仍校验格式'; return }
  const scope = start(d)
  try { const bytes = await file.arrayBuffer(); if (!scope.current()) return; const hash = await crypto.subtle.digest('SHA-256',bytes); if (!scope.current()) return; const f: ReplyFile = shallowReactive({uploadId:crypto.randomUUID(),file,sha256:Array.from(new Uint8Array(hash),b=>b.toString(16).padStart(2,'0')).join(''),state:'UNKNOWN'}); d.files.push(f); scope.finish(); await run(f,'upload') } catch(e) {work.fail(e,scope)} finally {scope.finish()}
}
async function run(f: ReplyFile, mode: 'upload' | 'query' | 'remove') {
  const d = props.draft
  if (!readable(d) || work.busy.value || mode !== 'query' && !writable.value || mode === 'remove' && !removable(f)) return
  const scope = start(d,f); f.state = 'UNKNOWN'; f.retryKind = undefined
  try {
    if (mode === 'remove') { const r = await replyFiles.remove(d,f,work.auth.csrf!.token); if (!scope.current()) return; if (r.fileId !== f.result?.fileId || !['DELETING','DELETED'].includes(r.state)) throw new ApiError(503,'REMOVE_RESPONSE_MISMATCH','移除回执无法确认，请查询原上传'); f.state = 'REMOVED'; return }
    const r = mode === 'query' ? await replyFiles.query(d,f) : await replyFiles.upload(d,f,work.auth.csrf!.token)
    if (!scope.current()) return; verifyReplyFile(d,f,r); f.result = r
    f.state = r.state === 'READY' ? 'READY' : r.state === 'FAILED' && r.nextAction === 'RETRY' ? 'RETRY' : r.state === 'DELETED' ? 'REMOVED' : 'UNKNOWN'
    if (f.state === 'RETRY') f.retryKind = 'FAILED'
  } catch(e) {
    if (!scope.current()) return
    if (mode === 'query' && e instanceof ApiError && e.status === 404 && e.code === 'TARGET_NOT_ACCESSIBLE') { f.state = 'RETRY'; f.retryKind = 'NOT_OBSERVED'; work.message.value = writable.value ? '本次未观察到原上传，不证明未执行或不会迟到。保留同UUID、同File、同摘要及当前请求/回复上下文，仅可显式重传。' : '本次未观察到原上传，不证明未执行。当前上下文不可写，仅保留原File、摘要与UUID查询，不允许重传或丢弃未知附件。' }
    else work.fail(e,scope)
  } finally {scope.finish()}
}
</script>
<template><section class="supplement-files"><h4>本次回复私有附件（可选）</h4><p>仅属于当前问题与回复UUID {{ draft.key }}，不与原报修或其他补充请求混用。未知附件须先查询，不自动重传或丢弃。</p><input v-if="!readOnly" type="file" accept="image/png,image/jpeg,application/pdf" aria-label="选择补充附件" :disabled="!writable || work.busy.value" @change="selected"/><p v-if="work.message.value" role="alert">{{ work.message.value }} {{ work.trace.value }}</p><article v-for="f in draft.files" :key="f.uploadId"><p>{{ f.file.name }} · 上传UUID {{ f.uploadId }} · {{ f.state }}</p><el-button v-if="f.state !== 'REMOVED'" :disabled="work.busy.value" @click="run(f,'query')">查询原补充上传</el-button><el-button v-if="f.state === 'RETRY'" :disabled="!writable || work.busy.value" @click="run(f,'upload')">显式原键同文件重传</el-button><el-button v-if="removable(f)" :disabled="!writable || work.busy.value" @click="run(f,'remove')">移除未引用补充附件</el-button></article></section></template>
