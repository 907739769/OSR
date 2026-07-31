<template>
  <v-snackbar
    v-if="currentSnackbar"
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
