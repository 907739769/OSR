<template>
  <v-chip v-if="focusId" closable class="mb-3" @click:close="$emit('clearFocus')">正在查看记录 #{{ focusId }} · 显示全部</v-chip>
  <v-alert v-if="failed" type="warning" variant="tonal" density="compact" class="mb-3" role="alert">
    {{ lastLoadedAt ? '刷新失败，当前显示上次成功加载的数据，可能不符合最新筛选条件。' : '记录加载失败，请重试。' }}
    <div v-if="lastLoadedAt" class="text-caption">上次更新：{{ formatDateTime(lastLoadedAt) }}</div>
    <template #append>
      <v-btn variant="text" size="small" :loading="loading" @click="$emit('retry')">重试</v-btn>
    </template>
  </v-alert>
</template>

<script setup lang="ts">
import { formatDateTime } from '@/composables/dateTime'

defineProps<{
  focusId?: number | null
  failed: boolean
  lastLoadedAt: string | null
  loading: boolean
}>()
defineEmits<{ (e: 'retry'): void; (e: 'clearFocus'): void }>()
</script>
