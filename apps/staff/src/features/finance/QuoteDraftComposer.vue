<script setup lang="ts">
import {computed,reactive,ref,watch} from 'vue'
import {centsFromYuan,yuanFromCents,quoteBody,type QuoteLine,type QuoteDraft} from './quote-draft'
import type {PartQuoteOption} from './part-quote-catalog'
const props=defineProps<{context?:{ticketId:string;expectedVersion:string;quoteId:string};partOptions?:PartQuoteOption[]}>()
const emit=defineEmits<{draft:[body:ReturnType<typeof quoteBody>]}>()
const lines=reactive<{id:string;amount:string;kind:QuoteLine['kind'];approvalId:string}[]>([{id:crypto.randomUUID(),amount:'',kind:'LABOR',approvalId:''}])
const description=ref(''),charged=ref(true),error=ref(''),preview=ref<QuoteDraft|null>(null)
const total=computed(()=>{try{return yuanFromCents(lines.reduce((s,l)=>s+BigInt(centsFromYuan(l.amount)),0n).toString())}catch{return'金额待校验'}})
watch(()=>JSON.stringify([props.context,props.partOptions,lines,description.value,charged.value]),()=>{preview.value=null},{flush:'sync'})
function addLine(){lines.push({id:crypto.randomUUID(),amount:'',kind:'OTHER',approvalId:''})}
function exportDraft(){if(preview.value)emit('draft',quoteBody(preview.value,crypto.randomUUID()))}
function inspect(){
 error.value=''
 try{
  if(!props.context)throw Error('当前服务端费用组/报价上下文尚未提供')
  const items:QuoteLine[]=lines.map(line=>{
   const item:QuoteLine={id:line.id,amountCents:centsFromYuan(line.amount),kind:line.kind}
   if(line.kind==='PART'){
    const option=props.partOptions?.find(p=>p.binding.approvalId===line.approvalId)
    if(!option)throw Error('请从当前授权目录选择客户付费的已批准备件申请')
    item.partBinding={...option.binding}
   }
   return item
  })
  const draft:QuoteDraft={...props.context,replaces:[],lines:items,chargesCustomer:charged.value,pricingDescription:description.value}
  quoteBody(draft,crypto.randomUUID())
  preview.value=draft
 }catch(e){error.value=e instanceof Error?e.message:'草稿校验失败'}
}
</script>
<template>
 <section class="panel">
  <h2>报价草稿（尚未提交）</h2>
  <article v-for="(line,index) in lines" :key="line.id">
   <label>第{{index+1}}项类别<select v-model="line.kind" @change="line.approvalId=''"><option value="LABOR">人工</option><option value="PART">备件</option><option value="OTHER">其他</option></select></label>
   <label>人民币金额（元）<input v-model="line.amount" inputmode="decimal"></label>
   <label v-if="line.kind==='PART'">已批准的客户付费备件申请<select v-model="line.approvalId"><option value="">请选择当前授权申请</option><option v-for="option in partOptions??[]" :key="option.binding.approvalId" :value="option.binding.approvalId">{{option.label}}</option></select></label>
   <button :disabled="lines.length===1" @click="lines.splice(index,1)">移除本地草稿项</button>
  </article>
  <button :disabled="lines.length>=50" @click="addLine">增加本地明细</button>
  <label><input v-model="charged" type="checkbox">本报价向客户收费</label>
  <label>计价说明<textarea v-model="description"/></label>
  <p>本地合计 {{total}}元</p>
  <p v-if="error" role="alert">{{error}}</p>
  <button @click="inspect">核对本地报价草稿</button>
  <section v-if="preview"><p>待提交预览 {{total}}元</p><p v-for="line in preview.lines.filter(l=>l.kind==='PART')" :key="line.id">备件申请 {{line.partBinding!.approvalId}} · 版本{{line.partBinding!.approvalVersion}} · {{line.partBinding!.quantity}} {{line.partBinding!.unitCode}}</p><button @click="exportDraft">使用此预览准备明确提交</button></section>
 </section>
</template>
<style scoped>label{display:block;margin:12px 0}input:not([type=checkbox]),select,textarea{display:block;padding:9px;font:inherit;width:440px;max-width:100%;box-sizing:border-box}textarea{min-height:90px}button{margin:6px;padding:9px;font:inherit}article{padding:12px 0;border-bottom:1px solid #ddd}</style>
