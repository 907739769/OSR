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

// 打开搜索弹窗时会拉一次启用中的站点列表供限定检索范围
vi.mock('@/api/openlist/ptIndexer', () => ({
  getPtIndexerListApi: vi.fn().mockResolvedValue({ records: [], total: 0 })
}))

import { usePtSubscription } from '../usePtSubscription'
import {
  getPtSubscriptionListApi,
  batchPauseSubscriptionApi,
  batchResumeSubscriptionApi,
  searchSupplementApi,
  getSubscriptionProgressApi,
  getSubscriptionEpisodesApi,
  resetEpisodeApi
} from '@/api/openlist/ptSubscription'
import { usePtStatusSocket } from '../usePtStatusSocket'
import { getPtIndexerListApi } from '@/api/openlist/ptIndexer'

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
    // 1 次整季 + 3 次逐集（进度 mock 仍报 3 集都缺）
    expect(calls).toHaveLength(4)
    for (const [subId, payload] of calls) {
      expect(subId).toBe(subA.id)
      expect(payload.keyword).toContain('A剧')
      expect(payload.keyword).toContain('S01')
    }
    expect(getSubscriptionProgressApi).toHaveBeenCalledWith(subA.id)
  })

  /**
   * 只逐集搜是搜不到季包的：`片名 S01E05` 命不中标题 `片名 S01`，逐集模式也不推解析不出集号的季包。
   * 第一步先走与缺集体检「立即补搜」同一套整季补搜，补上的集不再逐个重搜。
   */
  it('先整季补搜，只对仍缺着的集逐集搜', async () => {
    const composable = usePtSubscription()
    composable.currentSubscription.value = { id: 1, title: 'A剧', season: 1, mediaType: 'TV' }
    composable.progress.value = { missingEpisodes: [1, 2, 3] }
    ;(searchSupplementApi as any).mockImplementation(async (_id: number, payload: any) =>
      payload.episode === -1 ? { pushed: true, pushedCount: 2 } : { pushed: true })
    // 整季那一步推掉了 1、2 集
    ;(getSubscriptionProgressApi as any).mockResolvedValue({ missingEpisodes: [3] })

    await composable.handleSearchAllMissing()

    const episodes = (searchSupplementApi as any).mock.calls.map((c: any[]) => c[1].episode)
    expect(episodes).toEqual([-1, 3])
    expect((searchSupplementApi as any).mock.calls[0][1].keyword).toBe('A剧 S01')
    expect(composable.searchAllMissingDone.value).toBe(3)
    expect(message.success).toHaveBeenCalledWith(expect.stringContaining('共推送 3 个资源'))
  })

  it('整季那一步失败时退回逐集搜索，不让用户白等', async () => {
    const composable = usePtSubscription()
    composable.currentSubscription.value = { id: 1, title: 'A剧', season: 1, mediaType: 'TV' }
    composable.progress.value = { missingEpisodes: [1, 2] }
    ;(searchSupplementApi as any).mockImplementation(async (_id: number, payload: any) => {
      if (payload.episode === -1) throw new Error('timeout')
      return { pushed: false }
    })

    await composable.handleSearchAllMissing()

    const episodes = (searchSupplementApi as any).mock.calls.map((c: any[]) => c[1].episode)
    expect(episodes).toEqual([-1, 1, 2])
    expect(composable.searchAllMissingSeasonPhase.value).toBe(false)
  })

  it('用户点停止后，当前这一集跑完就收尾，不再搜后面的集', async () => {
    const composable = usePtSubscription()
    composable.currentSubscription.value = { id: 1, title: 'A剧', season: 1, mediaType: 'TV' }
    composable.progress.value = { missingEpisodes: [1, 2, 3, 4, 5] }

    ;(getSubscriptionProgressApi as any).mockResolvedValue({ missingEpisodes: [1, 2, 3, 4, 5] })
    ;(searchSupplementApi as any).mockImplementation(async (_id: number, payload: any) => {
      if (payload.episode !== -1) composable.abortSearchAllMissing()
      return { pushed: false }
    })

    await composable.handleSearchAllMissing()

    // 整季一次 + 第一集发出去了、跑完才停，所以恰好两次
    expect((searchSupplementApi as any).mock.calls).toHaveLength(2)
    expect(composable.searchAllMissingDone.value).toBe(1)
    expect(composable.searchAllMissingTotal.value).toBe(5)
    expect(composable.searchAllMissingLoading.value).toBe(false)
  })

  /**
   * 未播出的集站上不可能有资源。旧实现照单全收 missingEpisodes，一部刚播到第 3 集的
   * 12 集新番会为 9 个注定落空的集各打一整轮索引器请求，用户白等十几分钟。
   */
  it('未播出的集不进跑批，缺集串照旧显示全部', async () => {
    const composable = usePtSubscription()
    composable.currentSubscription.value = { id: 1, title: 'A剧', season: 1, mediaType: 'TV' }
    composable.progress.value = { missingEpisodes: [1, 2, 3, 4, 5], unairedEpisodes: [4, 5] }
    ;(searchSupplementApi as any).mockResolvedValue({ pushed: false })

    // 缺集串不受影响——用户要知道这季还缺几集
    expect(composable.visibleMissingEpisodes.value).toEqual([1, 2, 3, 4, 5])
    expect(composable.fillableMissingEpisodes.value).toEqual([1, 2, 3])

    await composable.handleSearchAllMissing()

    const searched = (searchSupplementApi as any).mock.calls.map((c: any[]) => c[1].episode)
    // -1 是第一步的整季补搜（后端同样跳过未播集）
    expect(searched).toEqual([-1, 1, 2, 3])
    expect(composable.searchAllMissingTotal.value).toBe(3)
  })

  it('缺集全部未播出时，一键补齐直接不跑', async () => {
    const composable = usePtSubscription()
    composable.currentSubscription.value = { id: 1, title: 'A剧', season: 1, mediaType: 'TV' }
    composable.progress.value = { missingEpisodes: [4, 5], unairedEpisodes: [4, 5] }

    await composable.handleSearchAllMissing()

    expect(searchSupplementApi).not.toHaveBeenCalled()
    expect(composable.searchAllMissingLoading.value).toBe(false)
  })

  it('跑批期间切走订阅后，进度不回写到用户正在看的那条', async () => {
    const composable = usePtSubscription()
    composable.currentSubscription.value = { id: 1, title: 'A剧', season: 1, mediaType: 'TV' }
    composable.progress.value = { missingEpisodes: [1] }
    const progressOfB = { missingEpisodes: [7] }
    ;(searchSupplementApi as any).mockImplementation(async () => {
      composable.currentSubscription.value = { id: 99, title: 'B剧', season: 2, mediaType: 'TV' }
      composable.progress.value = progressOfB
      return { pushed: true }
    })

    await composable.handleSearchAllMissing()

    // 整季那一步之后会为「A」重查一次进度用来算剩余集，但不能写进用户正在看的 B 的弹窗
    expect(composable.progress.value).toEqual(progressOfB)
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

describe('usePtSubscription 季号与播出日期', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtSubscriptionListApi as any).mockResolvedValue({ records: [], total: 0 })
  })

  it('剧集给出季号文案，第 0 季按「特别篇」显示', () => {
    const composable = usePtSubscription()
    expect(composable.seasonLabel({ mediaType: 'TV', season: 3 })).toBe('第 3 季')
    // TMDb 把特别篇放在第 0 季，直接写「第 0 季」用户对不上号
    expect(composable.seasonLabel({ mediaType: 'TV', season: 0 })).toBe('特别篇')
  })

  it('电影与缺季号的订阅返回空串，让调用方整段不渲染', () => {
    const composable = usePtSubscription()
    expect(composable.seasonLabel({ mediaType: 'MOVIE', season: 1 })).toBe('')
    expect(composable.seasonLabel({ mediaType: 'TV', season: null })).toBe('')
    expect(composable.seasonLabel(null)).toBe('')
  })

  it('播出日期只取日期部分，丢掉恒为 0 的时分秒', () => {
    const composable = usePtSubscription()
    // /episodes 直接返回实体，airDate 是 java.util.Date，按全局格式序列化带时分秒
    expect(composable.episodeAirDate({ airDate: '2026-08-12 00:00:00' })).toBe('2026-08-12')
    expect(composable.episodeAirDate({ airDate: null })).toBe('')
    expect(composable.episodeAirDate({})).toBe('')
  })

  it('日期晚于今天算未播出，缺日期一律按已播出处理', () => {
    const composable = usePtSubscription()
    const day = 24 * 60 * 60 * 1000
    const fmt = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    const tomorrow = fmt(new Date(Date.now() + day))
    const yesterday = fmt(new Date(Date.now() - day))

    expect(composable.episodeUnaired({ airDate: `${tomorrow} 00:00:00` })).toBe(true)
    expect(composable.episodeUnaired({ airDate: `${yesterday} 00:00:00` })).toBe(false)
    // 判据与后端 SearchSupplementService#aired 一致：日期本身不够可靠，
    // 缺失时不能让它单方面把这一集判成「还没播」
    expect(composable.episodeUnaired({ airDate: null })).toBe(false)
  })

  it('今天播出的集不算未播出', () => {
    const composable = usePtSubscription()
    const now = new Date()
    const today = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')}`
    expect(composable.episodeUnaired({ airDate: `${today} 00:00:00` })).toBe(false)
  })
})


