<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { useSession } from '../../session'
import { ObjectLease } from '../../object-lease'
import { createManagerCatalog, type StockWarehouse } from './stock-catalog'
import { ownerOf, stockTransport, id, uuid } from './stock-client'
import { createStockLedger, type LedgerPage, type StockBalance, type StockMovement } from './stock-ledger'

const auth = useSession()
const warehouse = ref(''), partInput = ref(''), orderInput = ref(''), message = ref('')
const part = ref(''), order = ref(''), warehousePage = ref(1), balancePage = ref(1), movementPage = ref(1)
let alive = true
const identity = computed(() => JSON.stringify(ownerOf(auth)))
const context = () => JSON.stringify([identity.value, warehouse.value, part.value, order.value])
const wire = stockTransport(auth, () => alive)
const directory = reactive(new ObjectLease<LedgerPage<StockWarehouse>>(auth,
  () => identity.value + warehousePage.value, () => {
    const stamp = identity.value + warehousePage.value
    return createManagerCatalog(wire, () => identity.value + warehousePage.value === stamp).warehouses(warehousePage.value)
  }))
const authorized = computed(() => !!directory.value?.items.some(row => row.id === warehouse.value))
const balances = reactive(new ObjectLease<LedgerPage<StockBalance>>(auth,
  () => context() + ':balance:' + balancePage.value, () => {
    const stamp = context() + ':balance:' + balancePage.value
    return createStockLedger(wire, () => authorized.value && context() + ':balance:' + balancePage.value === stamp)
      .balances(warehouse.value, part.value, balancePage.value)
  }))
const movements = reactive(new ObjectLease<LedgerPage<StockMovement>>(auth,
  () => context() + ':movement:' + movementPage.value, () => {
    const stamp = context() + ':movement:' + movementPage.value
    return createStockLedger(wire, () => authorized.value && context() + ':movement:' + movementPage.value === stamp)
      .movements(warehouse.value, part.value, order.value, movementPage.value)
  }))
const labels: Record<string, string> = { INBOUND: '入库', RESERVE: '预留', ISSUE: '领用', USE: '消耗', RETURN: '退回', EXPIRE: '预留到期释放' }

function clear() { balances.clear(); movements.clear(); message.value = '' }
async function search() {
  clear()
  if (!authorized.value || partInput.value && !id(partInput.value) || orderInput.value && !uuid(orderInput.value)) {
    message.value = '请选择当前授权仓库，备件编号须为正整数，原执行单须为完整UUID。'
    return
  }
  part.value = partInput.value; order.value = orderInput.value
  balancePage.value = 1; movementPage.value = 1
  await Promise.all([balances.refresh(), movements.refresh()])
}
async function turnDirectory(delta: number) {
  warehouse.value = ''; clear(); directory.clear(); warehousePage.value += delta
  await directory.refresh()
}
async function turnBalance(delta: number) {
  balances.clear(); balancePage.value += delta
  if (authorized.value) await balances.refresh()
}
async function turnMovement(delta: number) {
  movements.clear(); movementPage.value += delta
  if (authorized.value) await movements.refresh()
}
watch(identity, () => {
  warehouse.value = ''; partInput.value = ''; orderInput.value = ''; warehousePage.value = 1
  clear(); directory.clear()
  if (ownerOf(auth)) void directory.refresh()
}, { immediate: true })
watch(warehouse, clear)
watch(authorized, allowed => { if (!allowed) clear() })
function visibility() {
  clear(); directory.clear()
  if (document.visibilityState !== 'hidden' && ownerOf(auth)) void directory.refresh()
}
document.addEventListener('visibilitychange', visibility)
const timer = setInterval(() => { directory.expire(); balances.expire(); movements.expire() }, 1000)
onBeforeUnmount(() => {
  alive = false; clearInterval(timer); document.removeEventListener('visibilitychange', visibility)
  directory.dispose(); balances.dispose(); movements.dispose()
})
</script>

