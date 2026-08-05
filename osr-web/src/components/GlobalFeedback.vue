<template>
  <!--
    :key 不能省。v-snackbar 的自动关闭计时器只在 onMounted 和 model-value / timeout 变化时启动
    （见 vuetify VSnackbar 的 startTimeout），而这里 model-value 恒为 true。队头换人时（前一条被
    关掉、后一条顶上）组件实例会被复用，只有文案变了，计时器不会重启，这条提示就永远留在页面上。
    用 id 做 key 强制每条提示都重新挂载一次，各自拿到自己的计时器。
  -->
  <v-snackbar
    v-if="currentSnackbar"
    :key="currentSnackbar.id"
    :model-value="true"
    :color="currentSnackbar.level"
    :timeout="currentSnackbar.timeout"
    location="top"
    @update:model-value="dismiss(currentSnackbar.id)"
  >
    {{ currentSnackbar.text }}
  </v-snackbar>

  <v-dialog v-model="confirmState.visible" max-width="420" persistent>
    <v-card>
      <v-card-title>{{ confirmState.title }}</v-card-title>
      <v-card-text>{{ confirmState.message }}</v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="text" @click="store.rejectConfirm()">{{ confirmState.cancelText }}</v-btn>
        <v-btn :color="confirmState.type" variant="flat" @click="store.resolveConfirm()">
          {{ confirmState.confirmText }}
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>

  <v-overlay :model-value="store.loading" class="d-flex align-center justify-center" persistent>
    <v-progress-circular indeterminate color="primary" size="48" />
    <div v-if="store.loadingText" class="mt-2 text-body-2 text-center">{{ store.loadingText }}</div>
  </v-overlay>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useFeedbackStore } from '@/stores/feedback'

const store = useFeedbackStore()
const confirmState = computed(() => store.confirmState)
const currentSnackbar = computed(() => store.snackbars[0] ?? null)

function dismiss(id: number) {
  store.dismissSnackbar(id)
}
</script>
