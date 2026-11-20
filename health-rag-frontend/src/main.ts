import { createApp } from 'vue'
import { createPinia } from 'pinia'
import piniaPluginPersistedstate from 'pinia-plugin-persistedstate'
import App from '@/App.vue'
import router from '@/router'

/* Element Plus 函数式组件的样式必须显式引入。
 * unplugin-vue-components 的 ElementPlusResolver 只按需注入「模板里出现过」的组件样式，
 * ElMessageBox / ElMessage 通过 import { ... } 函数式调用，不走模板解析，
 * 因此它们的 CSS 不会被自动带上。缺失时的表现：确认框无遮罩、无边框、铺满整行并挤在
 * 页面左上角（垂直/水平都不居中），弹窗按钮退化成浏览器原生灰按钮。
 * 这两个入口会级联引入 base / input / button / overlay 等依赖样式。 */
import 'element-plus/es/components/message-box/style/css'
import 'element-plus/es/components/message/style/css'

// 样式入口：Tailwind + 设计令牌（颜色/字号/圆角只允许在 tokens.css 定义）
import '@/styles/main.css'

/* Element Plus 覆盖层修正：把 el-dialog 的 15vh 上边距改为真正的垂直居中。
 * 必须放在 main.css 之后 —— 覆盖规则需要晚于上游组件样式生效。 */
import '@/styles/element-plus-overrides.css'

const pinia = createPinia()
// token 持久化由插件完成；userInfo 明确不持久化（见 stores/user.ts 文件头说明）
pinia.use(piniaPluginPersistedstate)

const app = createApp(App)
app.use(pinia)
app.use(router)
app.mount('#app')
