import { reactive, computed, watch, onActivated, onDeactivated, onBeforeUnmount, type Ref } from 'vue'
import { getPtDownloadLiveApi } from '@/api/openlist/ptDownloadRecord'
import type { PtDownloadLiveView, PtDownloadRecordView } from '@/api/openlist/ptDownloadRecord'

/** 实时速度的刷新间隔。下载追踪 30 秒才落一次库，速度那样刷看着像卡住了 */
export const LIVE_REFRESH_MS = 5000

/**
 * 下载记录页的实时速度：当前页里有已推送 / 下载中的记录时，每几秒问一次下载器。
 *
 * 速度不落库（每秒都在变），所以不走列表接口，也不走 WebSocket——后者由 30 秒一轮的
 * 下载追踪驱动，同样太慢。只在真有人看的时候才问：页面被 keep-alive 切走、标签页不可见、
 * 或当前页已经没有在途记录时都停下，否则一个开着没人看的标签页会一直打下载器。
 *
 * 拿到的进度顺手写回行上：比落库那份新，进度条就不会每 30 秒跳一格。
 */
export function usePtDownloadLive(list: Ref<PtDownloadRecordView[]>) {
  const liveById = reactive(new Map<number, PtDownloadLiveView>())

  const activeIds = computed(() =>
    list.value
      .filter(row => row.state === 'PUSHED' || row.state === 'DOWNLOADING')
      .map(row => row.id)
  )

  let active = true
  let timer: ReturnType<typeof setInterval> | undefined
  /** 请求序号：翻页后晚到的上一页结果不能写到新的一页上 */
  let seq = 0

  const refresh = async () => {
    const ids = activeIds.value
    if (!ids.length) return
    if (typeof document !== 'undefined' && document.hidden) return
    const mine = ++seq
    try {
      const rows = await getPtDownloadLiveApi(ids)
      if (mine !== seq) return
      liveById.clear()
      for (const live of rows ?? []) {
        liveById.set(live.id, live)
        const row = list.value.find(r => r.id === live.id)
        // 只改下载中的：已推送的还没开始算进度，写上去会凭空冒出一根进度条
        if (row && live.found && row.state === 'DOWNLOADING') row.progress = live.progress
      }
    } catch {
      // 接口是 silent 的，下载器离线由首页待办告警；这里只是少显示一轮速度
    }
  }

  const sync = () => {
    const shouldRun = active && activeIds.value.length > 0
    if (shouldRun && !timer) {
      timer = setInterval(refresh, LIVE_REFRESH_MS)
    } else if (!shouldRun && timer) {
      clearInterval(timer)
      timer = undefined
    }
  }

  // 列表换了一批在途记录（翻页、筛选、刷新）立刻问一次，不必干等一个周期
  watch(() => activeIds.value.join(','), (ids) => {
    if (!ids) liveById.clear()
    sync()
    if (ids && active) refresh()
  })
  onActivated(() => {
    active = true
    sync()
    refresh()
  })
  onDeactivated(() => {
    active = false
    sync()
  })
  onBeforeUnmount(() => {
    active = false
    sync()
  })

  const liveOf = (id: number) => liveById.get(id)

  return { liveOf, refreshLive: refresh }
}