describe('usePtSubscription 电影的重置入口', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtSubscriptionListApi as any).mockResolvedValue({ records: [], total: 0 })
    ;(confirm as any).mockResolvedValue(undefined)
    ;(resetEpisodeApi as any).mockResolvedValue(undefined)
  })

  it('重置电影打到哨兵集号 0', async () => {
    const composable = usePtSubscription()
    await composable.handleResetMovie({ id: 7, title: '沙丘', mediaType: 'MOVIE' })
    // 电影在集表里只有集号 0 那一行。传别的集号会「重置成功」但什么都没改到，
    // 而页面上仍显示已入库——与这个入口要修的现象一模一样
    expect(resetEpisodeApi).toHaveBeenCalledWith(7, 0)
  })

  it('用户在确认框点取消时不发请求', async () => {
    (confirm as any).mockRejectedValue('cancel')
    const composable = usePtSubscription()
    await composable.handleResetMovie({ id: 7, title: '沙丘', mediaType: 'MOVIE' })
    expect(resetEpisodeApi).not.toHaveBeenCalled()
  })

  it('弹窗开着的是另一条订阅时不去刷它的进度', async () => {
    const composable = usePtSubscription()
    composable.currentSubscription.value = { id: 99, mediaType: 'MOVIE' }
    await composable.handleResetMovie({ id: 7, title: '沙丘', mediaType: 'MOVIE' })
    // 弹窗点遮罩就能关，跑批期间用户完全可能已经换看另一条订阅——
    // 不判 id 的话会把他眼前那条的进度换成刚重置的这条
    expect(getSubscriptionProgressApi).not.toHaveBeenCalled()
  })

  it('currentIsMovie 只认 mediaType，不看季号', () => {
    const composable = usePtSubscription()
    composable.currentSubscription.value = { mediaType: 'MOVIE', season: 0 }
    expect(composable.currentIsMovie.value).toBe(true)
    // 剧集的特别篇也是第 0 季，用 season === 0 判会把它一起当成电影
    composable.currentSubscription.value = { mediaType: 'TV', season: 0 }
    expect(composable.currentIsMovie.value).toBe(false)
  })
})


