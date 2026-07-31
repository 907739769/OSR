import { ref, onMounted, onUnmounted } from 'vue'
import { useRegisterSW } from 'virtual:pwa-register/vue'

/** 两次主动检查之间的最小间隔：手机端切前后台很频繁，不节流会一直发请求 */
const MIN_CHECK_INTERVAL = 10 * 60 * 1000

/** 桌面端常开标签页的兜底轮询间隔；手机端后台会被挂起，指望不上 */
const POLL_INTERVAL = 60 * 60 * 1000

/**
 * PWA 版本更新检测。
 *
 * 检查时机分三层：
 *   1. 冷启动——浏览器注册 SW 时自带
 *   2. visibilitychange——手机端主力，覆盖「后台放几小时再切回来」的温启动
 *   3. 定时轮询——只对桌面端常开标签页有意义
 */
export function useAppUpdate() {
  /** 新版本已就绪，等待用户确认刷新 */
  const needRefresh = ref(false)
  /** 用户点了刷新后置位，避免按钮被重复点击 */
  const updating = ref(false)

  let registration: ServiceWorkerRegistration | undefined
  let lastCheckAt = Date.now()
  let pollTimer: number | undefined

  const { updateServiceWorker } = useRegisterSW({
    onRegistered(reg) {
      registration = reg
    },
    onRegisterError(error) {
      console.error('[pwa] Service Worker 注册失败', error)
    },
    onNeedRefresh() {
      needRefresh.value = true
    },
  })

  /** 主动询问服务端有没有新版本；节流，除非 force */
  async function checkForUpdate(force = false) {
    if (!registration || needRefresh.value) return

    const now = Date.now()
    if (!force && now - lastCheckAt < MIN_CHECK_INTERVAL) return
    lastCheckAt = now

    try {
      await registration.update()
    } catch (error) {
      // 离线时必然失败，属于预期内，不打扰用户
      console.warn('[pwa] 检查更新失败', error)
    }
  }

  /** 激活新 SW 并重载页面 */
  async function applyUpdate() {
    if (updating.value) return
    updating.value = true
    try {
      await updateServiceWorker(true)
    } catch (error) {
      console.error('[pwa] 应用更新失败', error)
      updating.value = false
    }
  }

  /** 用户选择稍后：收起提示，下次检查到新版本时会再次弹出 */
  function dismiss() {
    needRefresh.value = false
  }

  function handleVisibilityChange() {
    if (document.visibilityState === 'visible') {
      checkForUpdate()
    }
  }

  onMounted(() => {
    document.addEventListener('visibilitychange', handleVisibilityChange)
    pollTimer = window.setInterval(() => checkForUpdate(), POLL_INTERVAL)
  })

  onUnmounted(() => {
    document.removeEventListener('visibilitychange', handleVisibilityChange)
    if (pollTimer) window.clearInterval(pollTimer)
  })

  return { needRefresh, updating, applyUpdate, dismiss, checkForUpdate }
}
