<template>
  <svg class="mini-trend" viewBox="0 0 80 24" preserveAspectRatio="none" aria-hidden="true">
    <defs>
      <linearGradient :id="gradId" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0%" :stop-color="`var(--osr-${tone})`" stop-opacity="0.35" />
        <stop offset="100%" :stop-color="`var(--osr-${tone})`" stop-opacity="0.02" />
      </linearGradient>
    </defs>
    <polygon v-if="areaPoints" class="mini-trend-area" :points="areaPoints" :fill="`url(#${gradId})`" />
    <polyline
      v-if="linePoints"
      class="mini-trend-line"
      :points="linePoints"
      fill="none"
      :stroke="`var(--osr-${tone})`"
      stroke-width="1.6"
      stroke-linejoin="round"
      stroke-linecap="round"
      pathLength="1"
    />
    <!-- 末点圆点：走势图缺一个「现在在哪」的锚点。只画最后一个点，
         画满 7 个会把 80×24 这么小的图挤成一串珠子 -->
    <circle
      v-if="lastPoint"
      class="mini-trend-dot"
      :cx="lastPoint.x"
      :cy="lastPoint.y"
      r="1.8"
      :fill="`var(--osr-${tone})`"
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

/** 归一化后的坐标点，线/面/末点共用一次计算 */
const coords = computed(() => {
  const pts = props.points
  if (!pts.length) return []
  const max = Math.max(...pts, 1)
  const step = pts.length > 1 ? W / (pts.length - 1) : 0
  return pts.map((v, i) => ({
    x: Number((i * step).toFixed(1)),
    y: Number((H - (v / max) * (H - 5) - 2).toFixed(1))
  }))
})

const linePoints = computed(() => coords.value.map((p) => `${p.x},${p.y}`).join(' '))

const areaPoints = computed(() => {
  if (!linePoints.value) return ''
  return `${linePoints.value} ${W},${H} 0,${H}`
})

const lastPoint = computed(() => coords.value[coords.value.length - 1] ?? null)
</script>

<style scoped lang="scss">
.mini-trend {
  display: block;
  width: 100%;
  height: 24px;
  overflow: visible; /* 末点的半径会略微出界，不放开会被裁掉半个 */
}

/* 折线描边动画。
   靠 `pathLength="1"` 把路径长度归一化成 1，dasharray/dashoffset 就能直接写 1，
   **不需要在 JS 里 getTotalLength() 量一遍** —— 量的话得等元素挂载、
   还要在数据变化时重新量，而这个组件每张统计卡上都有一份。 */
.mini-trend-line {
  stroke-dasharray: 1;
  animation: osr-draw-line var(--osr-dur-4) var(--osr-ease-out) both;
  --osr-dash: 1;
}

/* 面积与末点跟在描边之后淡入：先有线、再有面，顺序反了会先看到一块无缘无故的色块 */
.mini-trend-area {
  animation: osr-fade-in var(--osr-dur-3) var(--osr-ease-out) both;
  animation-delay: var(--osr-dur-2);
}

.mini-trend-dot {
  animation: osr-scale-in var(--osr-dur-2) var(--osr-ease-spring) both;
  animation-delay: var(--osr-dur-3);
  transform-box: fill-box; /* 不写的话 scale 以整个 SVG 的原点为中心，圆点会从左上角飞过来 */
  transform-origin: center;
}
</style>