<template>
  <section class="page-heading"><h1>库存余额与流水</h1><p>按当前授权仓库查询现存量、预留和原始库存记录。消耗记账不重复扣减现存量。</p></section>
  <section class="panel">
    <p v-if="directory.error" role="alert">{{ directory.error }}</p>
    <button :disabled="directory.busy" @click="clear(); directory.clear(); directory.refresh()">重新核验授权仓库</button>
    <form @submit.prevent="search">
      <label for="ledger-warehouse">授权仓库</label>
      <select id="ledger-warehouse" v-model="warehouse" :disabled="directory.busy"><option value="">请选择仓库</option><option v-for="row in directory.value?.items" :key="row.id" :value="row.id">{{ row.name }}</option></select>
      <button type="button" :disabled="warehousePage <= 1 || directory.busy" @click="turnDirectory(-1)">上一页仓库</button>
      <button type="button" :disabled="!directory.value || warehousePage * 20 >= directory.value.total || directory.busy" @click="turnDirectory(1)">下一页仓库</button>
      <label for="ledger-part">备件编号（可选）</label><input id="ledger-part" v-model.trim="partInput" inputmode="numeric">
      <label for="ledger-order">原执行单UUID（仅筛选流水，可选）</label><input id="ledger-order" v-model.trim="orderInput">
      <button type="submit" :disabled="!authorized || balances.busy || movements.busy">查询余额与流水</button>
    </form>
    <p v-if="message" role="alert">{{ message }}</p>
  </section>
  <section class="panel"><h2>当前余额</h2>
    <p v-if="balances.error" role="alert">{{ balances.error }}</p><p v-if="balances.busy" role="status">正在核验余额…</p>
    <template v-if="authorized && balances.value"><p>共 {{ balances.value.total }} 条 · 第 {{ balancePage }} 页</p>
      <div class="table-wrap"><table><thead><tr><th>备件编号</th><th>现存量</th><th>预留量</th><th>可用量</th><th>资料版本</th></tr></thead><tbody><tr v-for="row in balances.value.items" :key="row.partId"><td>{{ row.partId }}</td><td>{{ row.onHand }}</td><td>{{ row.reserved }}</td><td>{{ row.available }}</td><td>{{ row.rowVersion }}</td></tr></tbody></table></div>
      <p v-if="!balances.value.items.length">当前条件下没有余额记录。</p>
      <button :disabled="balancePage <= 1 || balances.busy" @click="turnBalance(-1)">上一页余额</button><button :disabled="balancePage * 20 >= balances.value.total || balances.busy" @click="turnBalance(1)">下一页余额</button>
    </template>
  </section>
  <section class="panel"><h2>原始库存流水</h2>
    <p v-if="movements.error" role="alert">{{ movements.error }}</p><p v-if="movements.busy" role="status">正在核验流水…</p>
    <template v-if="authorized && movements.value"><p>共 {{ movements.value.total }} 条 · 第 {{ movementPage }} 页 · 时间为UTC</p>
      <div class="table-wrap"><table><thead><tr><th>时间</th><th>动作</th><th>备件</th><th>现存变化</th><th>预留变化</th><th>消耗变化</th><th>操作者</th><th>原执行单</th><th>原操作UUID</th></tr></thead><tbody><tr v-for="row in movements.value.items" :key="row.source + row.operationId + row.partId"><td>{{ row.recordedAt }}</td><td>{{ labels[row.action] }}</td><td>{{ row.partId }}</td><td>{{ row.onHandDelta }}</td><td>{{ row.reservedDelta }}</td><td>{{ row.usedDelta }}</td><td>{{ row.actorUserId === '0' ? '系统' : row.actorUserId }}</td><td><RouterLink v-if="row.orderId" :to="'/stock/warehouses/' + warehouse + '/orders/' + row.orderId">{{ row.orderId }}</RouterLink><span v-else>入库</span></td><td>{{ row.operationId }}</td></tr></tbody></table></div>
      <p v-if="!movements.value.items.length">当前条件下没有库存流水。</p>
      <button :disabled="movementPage <= 1 || movements.busy" @click="turnMovement(-1)">上一页流水</button><button :disabled="movementPage * 20 >= movements.value.total || movements.busy" @click="turnMovement(1)">下一页流水</button>
    </template>
  </section>
</template>

<style scoped>
label { display: block; margin: 12px 0 6px; }
input, select { width: min(100%, 560px); box-sizing: border-box; padding: 9px; font: inherit; }
button { margin: 6px; padding: 9px; font: inherit; }
.table-wrap { overflow: auto; }
table { width: 100%; border-collapse: collapse; }
td, th { text-align: left; padding: 10px; border-bottom: 1px solid #ddd; overflow-wrap: anywhere; }
</style>
