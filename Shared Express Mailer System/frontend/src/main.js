import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'

import App from './App.vue'
import router from './router'

/**
 * 样式引入顺序是**有语义的，不能调换**：
 *   1) 先引 Element Plus 的基线样式——全量引入（app.use(ElementPlus)）**不会**自动带上样式，
 *      漏掉这一行所有组件的类名都没有对应规则，按钮/表格/弹窗会渲染成无样式的裸元素；
 *   2) 再引本工程的令牌与变量映射——我们的 :root 与 Element Plus 的 :root 同优先级，
 *      靠源码顺序决胜，必须排在其后，否则 --el-* 变量会被 Element Plus 的默认值覆盖回默认蓝。
 */
import 'element-plus/dist/index.css'
import './styles/index.css'

/**
 * 应用入口（见《系统设计说明书》10.1）。
 *
 * Element Plus 采用全量引入：本项目不追求首屏极致体积（校园规模、且已按 vue/element/echarts
 * 分块产出），换取的是「不存在按需引入漏注册组件导致运行期才报错」的确定性。
 * 语言包固定中文（全部交付物使用中文）。
 */

const app = createApp(App)

// 图标全量注册，供各页直接 <el-icon><Search /></el-icon> 使用
for (const [name, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(name, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus, { locale: zhCn })

/**
 * 全局错误兜底：未捕获的渲染错误不让页面白屏，至少留下可诊断的日志。
 * 注意不要在这里吞掉错误——控制台仍要打出来。
 */
app.config.errorHandler = (err, _instance, info) => {
  console.error('[SEMS] 未捕获的前端异常：', info, err)
}

app.mount('#app')
