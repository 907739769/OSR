<template>
  <v-chip size="small" :color="color" variant="tonal">
    {{ text }}
  </v-chip>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * 统一状态徽章：type 决定主题色，视觉全站一致。
 * 用法：<StatusChip :type="item.status === '0' ? 'error' : 'success'" text="停用" />
 */
const props = withDefaults(
  defineProps<{
    text: string
    type?: 'primary' | 'success' | 'warning' | 'error' | 'info' | 'default'
  }>(),
  { type: 'default' }
)

const color = computed(() => (props.type === 'default' ? undefined : props.type))
</script>
