import { computed, ref, watch, type ComputedRef } from 'vue'
import { useTheme } from 'vuetify'

export type ThemeMode = 'light' | 'dark' | 'system'

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
  setMode: (m: ThemeMode) => void
  toggle: () => void
} {
  if (!inited) {
    inited = true
    vuetifyTheme = useTheme()
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    systemDark.value = media.matches
    media.addEventListener('change', onMediaChange)
    watch([mode, systemDark], apply, { immediate: true })
  }

  const setMode = (m: ThemeMode) => {
    mode.value = m
  }

  const toggle = () => {
    mode.value = mode.value === 'dark' ? 'light' : 'dark'
  }

  return { mode, isDark, setMode, toggle }
}

/** 读取当前生效的 --osr-* 设计令牌值（随 data-theme 切换变化），供 ECharts 等 canvas 场景使用 */
export function osrCssVar(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim()
}
