<template>
  <component :is="isMobile ? MobileDashboard : DesktopDashboard" />
</template>

<script setup lang="ts">
import { computed, defineAsyncComponent } from 'vue'
import { useAppStore } from '@/stores/app'

// 首页入口：按设备分流。
// 异步引入让 PC 版的 echarts 不会被打进移动端首屏。
const DesktopDashboard = defineAsyncComponent(() => import('./desktop.vue'))
const MobileDashboard = defineAsyncComponent(() => import('@/views-mobile/dashboard/index.vue'))

const appStore = useAppStore()
const isMobile = computed(() => appStore.device === 'mobile')
</script>
