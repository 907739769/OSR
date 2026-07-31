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
import { useAppStore } from '@/stores/app'
import DesktopLayout from '@/layouts/DesktopLayout.vue'
import MobileLayout from '@/layouts/MobileLayout.vue'
import AppUpdatePrompt from '@/components/AppUpdatePrompt.vue'
import GlobalFeedback from '@/components/GlobalFeedback.vue'

const route = useRoute()
const appStore = useAppStore()

const isHiddenRoute = computed(() => {
  return route.meta?.hidden === true
})

const isMobileDevice = computed(() => appStore.device === 'mobile')

const checkDevice = () => {
  const mobile = window.innerWidth < 768
  appStore.toggleDevice(mobile ? 'mobile' : 'desktop')
  if (mobile) {
    appStore.closeSidebar()
  }
}

onMounted(() => {
  checkDevice()
  window.addEventListener('resize', checkDevice)
})

onUnmounted(() => {
  window.removeEventListener('resize', checkDevice)
})
</script>
