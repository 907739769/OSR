import { ref, computed } from 'vue'
import { getPtHealthApi } from '@/api/openlist/ptHealth'
import { getPtIndexerListApi } from '@/api/openlist/ptIndexer'
import { getRoutePathForComponent } from '@/router'

export interface TodoItem {
  key: string
  label: string
  hint: string
  count: number
  tone: 'warning' | 'error'
  path: string | null
}

/** 索引器是否处于异常：启用着、且上次轮询结果不是 OK（没轮询过的新索引器 lastStatus 为空，不算异常） */
export function isIndexerAbnormal(ix: { enabled?: string; lastStatus?: string | null }): boolean {
  return ix.enabled === '1' && !!ix.lastStatus && ix.lastStatus !== 'OK'
}

/**
 * 首页「待办提醒」：把散在缺集体检、索引器两个页面里、需要用户动手的事汇到一处。
 *
 * 数据全部来自已有接口，不新增后端能力：体检报告的分档数与索引器列表的轮询状态。
 * 两路各自独立取，一路失败不影响另一路；失败时 `failed` 置位，页面要如实说「没取到」，
 * 而不是当成「没有待办」——后者会让用户以为一切正常。
 */
export function useDashboardTodo() {
  const loading = ref(true)
  const failed = ref(false)
  const overdueMissing = ref(0)
  const blocked = ref(0)
  const overdueInFlight = ref(0)
  const abnormalIndexers = ref(0)

  async function load() {
    // 刷新时保留旧数字，只有首次加载才进入加载态
    const first = loading.value
    if (first) loading.value = true
    const [health, indexers] = await Promise.allSettled([
      getPtHealthApi(false),
      getPtIndexerListApi({ pageNum: 1, pageSize: 100 } as any)
    ])
    let anyFailed = false
    if (health.status === 'fulfilled') {
      const c = health.value?.bucketCounts || {}
      overdueMissing.value = c.OVERDUE_MISSING ?? 0
      overdueInFlight.value = c.OVERDUE_IN_FLIGHT ?? 0
      blocked.value = c.BLOCKED ?? 0
    } else {
      anyFailed = true
      console.error('[Dashboard] 缺集体检加载失败:', health.reason)
    }
    if (indexers.status === 'fulfilled') {
      const rows: any[] = (indexers.value as any)?.records || []
      abnormalIndexers.value = rows.filter(isIndexerAbnormal).length
    } else {
      anyFailed = true
      console.error('[Dashboard] 索引器状态加载失败:', indexers.reason)
    }
    failed.value = anyFailed
    loading.value = false
  }

  const items = computed<TodoItem[]>(() => {
    const healthPath = getRoutePathForComponent('openlist/ptHealth/index')
    const indexerPath = getRoutePathForComponent('openlist/ptIndexer/index')
    return [
      { key: 'overdueMissing', label: '逾期缺集', hint: '已播出仍没搜到资源', count: overdueMissing.value, tone: 'warning' as const, path: healthPath },
      { key: 'overdueInFlight', label: '在途逾期', hint: '已推送但迟迟没入库', count: overdueInFlight.value, tone: 'warning' as const, path: healthPath },
      { key: 'blocked', label: '需人工处理', hint: '连续失败已熔断', count: blocked.value, tone: 'error' as const, path: healthPath },
      { key: 'indexer', label: '索引器异常', hint: '上次轮询没成功', count: abnormalIndexers.value, tone: 'error' as const, path: indexerPath }
    ].filter((i) => i.count > 0)
  })

  return { loading, failed, items, load }
}
