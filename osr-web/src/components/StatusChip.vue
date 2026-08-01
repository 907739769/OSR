<template>
  <v-chip size="small" variant="tonal" :color="color">
    {{ text }}
  </v-chip>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/**
 * 统一状态徽章。全站所有状态展示都走这个组件，保证同一语义在 PC / 移动端、
 * 在不同页面之间用同一种颜色 —— 改造前「停用」在 strmTask 是 error、在
 * ptAutoAddRule 是 info，同一个意思两种颜色。
 *
 * 用法有两种：
 *   <StatusChip enabled-value="1" :value="item.enabled" />  // 启用/停用 这类二元开关
 *   <StatusChip type="warning" text="下载中" />              // 自定义文案 + 语义色
 */
const props = withDefaults(
  defineProps<{
    /** 自定义文案；不传时按 value/enabledValue 推导为「启用」/「停用」 */
    text?: string
    type?: 'primary' | 'success' | 'warning' | 'error' | 'info' | 'default'
    /** 二元开关的当前值 */
    value?: string | number | boolean | null
    /** 二元开关中代表「开启」的值 */
    enabledValue?: string | number | boolean
    /** 二元开关开启/关闭时的文案 */
    onText?: string
    offText?: string
  }>(),
  {
    text: '',
    type: 'default',
    value: undefined,
    enabledValue: '1',
    onText: '启用',
    offText: '停用'
  }
)

const isBinary = computed(() => props.value !== undefined)
/* eslint-disable-next-line eqeqeq */
const on = computed(() => props.value == props.enabledValue)

const text = computed(() => {
  if (props.text) return props.text
  return on.value ? props.onText : props.offText
})

const color = computed(() => {
  // 二元开关统一配色：开 = success，关 = error（不再有 info / warning 混用）
  if (isBinary.value && props.type === 'default') return on.value ? 'success' : 'error'
  return props.type === 'default' ? undefined : props.type
})
</script>
