import { createApp } from 'vue'
import { createPinia } from 'pinia'
import { ElButton, ElInput, ElTag, ElTable, ElTableColumn, ElSelect, ElOption, ElPagination, ElDialog, ElLoading } from 'element-plus'
import 'element-plus/dist/index.css'
import App from '../../staff/src/App.vue'
import '../../staff/src/style.css'
import { router } from './router'
const app = createApp(App).use(createPinia()).use(router)
for (const component of [ElButton, ElInput, ElTag, ElTable, ElTableColumn, ElSelect, ElOption, ElPagination, ElDialog]) app.component(component.name!, component)
app.directive('loading', ElLoading.directive).mount('#app')
