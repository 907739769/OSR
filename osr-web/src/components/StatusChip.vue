<template>
  <v-chip size="small" variant="tonal" :color="color">
    <!-- 进行态的呼吸点。样式在 styles/surface.scss（.osr-pulse-dot），
         用 currentColor 取 chip 自己的语义色，不需要再传一次颜色 -->
    <span v-if="pulse" class="osr-pulse-dot" aria-hidden="true" />
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
 * 用法有三种：
 *   <StatusChip enabled-value="1" :value="item.enabled" />  // 启用/停用 这类二元开关
 *   <StatusChip type="warning" text="下载中" />              // 自定义文案 + 语义色
 *   <StatusChip type="warning" text="下载中" pulse />        // 进行态，带呼吸点
 *
 * `pulse` 只给**真正还在推进**的状态用（下载中 / 处理中 / 上传中），
 * 不要给「已推送」「保种中」这类稳态挂上去。改造前进行态与终态在形态上完全一样，
 * 一屏几十条记录时扫一眼分不出哪些还在跑 —— 这是个可用性问题，不只是装饰：
 * 这个系统里的任务动辄跑几十分钟，「还在跑」和「卡住了」是用户最需要区分的两件事。
 * 挂满了就等于没有强调，所以它是个显式开关而不是按文案自动推断。
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
    /** 进行态：文案前加一个呼吸圆点 */
    pulse?: boolean
  }>(),
  {
    text: '',
    type: 'default',
    value: undefined,
    enabledValue: '1',
    onText: '启用',
    offText: '停用',
    pulse: false
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
