import { nextTick, onMounted, onUnmounted, watch, type ComputedRef, type Ref } from 'vue'
import * as echarts from 'echarts/core'

/**
 * 把一个 ECharts 实例绑到容器上：挂载时 init、option 变化时 setOption、
 * 卸载时 resize 监听与实例一起收掉。
 *
 * 做成一处的理由很具体：统计仪表盘此前四张图各写一遍 init/resize/dispose，
 * 而 `onUnmounted` 里<b>漏掉了其中一张</b>——每进出一次页面泄漏一个 ECharts 实例，
 * 不报错、不告警，只有内存涨。这类"N 份样板里少写一份"的缺陷只能靠收口消掉，
 * 靠 review 是看不出来的。
 *
 * `onClick` 可选：点击图元（折线上的点、扇形、柱子）时回调，参数是 ECharts 的事件对象。
 *
 * 调用方仍需自己 `echarts.use([...])` 注册用到的图表与组件（按需引入，
 * 全量引入会把打包体积拖大一倍）。
 */
export function useEchart(el: Ref<HTMLElement | null>, option: ComputedRef<any>, onClick?: (params: any) => void) {
  let chart: echarts.ECharts | null = null

  const render = () => {
    if (!el.value) return
    if (!chart) {
      chart = echarts.init(el.value)
      // 绑在实例上而不是每次 setOption 后重绑：实例随 dispose 一起释放，不会累积多份监听
      if (onClick) chart.on('click', onClick)
    }
    // notMerge=true：空态 option 与数据 option 的结构完全不同（一个只有 graphic，
    // 一个有 series/axis），合并会把上一次的坐标轴留在空态图上
    chart.setOption(option.value, true)
  }

  const onResize = () => chart?.resize()

  watch(option, () => render())

  onMounted(async () => {
    await nextTick()
    render()
    window.addEventListener('resize', onResize)
  })

  onUnmounted(() => {
    window.removeEventListener('resize', onResize)
    chart?.dispose()
    chart = null
  })

  return { resize: onResize }
}
