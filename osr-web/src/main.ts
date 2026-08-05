import { createApp } from 'vue'
import { createPinia } from 'pinia'
import vuetify from './plugins/vuetify'
import App from './App.vue'
import router from './router'
import './styles/index.scss'

const app = createApp(App)

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
