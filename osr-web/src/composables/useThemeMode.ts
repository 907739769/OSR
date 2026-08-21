import { computed, nextTick, ref, watch, type ComputedRef } from 'vue'
import { useTheme } from 'vuetify'

export type ThemeMode = 'light' | 'dark' | 'system'

/** 切换来源坐标（视口像素）。有它才做圆形揭示，没有就瞬间切换 */
export interface ThemeOrigin {
  x: number
  y: number
}

const STORAGE_KEY = 'osr-theme'

/* 模块级单例状态：App.vue 初始化一次（注册 watch/media 监听），布局/组件共享 */
const saved = (localStorage.getItem(STORAGE_KEY) as ThemeMode) || 'system'
const mode = ref<ThemeMode>(saved)
const systemDark = ref(false)
const isDark = computed(() =>
  mode.value === 'system' ? systemDark.value : mode.value === 'dark'
)

let inited = false
let vuetifyTheme: ReturnType<typeof useTheme> | null = null

/**
 * 本次切换的来源坐标。setMode 写入、watcher 消费后立刻清空。
 *
 * 挂在模块级而不是当参数传：真正触发换肤的是 [mode, systemDark] 上的 watcher，
 * 而 watcher 的回调签名里没有位置放这个东西。清空是必须的 —— 不清的话，
 * 用户点过一次按钮之后，操作系统自己切换深浅色也会从那个早已不存在的按钮位置扩开。
 */
let pendingOrigin: ThemeOrigin | null = null

function apply() {
  if (!vuetifyTheme) return
  const dark = isDark.value
  if (dark) {
    document.documentElement.setAttribute('data-theme', 'dark')
  } else {
    document.documentElement.removeAttribute('data-theme')
  }
  vuetifyTheme.global.name.value = dark ? 'osrDark' : 'osrLight'
  localStorage.setItem(STORAGE_KEY, mode.value)
  // 通知图表等无法用 CSS 变量的场景重绘
  document.dispatchEvent(new CustomEvent('osr-theme-change'))
}

/**
 * 带圆形揭示的换肤。
 *
 * 走 View Transitions：新主题的那一帧从点击位置扩开、盖住静止的旧帧。
 * 三个前提任意一个不满足就退回 apply()，没有中间态需要兜底：
 *   - 浏览器不支持 startViewTransition（Firefox / 老 Safari）
 *   - 用户开了「减少动效」—— 全屏揭示正是这类偏好最想避免的效果
 *   - 拿不到来源坐标（操作系统主题变化、首次初始化），没有圆心可言
 *
 * **回调必须 await nextTick()**：startViewTransition 在回调返回后立刻抓「新」快照，
 * 而 apply() 里改的是 Vuetify 主题名，那是响应式的、DOM 要等 Vue 下一个 tick 才更新。
 * 不等的话抓到的新快照与旧快照完全相同，表现是圆扩开了、颜色却没变，
 * 等揭示放完才「啪」地跳成新主题 —— 比不做动画更糟。
 */
function applyWithReveal(origin: ThemeOrigin | null) {
  const startViewTransition = (
    document as Document & {
      startViewTransition?: (cb: () => unknown) => {
        ready: Promise<void>
        finished: Promise<void>
      }
    }
  ).startViewTransition

  const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches

  if (!origin || !startViewTransition || reduceMotion) {
    apply()
    return
  }

  const root = document.documentElement
  // 这个类让 motion.scss 关掉默认的页面转场动画（淡出 + 上移）：
  // 两套动画同时作用在同一对快照上时，圆形边缘会跟着一起飘
  root.classList.add('osr-theme-transition')

  const transition = startViewTransition.call(document, async () => {
    apply()
    await nextTick()
  })

  transition.ready
    .then(() => {
      const { x, y } = origin
      // 半径取到最远的那个角，否则揭示放完了角落还留着旧主题
      const radius = Math.hypot(
        Math.max(x, window.innerWidth - x),
        Math.max(y, window.innerHeight - y)
      )
      root.animate(
        {
          clipPath: [`circle(0px at ${x}px ${y}px)`, `circle(${radius}px at ${x}px ${y}px)`]
        },
        {
          duration: 520,
          easing: 'cubic-bezier(0.22, 1, 0.36, 1)',
          pseudoElement: '::view-transition-new(root)'
        }
      )
    })
    .catch(() => {
      /* ready 会在转场被打断时 reject（比如连点两次）—— 主题此时已经切好了，
         少一次动画而已，不需要处理 */
    })

  // finally 而不是 then：转场被打断时也必须把类摘掉，
  // 否则下一次真正的页面导航会失去默认转场动画，而且没有任何报错线索
  transition.finished.finally(() => {
    root.classList.remove('osr-theme-transition')
  })
}

function onMediaChange(e: MediaQueryListEvent) {
  systemDark.value = e.matches
}

/**
 * 主题切换：同步 Vuetify 主题 (osrLight/osrDark) 与 <html data-theme> 上的 --osr-* 令牌，
 * 浅色为默认（不挂 data-theme），深色挂 data-theme="dark"。
 * 必须在组件 setup 内调用（依赖 useTheme 的注入），但副作用只注册一次。
 */
export function useThemeMode(): {
  mode: typeof mode
  isDark: ComputedRef<boolean>
  setMode: (m: ThemeMode, origin?: ThemeOrigin | MouseEvent | KeyboardEvent) => void
  toggle: (origin?: ThemeOrigin | MouseEvent | KeyboardEvent) => void
} {
  if (!inited) {
    inited = true
    vuetifyTheme = useTheme()
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    systemDark.value = media.matches
    media.addEventListener('change', onMediaChange)
    watch(
      [mode, systemDark],
      () => {
        const origin = pendingOrigin
        pendingOrigin = null
        applyWithReveal(origin)
      },
      { immediate: true }
    )
  }

  /**
   * 把事件换算成揭示圆心，省得每个调用方各写一遍。
   *
   * 两种情况要判成「没有圆心」，退回瞬间切换：
   * - KeyboardEvent：本来就没有坐标
   * - 键盘敲回车触发的 click：浏览器照样派发 MouseEvent，但 clientX/Y 全是 0，
   *   直接用会让揭示从屏幕左上角扩开 —— 那个位置与用户的操作毫无关系。
   *   判据用 `detail === 0`（真实点击的 detail 是点击次数，至少为 1），
   *   比判 `x === 0 && y === 0` 准确：后者会把真的点在左上角那一像素上的点击也误伤。
   */
  const toOrigin = (o?: ThemeOrigin | MouseEvent | KeyboardEvent): ThemeOrigin | null => {
    if (!o) return null
    if (o instanceof KeyboardEvent) return null
    if (o instanceof MouseEvent) {
      return o.detail === 0 ? null : { x: o.clientX, y: o.clientY }
    }
    return o
  }

  const setMode = (m: ThemeMode, origin?: ThemeOrigin | MouseEvent | KeyboardEvent) => {
    // 值没变时 watcher 不会触发，pendingOrigin 会一直挂着污染下一次切换
    if (mode.value === m) return
    pendingOrigin = toOrigin(origin)
    mode.value = m
  }

  const toggle = (origin?: ThemeOrigin | MouseEvent | KeyboardEvent) => {
    setMode(mode.value === 'dark' ? 'light' : 'dark', origin)
  }

  return { mode, isDark, setMode, toggle }
}

/** 读取当前生效的 --osr-* 设计令牌值（随 data-theme 切换变化），供 ECharts 等 canvas 场景使用 */
export function osrCssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}
