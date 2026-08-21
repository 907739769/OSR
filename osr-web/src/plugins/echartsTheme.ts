import { osrCssVar } from '@/composables/useThemeMode'

/**
 * ECharts 统一主题。
 *
 * ## 为什么需要它
 *
 * 改造前，4 个图表页把系列色写成了字面量：`itemStyle: { color: '#B4690E' }`。
 * 那三个值（#B4690E / #3F8F5F / #C0362C）是 **osrLight 的色板**，
 * 而暗色主题的对应色是 #E0A548 / #6BBF8C / #E1685C。于是切到暗色时，
 * `osr-theme-change` 触发重绘、坐标轴和网格线跟着换了，**折线颜色却没有** ——
 * 图表里的琥珀色与页面其余部分的琥珀色差着一整档明度，看起来像图表没刷新。
 * 这不只是审美问题，是个现存 bug，本文件顺带修掉。
 *
 * ## 用法
 *
 * 每次 setOption 前调用，**不要把返回值缓存到模块级变量**：
 * 它读的是当前生效的 --osr-* 令牌，缓存下来就等于把第一次渲染时的主题钉死了。
 *
 * ```ts
 * chart.setOption({ ...chartBase(), series: [lineSeries({ name: '总数', data, tone: 'primary' })] }, true)
 * ```
 */

export type ChartTone = 'primary' | 'success' | 'error' | 'info' | 'warning'

/** 令牌里的颜色形如 `rgb(180,105,14)`（getComputedStyle 会把嵌套 var() 解开）。
    要做渐变就得往里塞 alpha，这里把三个通道拆出来。 */
function channels(cssColor: string): string {
  const m = cssColor.match(/rgba?\(([^)]+)\)/)
  if (!m) return '128,128,128'
  return m[1]
    .split(',')
    .slice(0, 3)
    .map((s) => s.trim())
    .join(',')
}

function tone(name: ChartTone): string {
  return osrCssVar(`--osr-${name}`) || '#888888'
}

function alpha(cssColor: string, a: number): string {
  return `rgba(${channels(cssColor)}, ${a})`
}

/**
 * 折线下方的渐变面积。
 *
 * 用渐变而不是 `areaStyle: { opacity: 0.1 }` 的纯色块：多条线叠在一起时，
 * 纯色半透明块会互相染色成一团灰绿灰红，分不出哪条是哪条；
 * 上浓下透的渐变在交叠处仍能看出各自的走向。
 */
function areaGradient(color: string) {
  return {
    type: 'linear',
    x: 0,
    y: 0,
    x2: 0,
    y2: 1,
    colorStops: [
      { offset: 0, color: alpha(color, 0.26) },
      { offset: 1, color: alpha(color, 0.01) }
    ]
  }
}

/**
 * 一条折线系列。
 *
 * `shadowBlur` 给折线本身加辉光 —— 暗色下这是让细线从深底上「浮起来」的关键，
 * 浅色下几乎看不出来，不必分主题处理。
 */
export function lineSeries(opts: {
  name: string
  data: (number | string)[]
  tone: ChartTone
  smooth?: boolean
  area?: boolean
}) {
  const color = tone(opts.tone)
  return {
    name: opts.name,
    type: 'line' as const,
    smooth: opts.smooth ?? true,
    symbol: 'circle',
    symbolSize: 6,
    // 平时不画点，只在 hover 时显示 —— 7 个点还好，30 天的趋势图上
    // 90 个实心点会把折线本身淹掉
    showSymbol: false,
    data: opts.data,
    itemStyle: { color },
    lineStyle: {
      width: 2,
      color,
      shadowBlur: 12,
      shadowColor: alpha(color, 0.45)
    },
    areaStyle: opts.area === false ? undefined : { color: areaGradient(color) },
    emphasis: {
      focus: 'series' as const,
      itemStyle: { borderWidth: 3, borderColor: alpha(color, 0.35) }
    }
  }
}

