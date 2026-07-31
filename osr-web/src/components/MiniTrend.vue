<template>
  <svg class="mini-trend" viewBox="0 0 80 24" preserveAspectRatio="none" aria-hidden="true">
    <defs>
      <linearGradient :id="gradId" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" :stop-color="`var(--osr-${tone})`" stop-opacity="0.35" />
        <stop offset="100%" :stop-color="`var(--osr-${tone})`" stop-opacity="0.02" />
      </linearGradient>
    </defs>
    <polygon v-if="areaPoints" :points="areaPoints" :fill="`url(#${gradId})`" />
    <polyline
      v-if="linePoints"
      :points="linePoints"
      fill="none"
      :stroke="`var(--osr-${tone})`"
      stroke-width="1.6"
      stroke-linejoin="round"
      stroke-linecap="round"
    />
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue'

/** 迷你趋势线（SVG sparkline）：统计卡片右下角展示近 7 天走势，颜色随主题变量自动适配 */
const props = withDefaults(
  defineProps<{
    points: number[]
    tone?: 'primary' | 'success' | 'warning' | 'info'
  }>(),
  { tone: 'primary' }
)

const W = 80
const H = 24

// 渐变 id 需唯一，多个 sparkline 同页渲染不串色
const gradId = `mini-trend-${Math.random().toString(36).slice(2, 8)}`

const linePoints = computed(() => {
  const pts = props.points
  if (!pts.length) return ''
  const max = Math.max(...pts, 1)
  const step = pts.length > 1 ? (W / (pts.length - 1)) : 0
  return pts
    .map((v, i) => `${(i * step).toFixed(1)},${(H - (v / max) * (H - 5) - 2).toFixed(1)}`)
    .join(' ')
})

const areaPoints = computed(() => {
  if (!linePoints.value) return ''
  return `${linePoints.value} ${W},${H} 0,${H}`
})
</script>

<style scoped lang="scss">
.mini-trend {
  display: block;
  width: 100%;
  height: 24px;
}
</style>
