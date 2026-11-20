<script setup lang="ts">
import {onBeforeUnmount,ref,shallowReactive,watch} from 'vue'
import {PartFeeExecutionWork} from './part-fee-execution'
import type {FeeExecutionContext,FeeExecutionOwner,FeeExecutionProjection,FeeExecutionTransport} from './fee-execution-client'

const props=defineProps<{
 context:FeeExecutionContext;owner:FeeExecutionOwner|null;transport:FeeExecutionTransport;
 projection:FeeExecutionProjection|null;blocked:boolean
}>()
const emit=defineEmits<{active:[value:boolean];changed:[]}>()
const useIds=ref<Record<string,string>>({}),error=ref('')
const work=shallowReactive(new PartFeeExecutionWork(props.transport,()=>props.owner))
watch(()=>JSON.stringify([props.owner,props.context]),()=>{
 useIds.value={};error.value='';work.bind(props.context)
},{immediate:true})
watch(()=>!!work.operation||work.busy,active=>emit('active',active),{immediate:true})
async function queryUse(lineId:string){
 if(props.blocked)return
 await work.queryUse(lineId,useIds.value[lineId]??'')
}
async function execute(lineId:string){
 if(props.blocked)return
 error.value=''
 try{await work.start(lineId,props.projection)}catch(e){error.value=e instanceof Error?e.message:'备件收费参数无法确认'}
 if(work.operation?.state==='SUCCEEDED')emit('changed')
}
async function recover(query=true){
 if(!query&&props.blocked)return
 await work.recover(query,props.projection)
 if(work.operation?.state==='SUCCEEDED')emit('changed')
}
function next(){work.next();emit('changed')}
onBeforeUnmount(()=>{work.dispose();emit('active',false)})
</script>

<template>
 <section aria-label="备件收费执行">
  <p v-if="error" role="alert">{{error}}</p>
  <p v-if="work.useMessage" role="status">{{work.useMessage}}</p>
  <p v-if="work.message" role="status">{{work.message}}</p>
  <section v-if="work.operation" class="notice">
   <h2>原备件收费操作</h2>
   <p>UUID {{work.operation.key}} · {{work.operation.state}}</p>
   <p>批准行 {{work.operation.body.lineId}} · 原消耗UUID {{work.operation.body.useOperationId}}</p>
   <button :disabled="work.busy||!owner" @click="recover()">只查询原备件收费UUID</button>
   <button v-if="work.operation.state==='NOT_OBSERVED'||work.operation.state==='OWNER_COMMITTED'" :disabled="work.busy||blocked||!owner||!projection" @click="recover(false)">以原UUID原参数继续协调</button>
   <button v-if="work.operation.state==='SUCCEEDED'" :disabled="work.busy" @click="next">核验下一条备件收费</button>
  </section>
  <article v-for="line in projection?.lines.filter(line=>line.kind==='PART')??[]" :key="line.lineId" class="record-entry">
   <h2>备件收费执行</h2>
   <p>批准行 {{line.lineId}} · 已执行数量 {{line.executedQuantity}} · 剩余数量 {{line.remainingQuantity}}</p>
   <label>原库存消耗UUID
    <input v-model="useIds[line.lineId]" :disabled="!!work.operation||blocked||!owner" @input="work.clearUse()">
   </label>
   <button :disabled="!!work.operation||work.queryBusy||blocked||!owner||!useIds[line.lineId]||line.remainingQuantity==='0'" @click="queryUse(line.lineId)">查询原库存消耗</button>
   <p v-if="work.use?.lineId===line.lineId">原单 {{work.use.order.orderId}} · 备件 {{work.use.binding.partId}} · 批准数量 {{work.use.binding.quantity}}</p>
   <button :disabled="!!work.operation||work.busy||blocked||!owner||work.use?.lineId!==line.lineId||line.remainingQuantity==='0'" @click="execute(line.lineId)">提交备件收费执行</button>
  </article>
 </section>
</template>
