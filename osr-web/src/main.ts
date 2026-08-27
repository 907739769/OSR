import { createApp } from 'vue'
import { createPinia } from 'pinia'
import vuetify from './plugins/vuetify'
import App from './App.vue'
import router from './router'
import './styles/index.scss'

const app = createApp(App)

/**
 * 组件错误的最后一道兜底。日常的页面异常由 `components/ErrorBoundary.vue` 就地接住
 * （它返回 false 阻止冒泡，所以不会在这里重复报一遍），走到这里的只剩两类：
 * 边界自身渲染时抛的错，以及边界还没挂上去（布局外壳本身出错）。
 *
 * 只记不吞：这里没有可展示的位置，硬弹一个 toast 会在错误连发时刷屏；而没有这个
 * handler 时 Vue 只会往控制台打一行没有上下文的 warn，连出错的组件是哪个都不说。
 */
app.config.errorHandler = (err, _instance, info) => {
  console.error('[app]', info, err)
}

app.use(createPinia())
app.use(router)
app.use(vuetify)
app.mount('#app')

/**
 * 撤掉 index.html 里的启动屏。
 *
 * 时机是 router.isReady() 而不是 app.mount() 之后：mount 只代表 Vue 跑起来了，此时首次导航
 * 还没走完（守卫里要串行拉 getUserInfo + getRouters、注册动态路由、再按需加载首屏组件 chunk），
 * router-view 还是空的。在 mount 处撤掉，慢网下用户会从启动屏直接掉进一段白屏。
 *
 * 用 finally 而不是 then：守卫抛错会跳登录页，那也算启动结束，启动屏同样必须撤掉，
 * 否则用户被一块盖住整页的遮罩挡着，什么都点不了。
 */
function hideBootScreen() {
  const boot = document.getElementById('osr-boot')
  if (!boot) return
  boot.classList.add('is-done')
  // 等淡出放完再摘节点。transitionend 在后台标签页可能迟迟不触发，再加一道定时器兜底，
  // 谁先到算谁（remove() 重复调用是 no-op）。
  boot.addEventListener('transitionend', () => boot.remove(), { once: true })
  setTimeout(() => boot.remove(), 1000)
}

router.isReady().finally(hideBootScreen)
