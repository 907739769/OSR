import { useRouter } from 'vue-router'
import type { LocationQueryRaw, RouteLocationRaw } from 'vue-router'
import type { Ref } from 'vue'
import { getRoutePathForComponent } from '@/router'
import { failReasonFilter, trendPointFilter, type PtRecordFilter, type PtStatCard } from './usePtStats'

/**
 * 跳到「PT 下载记录」并带上筛选，键名与下载记录页从路由读取的一致（usePtDownloadRecord#applyRouteFilters）。
 * 路径按组件反查，不写死：菜单 path 历史上有 /openlist 与 /openliststrm 两种前缀。
 */
export function downloadRecordLocation(filter: PtRecordFilter & { subId?: number, subTitle?: string }): RouteLocationRaw | null {
  const path = getRoutePathForComponent('openlist/ptDownloadRecord/index')
  if (!path) return null
  const query: LocationQueryRaw = {}
  for (const [key, value] of Object.entries(filter)) {
    if (value === undefined || value === null || value === '' || value === false) continue
    query[key] = value === true ? '1' : String(value)
  }
  return { path, query }
}

/** 订阅页并定位到某条订阅 */
export function subscriptionLocation(subId?: number): RouteLocationRaw | null {
  const path = getRoutePathForComponent('openlist/ptSubscription/index')
  if (!path) return null
  return subId ? { path, query: { id: subId } } : { path }
}

/**
 * PT 统计仪表盘的下钻：统计卡、趋势图、失败原因饼图、Top 订阅都能点进对应的明细。
 *
 * 单独成文件而不是写进 usePtStats：那边是纯函数 + 图表选项，单测直接 import；
 * 这里要引 router，放进去会让那批单测把整个路由表（连带 store、布局）一起加载。
 * PC 与移动端共用这一份，跳转口径（哪张卡带什么筛选）只在 usePtStats 的纯函数里定义一次。
 */
export function usePtStatsNavigation(rangeDays: Ref<number>) {
  const router = useRouter()

  const go = (to: RouteLocationRaw | null) => {
    if (to) router.push(to)
  }

  const openCard = (card: PtStatCard) => {
    if (card.toSubscriptions) go(subscriptionLocation())
    else if (card.filter) go(downloadRecordLocation(card.filter))
  }

  const isCardClickable = (card: PtStatCard) => !!card.toSubscriptions || !!card.filter

  /** 趋势图：点某条线上的某一天 */
  const onTrendClick = (params: any) => {
    const filter = trendPointFilter(params?.seriesName, params?.name)
    if (filter) go(downloadRecordLocation(filter))
  }

  /** 失败原因饼图：点某一块 */
  const onFailReasonClick = (params: any) => {
    const code = params?.data?.code
    if (code) go(downloadRecordLocation(failReasonFilter(code, rangeDays.value)))
  }

  /** Top 订阅：这条订阅的下载记录 */
  const subscriptionRecordsLink = (row: { subId?: number, title?: string }) =>
    row.subId ? downloadRecordLocation({ subId: row.subId, subTitle: row.title }) : null

  return { openCard, isCardClickable, onTrendClick, onFailReasonClick, subscriptionRecordsLink, subscriptionLocation }
}
