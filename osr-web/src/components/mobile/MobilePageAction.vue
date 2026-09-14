<template>
  <!-- 悬浮底栏右侧的页面主动作。数据来自 useMobilePageAction，样式在
       styles/mobile-chrome.scss 的 .mobile-page-action（它与 .mobile-tabbar 的几何
       必须逐项对齐，放一起才看得出来）。

       实心主色而不是和底栏一样的玻璃：玻璃圆钮紧挨着玻璃胶囊，读起来像底栏的第六格，
       而它不是导航、是「在这一页做一件事」。 -->
  <Transition name="mobile-page-action">
    <button
      v-if="action"
      type="button"
      class="mobile-page-action"
      :class="{ 'mobile-page-action--compact': compact }"
      :aria-label="action.label"
      :title="action.label"
      :aria-busy="action.loading || undefined"
      :disabled="action.loading"
      @click="action.onClick()"
    >
      <v-progress-circular v-if="action.loading" indeterminate size="22" width="2" />
      <v-icon v-else :icon="action.icon" size="24" />
    </button>
  </Transition>
</template>

<script setup lang="ts">
import type { MobilePageAction } from '@/composables/useMobilePageAction'

defineProps<{
  action: MobilePageAction | null
  compact?: boolean
}>()
</script>
