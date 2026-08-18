import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'

vi.mock('@/composables/useMessage', () => ({
  message: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn()
  }
}))

vi.mock('@/composables/useConfirm', () => ({
  confirm: vi.fn()
}))

// base.getList() 在 setup 阶段同步调用，mock 掉整个 API 模块避免真实网络请求。
vi.mock('@/api/openlist/ptSubscription', () => ({
  getPtSubscriptionListApi: vi.fn().mockResolvedValue({ records: [], total: 0 }),
  addPtSubscriptionApi: vi.fn(),
  updatePtSubscriptionApi: vi.fn(),
  deletePtSubscriptionApi: vi.fn(),
  tmdbSearchApi: vi.fn(),
  tmdbSeasonEpisodeCountApi: vi.fn(),
  subscribeApi: vi.fn(),
  getSubscriptionProgressApi: vi.fn(),
  getSubscriptionEpisodesApi: vi.fn(),
  resetEpisodeApi: vi.fn(),
  pushSelectedCandidateApi: vi.fn(),
  getPtSubscriptionByIdApi: vi.fn(),
  refreshSubscriptionApi: vi.fn(),
  pauseSubscriptionApi: vi.fn(),
  resumeSubscriptionApi: vi.fn(),
  searchSupplementApi: vi.fn(),
  getSubscriptionSearchLogsApi: vi.fn(),
  batchPauseSubscriptionApi: vi.fn(),
  batchResumeSubscriptionApi: vi.fn(),
  batchDeletePtSubscriptionApi: vi.fn()
}))

vi.mock('../usePtStatusSocket', () => ({
  usePtStatusSocket: vi.fn()
}))

// 过滤覆盖弹窗现在会拉一次全局过滤配置当参照，mock 掉避免真实请求
vi.mock('@/api/openlist/ptFilterConfig', () => ({
  getPtFilterConfigApi: vi.fn().mockResolvedValue({})
}))

import { usePtSubscription } from '../usePtSubscription'
import {
  getPtSubscriptionListApi,
  batchPauseSubscriptionApi,
  batchResumeSubscriptionApi,
  searchSupplementApi,
  getSubscriptionProgressApi
} from '@/api/openlist/ptSubscription'
import { usePtStatusSocket } from '../usePtStatusSocket'

describe('usePtSubscription 的批量暂停/恢复', () => {
  let confirmSpy: any
  let successSpy: any

  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtSubscriptionListApi as any).mockResolvedValue({ records: [], total: 0 })
    confirmSpy = confirm as any
    confirmSpy.mockResolvedValue(undefined)
    successSpy = message.success as any
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('没有选中项时批量暂停不发起确认框', async () => {
    const composable = usePtSubscription()

    await composable.handleBatchPause()

    expect(confirmSpy).not.toHaveBeenCalled()
    expect(batchPauseSubscriptionApi).not.toHaveBeenCalled()
  })

  it('有选中项时批量暂停确认后调用接口、提示结果、清空选中并刷新', async () => {
    (batchPauseSubscriptionApi as any).mockResolvedValue({ successCount: 2, failedIds: [3] })
    const composable = usePtSubscription()
    composable.selectedIds.value = [1, 2, 3]

    await composable.handleBatchPause()

    expect(batchPauseSubscriptionApi).toHaveBeenCalledWith([1, 2, 3])
    expect(successSpy).toHaveBeenCalledWith('成功 2 项，1 项已跳过（可能已被删除）')
    expect(composable.selectedIds.value).toEqual([])
  })

  it('全部成功时提示语不带跳过后缀', async () => {
    (batchPauseSubscriptionApi as any).mockResolvedValue({ successCount: 2, failedIds: [] })
    const composable = usePtSubscription()
    composable.selectedIds.value = [1, 2]

    await composable.handleBatchPause()

    expect(successSpy).toHaveBeenCalledWith('成功 2 项')
  })

  it('批量恢复同构：确认后调用 batchResumeSubscriptionApi', async () => {
    (batchResumeSubscriptionApi as any).mockResolvedValue({ successCount: 1, failedIds: [] })
    const composable = usePtSubscription()
    composable.selectedIds.value = [9]

    await composable.handleBatchResume()

    expect(batchResumeSubscriptionApi).toHaveBeenCalledWith([9])
  })

  it('toggleSubSelect 未选中时加入、已选中时移除，isSubSelected 与之同步', () => {
    const composable = usePtSubscription()
    expect(composable.isSubSelected(7)).toBe(false)
    composable.toggleSubSelect({ id: 7 })
    expect(composable.isSubSelected(7)).toBe(true)
    composable.toggleSubSelect({ id: 7 })
    expect(composable.isSubSelected(7)).toBe(false)
  })
})

