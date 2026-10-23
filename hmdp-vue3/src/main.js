import { createApp } from 'vue'
import pinia from '@/stores'

import App from '@/App.vue'
import router from '@/router'
// 顺序很重要：先引入 Element Plus 全量样式（Loading 遮罩等依赖它），
// 再引入项目自定义样式。否则 Element Plus 的 :root 变量会覆盖我们的主题色，
// 表现为按钮/链接仍为默认蓝 #409eff。
import 'element-plus/dist/index.css'
// 引入全局样式（含主题变量覆盖）
import '@/assets/css/main.css'

const app = createApp(App)

app.use(pinia)
app.use(router)

app.mount('#app')
