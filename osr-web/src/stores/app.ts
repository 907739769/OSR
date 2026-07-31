import { defineStore } from 'pinia'
import { ref } from 'vue'

export const useAppStore = defineStore('app', () => {
  const sidebarOpened = ref<boolean>(true)
  const device = ref<'desktop' | 'mobile'>(window.innerWidth < 768 ? 'mobile' : 'desktop')

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
