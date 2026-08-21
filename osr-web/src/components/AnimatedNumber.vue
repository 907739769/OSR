<template><span class="animated-number">{{ display }}</span></template>

<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'

/**
 * 统计数字的滚动动画。
 *
 * ## 为什么值得做
 *
 * 仪表盘上的数字原先是「请求回来 → 直接出现」。数字直接 pop 出来，用户没有任何
 * 线索知道这个值是刚算出来的还是一直就在那；滚动一下同时解决两件事：给出「数据
 * 到达」的反馈，并且让视线自然落到数值上。这是仪表盘类界面性价比最高的一处动效。
 *
 * ## 为什么要自己解析而不是只接受 number
 *
 * 调用方手里的值形态不统一：计数是 `128`，成功率是 `'85%'`，没有数据时是 `'--'`，
 * 平均耗时是 `'12 分钟'`。把「怎么把它变成能动的数」这件事推给每个调用方，
 * 结果一定是有的页面动、有的页面不动。这里统一处理：能解析出数字的就滚，
 * 解析不出来的（'--'）原样渲染，调用方永远只管把值传进来。
 */
const props = withDefaults(
  defineProps<{
    value: number | string | null | undefined
    /** 滚动时长。默认与 --osr-dur-4 一致 */
    duration?: number
  }>(),
  { duration: 520 }
)

/** `'85%'` → { prefix: '', num: 85, suffix: '%' }；`'--'` → null */
function parse(raw: number | string | null | undefined) {
  if (raw === null || raw === undefined) return null
  if (typeof raw === 'number') {
    return Number.isFinite(raw) ? { prefix: '', num: raw, suffix: '', decimals: 0 } : null
  }
  const m = String(raw).match(/^(\D*?)(-?\d+(?:\.\d+)?)(.*)$/)
  if (!m) return null
  const numText = m[2]
  return {
    prefix: m[1],
    num: Number(numText),
    suffix: m[3],
    // 记住原值的小数位，回放时按同样的精度格式化 —— 不记的话
    // 「99.5%」会在动画结束时定格成「100%」，一个本来精确的数字被动画改掉了
    decimals: numText.includes('.') ? numText.split('.')[1].length : 0
  }
}

const current = ref(0)
const parsed = computed(() => parse(props.value))

const display = computed(() => {
  const p = parsed.value
  if (!p) return props.value ?? ''
  return `${p.prefix}${current.value.toFixed(p.decimals)}${p.suffix}`
})

let raf = 0

function stop() {
  if (raf) cancelAnimationFrame(raf)
  raf = 0
}

/** expo-out，与 --osr-ease-out 是同一条曲线的数学形式 */
const easeOut = (t: number) => 1 - Math.pow(2, -10 * t)

watch(
  parsed,
  (p, prev) => {
    stop()
    if (!p) return

    const reduceMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
    // 首次赋值也要滚：那正是「数据刚到」的时刻，是这个动画存在的理由。
    // 但从 0 滚到 0 没有意义，直接落位
    if (reduceMotion || p.num === current.value) {
      current.value = p.num
      return
    }

    const from = prev ? current.value : 0
    const to = p.num
    const start = performance.now()
    const step = (now: number) => {
      const t = Math.min(1, (now - start) / props.duration)
      current.value = from + (to - from) * easeOut(t)
      if (t < 1) {
        raf = requestAnimationFrame(step)
      } else {
        // 显式落到目标值：缓动函数在 t=1 处只是无限逼近，
        // 不收这一下的话 128 会定格成 127.99…，按 0 位小数四舍五入才勉强对
        current.value = to
        raf = 0
      }
    }
    raf = requestAnimationFrame(step)
  },
  { immediate: true }
)

onUnmounted(stop)
</script>

<style scoped lang="scss">
.animated-number {
  /* 等宽数字。滚动过程中每一帧的位数都在变（7 → 42 → 128），
     比例数字会让整块文字左右横跳，动画反而成了干扰 */
  font-variant-numeric: tabular-nums;
}
</style>
