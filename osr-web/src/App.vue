<template>
  <v-app>
    <div v-if="isHiddenRoute" class="h-100">
      <router-view />
    </div>
    <DesktopLayout v-else-if="!isMobileDevice">
      <router-view />
    </DesktopLayout>
    <MobileLayout v-else>
      <router-view />
    </MobileLayout>

    <!-- PWA 版本更新提示：非模态，不打断当前操作 -->
    <AppUpdatePrompt />

    <GlobalFeedback />
  </v-app>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore, MOBILE_MEDIA_QUERY } from '@/stores/app'
import { useThemeMode } from '@/composables/useThemeMode'
import DesktopLayout from '@/layouts/DesktopLayout.vue'
import MobileLayout from '@/layouts/MobileLayout.vue'
import AppUpdatePrompt from '@/components/AppUpdatePrompt.vue'
import GlobalFeedback from '@/components/GlobalFeedback.vue'

// 初始化主题模式（浅色/深色/跟随系统），写入 <html data-theme> 与 Vuetify 主题
useThemeMode()

const route = useRoute()
const appStore = useAppStore()

const isHiddenRoute = computed(() => {
  return route.meta?.hidden === true
})

const isMobileDevice = computed(() => appStore.device === 'mobile')

// 判定条件交给 matchMedia（见 MOBILE_MEDIA_QUERY：除了窄屏还要认手机横屏）。
// change 是主触发；resize 作为兜底一起听——iOS 14 之前的 Safari 只有 addListener，
// 没有 addEventListener('change')，那些设备上只挂 change 等于旋转屏幕不换布局，
// 而这是个主要给手机用的 PWA。兜底不贵：applyDevice 只读一个布尔量再写回 store，
// 值没变 Vue 不会重渲染，resize 连打几十次也就是几十次赋值。
const mediaQuery = window.matchMedia(MOBILE_MEDIA_QUERY)

const applyDevice = () => {
  const mobile = mediaQuery.matches
  appStore.toggleDevice(mobile ? 'mobile' : 'desktop')
  if (mobile) {
    appStore.closeSidebar()
  }
}

onMounted(() => {
  applyDevice()
  mediaQuery.addEventListener('change', applyDevice)
  window.addEventListener('resize', applyDevice)
})

onUnmounted(() => {
  mediaQuery.removeEventListener('change', applyDevice)
  window.removeEventListener('resize', applyDevice)
})
</script>