describe('usePtSubscription 实时状态推送', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtSubscriptionListApi as any).mockResolvedValue({ records: [], total: 0 })
    ;(usePtStatusSocket as any).mockReturnValue({ connect: vi.fn(), disconnect: vi.fn() })
  })

  it('收到 subscription 事件后原地更新对应行的 lastMatchTime，不重新整页拉取', () => {
    const composable = usePtSubscription()
    composable.taskList.value = [
      { id: 1, lastMatchTime: null },
      { id: 2, lastMatchTime: '2026-01-01 00:00:00' }
    ]

    const handlers = (usePtStatusSocket as any).mock.calls[0][0]
    handlers.onSubscription({ type: 'subscription', subId: 1, lastMatchTime: '2026-07-24 15:30:00' })

    expect(composable.taskList.value[0].lastMatchTime).toBe('2026-07-24 15:30:00')
    expect(composable.taskList.value[1].lastMatchTime).toBe('2026-01-01 00:00:00')
  })

  it('找不到对应行时静默忽略，不抛异常', () => {
    const composable = usePtSubscription()
    composable.taskList.value = [{ id: 1, lastMatchTime: null }]

    const handlers = (usePtStatusSocket as any).mock.calls[0][0]
    expect(() => handlers.onSubscription({ type: 'subscription', subId: 999, lastMatchTime: '2026-07-24 15:30:00' })).not.toThrow()
  })
})

describe('usePtSubscription 一键补齐全部缺集', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtSubscriptionListApi as any).mockResolvedValue({ records: [], total: 0 })
    ;(getSubscriptionProgressApi as any).mockResolvedValue({
      totalEpisodes: 3, inLibraryCount: 0, inFlightCount: 0, missingEpisodes: [1, 2, 3]
    })
  })

  /**
   * 这条守的是一个隐蔽缺陷：循环里每集要等一次几十秒的检索，期间弹窗点遮罩就能关，
   * 用户去点开另一条订阅的进度会改掉 currentSubscription——旧实现每轮现读它，
   * 于是剩下的集变成「拿 A 的集号、按 B 的标题、推给 B 的订阅」，界面上毫无迹象。
   */
  it('跑批中途 currentSubscription 被换掉，剩余集仍推给发起时那条订阅', async () => {
    const subA = { id: 1, title: 'A剧', season: 1, mediaType: 'TV' }
    const subB = { id: 99, title: 'B剧', season: 2, mediaType: 'TV' }
    const composable = usePtSubscription()
    composable.currentSubscription.value = subA
    composable.progress.value = { missingEpisodes: [1, 2, 3] }

    ;(searchSupplementApi as any).mockImplementation(async () => {
      // 第一集搜完就模拟用户切到了另一条订阅
      composable.currentSubscription.value = subB
      return { pushed: true }
    })

    await composable.handleSearchAllMissing()

    const calls = (searchSupplementApi as any).mock.calls
    expect(calls).toHaveLength(3)
    for (const [subId, payload] of calls) {
      expect(subId).toBe(subA.id)
      expect(payload.keyword).toContain('A剧')
      expect(payload.keyword).toContain('S01')
    }
  })

  it('用户点停止后，当前这一集跑完就收尾，不再搜后面的集', async () => {
    const composable = usePtSubscription()
    composable.currentSubscription.value = { id: 1, title: 'A剧', season: 1, mediaType: 'TV' }
    composable.progress.value = { missingEpisodes: [1, 2, 3, 4, 5] }

    ;(searchSupplementApi as any).mockImplementation(async () => {
      composable.abortSearchAllMissing()
      return { pushed: false }
    })

    await composable.handleSearchAllMissing()

    // 第一集发出去了、跑完才停，所以恰好一次
    expect((searchSupplementApi as any).mock.calls).toHaveLength(1)
    expect(composable.searchAllMissingDone.value).toBe(1)
    expect(composable.searchAllMissingTotal.value).toBe(5)
    expect(composable.searchAllMissingLoading.value).toBe(false)
  })

  it('跑批期间切走订阅后，进度不回写到用户正在看的那条', async () => {
    const composable = usePtSubscription()
    composable.currentSubscription.value = { id: 1, title: 'A剧', season: 1, mediaType: 'TV' }
    composable.progress.value = { missingEpisodes: [1] }
    ;(searchSupplementApi as any).mockImplementation(async () => {
      composable.currentSubscription.value = { id: 99, title: 'B剧', season: 2, mediaType: 'TV' }
      return { pushed: true }
    })

    await composable.handleSearchAllMissing()

    expect(getSubscriptionProgressApi).not.toHaveBeenCalled()
  })
})

describe('usePtSubscription 批量模式开关', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtSubscriptionListApi as any).mockResolvedValue({ records: [], total: 0 })
  })

  /**
   * 勾选框只在批量模式下渲染，残留的选择在界面上完全不可见——
   * 等用户下次点开「批量操作」，「已选 N 项」会凭空出现。
   */
  it('退出批量模式时清空已选', () => {
    const composable = usePtSubscription()
    composable.toggleSelectionMode()
    expect(composable.selectionMode.value).toBe(true)

    composable.toggleSubSelect({ id: 7 })
    expect(composable.selectedIds.value).toEqual([7])

    composable.toggleSelectionMode()
    expect(composable.selectionMode.value).toBe(false)
    expect(composable.selectedIds.value).toEqual([])
  })
})
