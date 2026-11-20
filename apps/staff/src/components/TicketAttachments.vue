<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { inject } from 'vue'
import { ticketReadDenied, isObjectReadDenied } from '../ticket-access'
import { useRoute } from 'vue-router'
import { tickets, transfer, type TicketFile } from '../ticket-api'
import { useTicketWork } from '../ticket-work'
const denyObject = inject(ticketReadDenied,undefined)
const props = defineProps<{ files: TicketFile[] }>(), route = useRoute(), work = useTicketWork(() => route.fullPath)
const preview = ref(''), previewType = ref(''), previewName = ref(''); let expiry: ReturnType<typeof setTimeout> | undefined
function clear() { if (preview.value) URL.revokeObjectURL(preview.value); preview.value = ''; previewType.value = ''; previewName.value = ''; clearTimeout(expiry) }
watch(() => [work.auth.generation, work.auth.identity?.loginAttemptId, route.fullPath, props.files], clear)
onBeforeUnmount(clear)
async function open(file: TicketFile, download: boolean) {
  const me = work.auth.identity
  if (work.busy.value || work.auth.phase !== 'NORMAL' || !me || me.realm !== 'TENANT' || !work.auth.csrf || work.auth.csrf.loginAttemptId !== me.loginAttemptId || !(me.identityType === 'CUSTOMER' || me.identityType === 'EMPLOYEE' && (me.permissions.includes('ticket.read') || me.permissions.includes('ticket.engineer.read')))) return
  const scope = work.begin(() => props.files.some(f => f.fileId === file.fileId)); clear()
  try {
    const a = await tickets.authorize(file.fileId, work.auth.csrf!.token); if (!scope.current()) return
    const expected = `/api/ticket/files/${encodeURIComponent(file.fileId)}/content?authorizationId=${encodeURIComponent(a.authorizationId)}`
    const expires = Date.parse(a.expiresAt)
    if (a.url !== expected || !Number.isFinite(expires) || expires <= Date.now() || expires > Date.now() + 301000) throw new Error('下载授权响应无法确认')
    const blob = await transfer<Blob>(expected, {}, true); if (!scope.current() || Date.parse(a.expiresAt) <= Date.now()) return
    const bytes = await blob.arrayBuffer(); if (!scope.current()) return
    const digest = await crypto.subtle.digest('SHA-256', bytes); if (!scope.current()) return
    const sha = Array.from(new Uint8Array(digest), b => b.toString(16).padStart(2, '0')).join('')
    if (sha !== file.sha256 || blob.size !== file.size || blob.type !== file.mediaType) throw new Error('下载内容与文件摘要不匹配，未展示或保存')
    preview.value = URL.createObjectURL(blob); previewType.value = blob.type; previewName.value = file.name
    expiry = setTimeout(clear, Math.max(0, Date.parse(a.expiresAt) - Date.now()))
    if (download) { const link = document.createElement('a'); link.href = preview.value; link.download = file.name; link.click() }
  } catch (e) { if (scope.current() && isObjectReadDenied(e)) denyObject?.(); if (scope.current()) clear(); work.fail(e, scope) } finally { scope.finish() }
}
</script>
<template><section><h2>私有附件</h2><p v-if="!files.length">无附件</p><p v-if="work.message.value" role="alert">{{ work.message.value }} {{ work.trace.value }}</p><ul><li v-for="f in files" :key="f.fileId">{{ f.name }} · {{ f.size }} 字节 · {{ f.state }} <el-button :disabled="work.busy.value || !work.auth.csrf" @click="open(f, false)">授权预览</el-button><el-button :disabled="work.busy.value || !work.auth.csrf" @click="open(f, true)">授权下载</el-button></li></ul><section v-if="preview"><p>{{ previewName }} <el-button @click="clear">关闭预览</el-button></p><img v-if="previewType.startsWith('image/')" :src="preview" :alt="previewName" style="max-width:100%;max-height:65vh"/><object v-else-if="previewType === 'application/pdf'" :data="preview" type="application/pdf" style="width:100%;height:65vh"><p>浏览器不支持PDF预览，请使用授权下载。</p></object><p>每次新的下载均重新授权；已下载到本机的内容无法追回。</p></section></section></template>

