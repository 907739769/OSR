import { ref, computed } from 'vue'
import { getPtHealthApi } from '@/api/openlist/ptHealth'
import { getPtIndexerListApi } from '@/api/openlist/ptIndexer'
import { getTodoSignalsApi, type TodoProblem } from '@/api/openlist/dashboard'
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

/** 待办提示里列出的名字：两个以内全列，多了只列前两个 */
export function describeProblems(problems: TodoProblem[], suffix: string): string {
  const names = problems.slice(0, 2).map((p) => p.name).join('、')
  return problems.length > 2 ? `${names} 等 ${problems.length} 台${suffix}` : `${names}${suffix}`
}

/**
 * 首页「待办提醒」：把散在各页面里、需要用户动手的事汇到一处。
 *
 * 三路数据：缺集体检的分档数、索引器列表的轮询状态、后端 `todo-signals`（下载器离线、
 * 媒体服务器连不上、还没着落的失败下载）。各自独立取，一路失败不影响另一路；失败时 `failed` 置位，
 * 页面要如实说「没取到」，而不是当成「没有待办」——后者会让用户以为一切正常。
 * `todo-signals` 里某个字段为 null 同样算那一路没取到。
 */
export function useDashboardTodo() {
  const loading = ref(true)
  const failed = ref(false)
  const overdueMissing = ref(0)
  const blocked = ref(0)
  const overdueInFlight = ref(0)
  const abnormalIndexers = ref(0)
  const offlineDownloaders = ref<TodoProblem[]>([])
  const unhealthyMediaServers = ref<TodoProblem[]>([])
  const unresolvedFailedDownloads = ref(0)

  async function load() {
    // 刷新时保留旧数字，只有首次加载才进入加载态
    const first = loading.value
    if (first) loading.value = true
    const [health, indexers, signals] = await Promise.allSettled([
      getPtHealthApi(false),
      getPtIndexerListApi({ pageNum: 1, pageSize: 100 } as any),
      getTodoSignalsApi()
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
    if (signals.status === 'fulfilled' && signals.value) {
      const s = signals.value
      offlineDownloaders.value = s.offlineDownloaders ?? []
      unhealthyMediaServers.value = s.unhealthyMediaServers ?? []
      unresolvedFailedDownloads.value = s.unresolvedFailedDownloads ?? 0
      if (s.offlineDownloaders == null || s.unhealthyMediaServers == null || s.unresolvedFailedDownloads == null) {
        anyFailed = true
      }
    } else {
      anyFailed = true
      console.error('[Dashboard] 待办信号加载失败:', signals.status === 'rejected' ? signals.reason : '空响应')
    }
    failed.value = anyFailed
    loading.value = false
  }

  const items = computed<TodoItem[]>(() => {
    const healthPath = getRoutePathForComponent('openlist/ptHealth/index')
    const indexerPath = getRoutePathForComponent('openlist/ptIndexer/index')
    const downloaderPath = getRoutePathForComponent('openlist/ptDownloader/index')
    const mediaServerPath = getRoutePathForComponent('openlist/ptMediaServer/index')
    const recordPath = getRoutePathForComponent('openlist/ptDownloadRecord/index')
    return [
      // 下载器离线排最前：它一掉，订阅命中了也推不下去、在途的也追踪不到，其余待办多半由它引起
      { key: 'downloader', label: '下载器离线', hint: describeProblems(offlineDownloaders.value, ' 连不上'), count: offlineDownloaders.value.length, tone: 'error' as const, path: downloaderPath },
      { key: 'mediaServer', label: '媒体服务器连不上', hint: describeProblems(unhealthyMediaServers.value, ' 最近一次访问失败，入库对账会停'), count: unhealthyMediaServers.value.length, tone: 'error' as const, path: mediaServerPath },
      { key: 'overdueMissing', label: '逾期缺集', hint: '已播出仍没搜到资源', count: overdueMissing.value, tone: 'warning' as const, path: healthPath },
      { key: 'overdueInFlight', label: '在途逾期', hint: '已推送但迟迟没入库', count: overdueInFlight.value, tone: 'warning' as const, path: healthPath },
      { key: 'blocked', label: '需人工处理', hint: '连续失败已熔断', count: blocked.value, tone: 'error' as const, path: healthPath },
      { key: 'indexer', label: '索引器异常', hint: '上次轮询没成功', count: abnormalIndexers.value, tone: 'error' as const, path: indexerPath },
      { key: 'failedDownload', label: '下载失败待处理', hint: '失败后还没被重新下载成功', count: unresolvedFailedDownloads.value, tone: 'warning' as const, path: recordPath }
    ].filter((i) => i.count > 0)
  })

  return { loading, failed, items, load }
}
