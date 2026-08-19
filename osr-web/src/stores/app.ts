import { defineStore } from 'pinia'
import { ref } from 'vue'

/**
 * 移动端的判定条件。
 *
 * 第一条是常规的窄屏；第二条专门管**手机横屏**——iPhone 14 Pro Max 横过来是 926×428，
 * 只看宽度会被判成 desktop，于是 220px 的侧边栏加一张宽表格挤在 428px 高的屏幕里。
 * `pointer: coarse` 把这条限制在触摸设备上，笔记本缩窗口到 900px 仍然是 PC 布局。
 */
export const MOBILE_MEDIA_QUERY = '(max-width: 767.98px), (max-width: 926px) and (pointer: coarse)'

export const useAppStore = defineStore('app', () => {
  const sidebarOpened = ref<boolean>(true)
  const device = ref<'desktop' | 'mobile'>(
    window.matchMedia(MOBILE_MEDIA_QUERY).matches ? 'mobile' : 'desktop'
  )

  const toggleSidebar = () => {
    sidebarOpened.value = !sidebarOpened.value
  }

  const closeSidebar = () => {
    sidebarOpened.value = false
  }

  const toggleDevice = (value: 'desktop' | 'mobile') => {
    device.value = value
  }

  return { sidebarOpened, device, toggleSidebar, closeSidebar, toggleDevice }
})