describe('usePtSubscription 在途集的重置出口', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtSubscriptionListApi as any).mockResolvedValue({ records: [], total: 0 })
    ;(confirm as any).mockResolvedValue(undefined)
    ;(resetEpisodeApi as any).mockResolvedValue(undefined)
    ;(getSubscriptionEpisodesApi as any).mockResolvedValue([])
    ;(getSubscriptionProgressApi as any).mockResolvedValue({})
  })

  it('重置在途集时把「不会删已下好的文件」写进确认框', async () => {
    const composable = usePtSubscription()
    composable.currentSubscription.value = { id: 7, mediaType: 'TV' }
    await composable.handleResetEpisode({ episode: 5, state: 'IN_FLIGHT' })
    const message = (confirm as any).mock.calls[0][0].message
    // 在途集的文件多半已经下好了（卡的是上传那一段）。不写清楚重置动不动它，
    // 用户只能靠试——而试错的代价是几十 GB
    expect(message).toContain('不会删下载器里的种子')
    expect(message).toContain('同步任务记录')
    expect(resetEpisodeApi).toHaveBeenCalledWith(7, 5)
  })

  it('已入库的集不带那段提示', async () => {
    const composable = usePtSubscription()
    composable.currentSubscription.value = { id: 7, mediaType: 'TV' }
    await composable.handleResetEpisode({ episode: 5, state: 'IN_LIBRARY' })
    // 这条路径的语义本来就是「重下一遍」，多一段解释只会把真正的问句挤下去
    expect((confirm as any).mock.calls[0][0].message).not.toContain('同步任务记录')
  })

  it('在途的电影同样带提示，且照样打到哨兵集号 0', async () => {
    const composable = usePtSubscription()
    await composable.handleResetMovie({ id: 7, title: '沙丘', mediaType: 'MOVIE', inFlightCount: 1 })
    expect((confirm as any).mock.calls[0][0].message).toContain('不会删下载器里的种子')
    expect(resetEpisodeApi).toHaveBeenCalledWith(7, 0)
  })
})