/** 柱状系列：圆角柱 + 顶部提亮的渐变 */
export function barSeries(opts: {
  name: string
  data: (number | string)[]
  tone: ChartTone
  stack?: string
}) {
  const color = tone(opts.tone)
  return {
    name: opts.name,
    type: 'bar' as const,
    stack: opts.stack,
    barMaxWidth: 28,
    data: opts.data,
    itemStyle: {
      color: {
        type: 'linear',
        x: 0,
        y: 0,
        x2: 0,
        y2: 1,
        colorStops: [
          { offset: 0, color: alpha(color, 1) },
          { offset: 1, color: alpha(color, 0.55) }
        ]
      },
      // 堆叠柱不做圆角：每一段都圆的话，段与段之间会露出底色的缝
      borderRadius: opts.stack ? 0 : [4, 4, 0, 0]
    }
  }
}

/**
 * 图表的公共外观：坐标轴、网格、图例、提示框。
 *
 * 三条与默认样式不同的取向：
 * 1. **去掉纵向网格线，横向网格线改成虚线**。实心网格是 ECharts 默认里最
 *    「表格化」的部分，横竖都画等于给数据蒙了一层格子布。
 * 2. **提示框跟随全站的玻璃表面**（半透明 + 模糊 + 亮环），而不是默认的白盒子。
 *    默认那个盒子在暗色主题下是纯白的，扎眼且与整页脱节。
 * 3. **坐标轴刻度线全部去掉**，只留标签。刻度线在密集的日期轴上纯属噪音。
 */
export function chartBase() {
  const axis = osrCssVar('--osr-text-secondary') || '#64748b'
  const split = osrCssVar('--osr-border-base') || '#e2e8f0'
  const surface = osrCssVar('--osr-surface') || '#fff'
  const textPrimary = osrCssVar('--osr-text-primary') || '#0f172a'

  return {
    textStyle: {
      // 图表里全是数字，跟着全站的等宽字体走，与卡片上的统计值对齐
      fontFamily: 'JetBrains Mono Variable, ui-monospace, SFMono-Regular, Menlo, Consolas, monospace'
    },
    tooltip: {
      trigger: 'axis' as const,
      backgroundColor: alpha(surface, 0.82),
      borderColor: alpha(textPrimary, 0.1),
      borderWidth: 1,
      padding: [8, 12],
      textStyle: { color: textPrimary, fontSize: 12 },
      extraCssText:
        'backdrop-filter: blur(12px) saturate(1.4); -webkit-backdrop-filter: blur(12px) saturate(1.4); border-radius: 10px; box-shadow: 0 8px 28px -10px rgba(0,0,0,.35);',
      axisPointer: {
        type: 'line' as const,
        lineStyle: { color: alpha(textPrimary, 0.18), width: 1, type: 'solid' as const }
      }
    },
    legend: {
      top: 4,
      itemWidth: 10,
      itemHeight: 10,
      itemGap: 16,
      icon: 'roundRect',
      textStyle: { fontSize: 12, color: axis }
    },
    grid: { left: 40, right: 16, top: 42, bottom: 24, containLabel: false },
    xAxis: {
      type: 'category' as const,
      boundaryGap: false,
      axisLabel: { fontSize: 11, color: axis, margin: 12 },
      axisLine: { lineStyle: { color: alpha(axis, 0.25) } },
      axisTick: { show: false },
      splitLine: { show: false }
    },
    yAxis: {
      type: 'value' as const,
      minInterval: 1,
      axisLabel: { fontSize: 11, color: axis },
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: { lineStyle: { color: alpha(split, 0.6), type: 'dashed' as const } }
    }
  }
}

/** 空态：与页面的 v-empty-state 用同一档弱化文字色，不要用默认的黑字 */
export function chartEmptyOption(text = '暂无数据') {
  return {
    title: {
      text,
      left: 'center' as const,
      top: 'center' as const,
      textStyle: {
        fontSize: 14,
        fontWeight: 'normal' as const,
        color: osrCssVar('--osr-text-placeholder') || '#94a3b8'
      }
    },
    series: []
  }
}
