import { computed, onMounted, onUnmounted, ref, watch, type Ref } from 'vue'
import { barSeries, chartBase, chartEmptyOption, lineSeries } from '@/plugins/echartsTheme'
import { formatSize } from '@/composables/sizeUnits'
import { getPtSeedingOverviewApi, type SeedingDayPoint, type SeedingOverview } from '@/api/openlist/ptStats'

const GB = 1024 * 1024 * 1024

/**
 * 保种与上传量看板（统计仪表盘里的一块，PC 与移动端共用）。
 * <p>
 * **只有管理员看得到**：后端对非管理员返回 403，这里据此把整块藏起来（`visible`），
 * 不弹错误——普通成员打开统计页看到一个「无权访问」的报错，比看不到这块更让人困惑。
 * 其余失败（下载器全连不上、网络错误）如实显示「加载失败」。
 */
export function usePtSeeding(days: Ref<number>) {
  const data = ref<SeedingOverview | null>(null)
  const loading = ref(false)
  const failed = ref(false)
  const visible = ref(true)
  const themeTick = ref(0)

  const load = async () => {
    loading.value = true
    try {
      data.value = await getPtSeedingOverviewApi(days.value)
      failed.value = false
    } catch (e: any) {
      // 拦截器 reject 的是 Error(msg)，403 的 msg 就是后端那句「仅管理员可用」
      if (String(e?.message || '').includes('仅管理员')) {
        visible.value = false
      } else {
        console.error('[PtSeeding] 加载失败:', e)
        failed.value = true
      }
    } finally {
      loading.value = false
    }
  }

  watch(days, () => load())

  const trendOption = computed(() => {
    void themeTick.value
    return buildSeedingTrendOption(data.value?.trend ?? null, failed.value)
  })

  const totals = computed(() => {
    const list = data.value?.downloaders ?? []
    return {
      seedingCount: list.reduce((n, d) => n + d.seedingCount, 0),
      seedingSize: list.reduce((n, d) => n + d.seedingSize, 0),
      uploadedInRange: (data.value?.trend ?? []).reduce((n, p) => n + (p.uploaded ?? 0), 0)
    }
  })

  const onThemeChange = () => (themeTick.value += 1)
  onMounted(() => {
    document.addEventListener('osr-theme-change', onThemeChange)
    load()
  })
  onUnmounted(() => document.removeEventListener('osr-theme-change', onThemeChange))

  return { data, loading, failed, visible, totals, trendOption, load }
}

/** 每日上传量（柱，GB）+ 保种体积（线，GB，第二根 Y 轴）。算不出的那天柱子留空而不是画 0 */
export function buildSeedingTrendOption(points: SeedingDayPoint[] | null, failed = false) {
  if (failed) return chartEmptyOption('加载失败')
  if (!points || points.length === 0 || points.every((p) => p.uploaded == null && !p.seedingSize)) {
    return chartEmptyOption('暂无快照（每小时记录一次，明天起可看到每日上传量）')
  }
  const base = chartBase()
  const toGb = (v: number) => Math.round((v / GB) * 100) / 100
  return {
    ...base,
    legend: { ...base.legend, data: ['每日上传', '保种体积'], top: 0 },
    grid: { ...base.grid, top: 40, bottom: 30, right: 56 },
    tooltip: {
      ...base.tooltip,
      trigger: 'axis',
      valueFormatter: (v: number | string) => (typeof v === 'number' ? formatSize(v * GB) : '算不出')
    },
    xAxis: { ...base.xAxis, data: points.map((p) => p.date.slice(5)) },
    yAxis: [
      { ...base.yAxis, name: 'GB' },
      { ...base.yAxis, name: 'GB', splitLine: { show: false } }
    ],
    series: [
      barSeries({ name: '每日上传', data: points.map((p) => (p.uploaded == null ? '-' : toGb(p.uploaded))), tone: 'success' }),
      { ...lineSeries({ name: '保种体积', data: points.map((p) => toGb(p.seedingSize)), tone: 'primary', area: false }), yAxisIndex: 1 }
    ]
  }
}