describe('usePtSubscription 搜索限定站点', () => {
  const flush = () => new Promise(resolve => setTimeout(resolve, 0))

  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtSubscriptionListApi as any).mockResolvedValue({ records: [], total: 0 })
  })

  it('打开搜索弹窗时只拉启用中的站点，并剔除已选里不再启用的', async () => {
    (getPtIndexerListApi as any).mockResolvedValue({ records: [{ id: 1, name: 'A站' }, { id: 3, name: 'C站' }] })
    const composable = usePtSubscription()
    composable.searchIndexerIds.value = [1, 2]

    composable.openEpisodeSearch({ id: 7, title: '三体', season: 1 }, 5)
    await flush()

    expect((getPtIndexerListApi as any).mock.calls[0][0]).toMatchObject({ enabled: '1' })
    expect(composable.searchIndexerOptions.value.map(o => o.id)).toEqual([1, 3])
    // 停用的 2 被剔掉：留着的话下拉里是个只显示 id 的孤儿 chip，而后端会静默忽略它
    expect(composable.searchIndexerIds.value).toEqual([1])
  })

  it('选了站点时带上 indexerIds，没选时不传（后端按全部启用站点搜）', async () => {
    (getPtIndexerListApi as any).mockResolvedValue({ records: [{ id: 1, name: 'A站' }] })
    ;(searchSupplementApi as any).mockResolvedValue({ pushed: false, candidateCount: 0 })
    const composable = usePtSubscription()

    composable.openEpisodeSearch({ id: 7, title: '三体', season: 1 }, 5)
    await flush()
    await composable.confirmSearch()
    expect((searchSupplementApi as any).mock.calls[0][1].indexerIds).toBeUndefined()

    composable.openEpisodeSearch({ id: 7, title: '三体', season: 1 }, 5)
    await flush()
    composable.searchIndexerIds.value = [1]
    await composable.confirmSearch()
    expect((searchSupplementApi as any).mock.calls[1][1].indexerIds).toEqual([1])
  })
})
