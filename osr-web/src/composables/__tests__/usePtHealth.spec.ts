import { describe, it, expect, vi, beforeEach } from 'vitest'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'

vi.mock('@/composables/useMessage', () => ({
  message: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }
}))

vi.mock('@/composables/useConfirm', () => ({ confirm: vi.fn() }))

vi.mock('@/api/openlist/ptHealth', () => ({
  getPtHealthApi: vi.fn(),
  enableAutoSearchApi: vi.fn(),
  searchMissingApi: vi.fn()
}))

// usePtHealth 在 setup 阶段就 useRouter()，测试里没装路由插件
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }))
vi.mock('@/router', () => ({ getRoutePathForComponent: () => '/openlist/ptSubscription' }))

import { usePtHealth } from '../usePtHealth'
import { getPtHealthApi, enableAutoSearchApi, searchMissingApi } from '@/api/openlist/ptHealth'

/** 造一条订阅体检结果 */
function sub(overrides: Record<string, any> = {}) {
  return {
    subId: 1,
    tmdbId: '1',
    title: 'A 剧',
    posterPath: null,
    mediaType: 'TV',
    season: 1,
    autoSearch: false,
    lastSearchTime: null,
    missStreak: 0,
    rejectDetail: null,
    maxOverdueDays: 5,
    diagnoses: ['AUTO_SEARCH_OFF'],
    buckets: ['OVERDUE_MISSING'],
    episodes: [
      { episode: 1, state: 'MISSING', airDate: '2026-08-01', overdueDays: 5, bucket: 'OVERDUE_MISSING', diagnosis: 'AUTO_SEARCH_OFF' }
    ],
    ...overrides
  }
}

function report(overrides: Record<string, any> = {}) {
  return {
    overdueDays: 3,
    subscriptionCount: 1,
    episodeCount: 1,
    bucketCounts: { OVERDUE_MISSING: 1 },
    diagnosisCounts: { AUTO_SEARCH_OFF: 1 },
    subscriptions: [sub()],
    ...overrides
  }
}

/** load() 在 usePtHealth() 里同步触发，等它跑完 */
const flush = () => new Promise((r) => setTimeout(r, 0))

beforeEach(() => {
  vi.clearAllMocks()
  ;(getPtHealthApi as any).mockResolvedValue(report())
  ;(confirm as any).mockResolvedValue(undefined)
})

describe('usePtHealth 加载失败', () => {
  /**
   * 这条守的是一个会给出错误结论的问题：失败时把报告置空，而空报告渲染出来是
   * 绿色对勾「没有发现缺集」——接口挂了和一切正常长得一模一样。
   */
  it('加载失败时 loadFailed 为真，不能只靠「报告为空」表达失败', async () => {
    (getPtHealthApi as any).mockRejectedValue(new Error('boom'))
    const c = usePtHealth()
    await flush()

    expect(c.loadFailed.value).toBe(true)
    expect(c.subscriptions.value).toEqual([])
    expect(c.loading.value).toBe(false)
  })

  it('加载成功后 loadFailed 归位，并记下刷新时刻', async () => {
    const c = usePtHealth()
    await flush()

    expect(c.loadFailed.value).toBe(false)
    expect(c.lastLoadedAt.value).toBeInstanceOf(Date)
  })

  it('重试成功后失败态被清掉', async () => {
    (getPtHealthApi as any).mockRejectedValueOnce(new Error('boom'))
    const c = usePtHealth()
    await flush()
    expect(c.loadFailed.value).toBe(true)

    ;(getPtHealthApi as any).mockResolvedValue(report())
    await c.load()
    expect(c.loadFailed.value).toBe(false)
    expect(c.subscriptions.value).toHaveLength(1)
  })
})

describe('usePtHealth 按行独立的动作状态', () => {
  /**
   * 原先是一个全局 acting 布尔量，点第 3 行会让整页按钮一起转圈并禁用，
   * 而「立即补搜」本来就要跑几十秒——整页看起来是卡死的。
   */
  it('立即补搜只让被点的那一行进入 loading', async () => {
    (getPtHealthApi as any).mockResolvedValue(report({
      subscriptionCount: 2,
      episodeCount: 2,
      subscriptions: [sub({ subId: 1 }), sub({ subId: 2 })]
    }))
    let resolveSearch: (v: string) => void = () => {}
    ;(searchMissingApi as any).mockImplementation(() => new Promise((r) => { resolveSearch = r }))

    const c = usePtHealth()
    await flush()

    const promise = c.handleSearchNow(1)
    expect(c.isActing(1)).toBe(true)
    expect(c.isActing(2)).toBe(false)

    resolveSearch('已推送 1 个资源到下载器')
    await promise
    expect(c.isActing(1)).toBe(false)
  })

  it('批量开启时所有行一起锁住，避免并发改同一批数据', async () => {
    let resolveEnable: (v: number) => void = () => {}
    ;(enableAutoSearchApi as any).mockImplementation(() => new Promise((r) => { resolveEnable = r }))

    const c = usePtHealth()
    await flush()

    const promise = c.handleEnableAutoSearch()
    await flush()
    expect(c.batchActing.value).toBe(true)
    expect(c.isActing(1)).toBe(true)
    expect(c.anyActing.value).toBe(true)

    resolveEnable(1)
    await promise
    expect(c.batchActing.value).toBe(false)
    expect(c.anyActing.value).toBe(false)
  })
})

describe('usePtHealth 批量开启自动补搜', () => {
  it('批量要二次确认，用户取消则不发请求', async () => {
    (confirm as any).mockRejectedValue('cancel')
    const c = usePtHealth()
    await flush()

    await c.handleEnableAutoSearch()

    expect(confirm).toHaveBeenCalled()
    expect(enableAutoSearchApi).not.toHaveBeenCalled()
  })

  /** 逐行开启只影响一条，不该拿确认框打断用户 */
  it('逐行开启不弹确认框', async () => {
    (enableAutoSearchApi as any).mockResolvedValue(1)
    const c = usePtHealth()
    await flush()

    await c.handleEnableAutoSearch([1])

    expect(confirm).not.toHaveBeenCalled()
    expect(enableAutoSearchApi).toHaveBeenCalledWith([1])
  })

  it('没有可开启的订阅时只提示、不发请求', async () => {
    (getPtHealthApi as any).mockResolvedValue(report({
      subscriptions: [sub({ autoSearch: true })]
    }))
    const c = usePtHealth()
    await flush()

    await c.handleEnableAutoSearch()

    expect(enableAutoSearchApi).not.toHaveBeenCalled()
    expect(message.info).toHaveBeenCalled()
  })
})

describe('usePtHealth 筛选', () => {
  it('分档与成因可叠加，且订阅内部的集也跟着收窄', async () => {
    (getPtHealthApi as any).mockResolvedValue(report({
      subscriptionCount: 1,
      episodeCount: 2,
      bucketCounts: { OVERDUE_MISSING: 1, BLOCKED: 1 },
      diagnosisCounts: { AUTO_SEARCH_OFF: 1, BLOCKED: 1 },
      subscriptions: [sub({
        buckets: ['OVERDUE_MISSING', 'BLOCKED'],
        diagnoses: ['AUTO_SEARCH_OFF', 'BLOCKED'],
        episodes: [
          { episode: 1, state: 'MISSING', airDate: '2026-08-01', overdueDays: 5, bucket: 'OVERDUE_MISSING', diagnosis: 'AUTO_SEARCH_OFF' },
          { episode: 2, state: 'BLOCKED', airDate: '2026-08-02', overdueDays: 4, bucket: 'BLOCKED', diagnosis: 'BLOCKED' }
        ]
      })]
    }))
    const c = usePtHealth()
    await flush()

    c.setBucket('BLOCKED')
    expect(c.subscriptions.value[0].episodes.map((e: any) => e.episode)).toEqual([2])

    // 叠加一个对不上的成因，应当筛空而不是忽略其中一维
    c.setDiagnosis('AUTO_SEARCH_OFF')
    expect(c.subscriptions.value).toEqual([])
  })

  it('再点一次已选中的档就取消筛选', async () => {
    const c = usePtHealth()
    await flush()

    c.setBucket('OVERDUE_MISSING')
    expect(c.activeBucket.value).toBe('OVERDUE_MISSING')
    c.setBucket('OVERDUE_MISSING')
    expect(c.activeBucket.value).toBe('')
  })

  /**
   * 选中的档被自己的操作清空后，标签会因为计数归零而消失，筛选却还留着：
   * 列表空、没有任何 chip 是选中态、顶上却写着总数——用户看不出自己还在筛选中。
   */
  it('重新加载后，已归零的筛选档位自动退回全部', async () => {
    const c = usePtHealth()
    await flush()
    c.setBucket('OVERDUE_MISSING')
    c.setDiagnosis('AUTO_SEARCH_OFF')

    ;(getPtHealthApi as any).mockResolvedValue(report({
      subscriptionCount: 0,
      episodeCount: 0,
      bucketCounts: { OVERDUE_MISSING: 0 },
      diagnosisCounts: { AUTO_SEARCH_OFF: 0 },
      subscriptions: []
    }))
    await c.load()

    expect(c.activeBucket.value).toBe('')
    expect(c.activeDiagnosis.value).toBe('')
  })

  it('仍有条目的筛选档位在刷新后保留', async () => {
    const c = usePtHealth()
    await flush()
    c.setBucket('OVERDUE_MISSING')

    await c.load()

    expect(c.activeBucket.value).toBe('OVERDUE_MISSING')
  })

  it('汇总数字跟随筛选，避免和选中的档位数字打架', async () => {
    (getPtHealthApi as any).mockResolvedValue(report({
      subscriptionCount: 2,
      episodeCount: 2,
      bucketCounts: { OVERDUE_MISSING: 1, BLOCKED: 1 },
      subscriptions: [
        sub({ subId: 1 }),
        sub({
          subId: 2,
          buckets: ['BLOCKED'],
          diagnoses: ['BLOCKED'],
          episodes: [{ episode: 9, state: 'BLOCKED', airDate: null, overdueDays: null, bucket: 'BLOCKED', diagnosis: 'BLOCKED' }]
        })
      ]
    }))
    const c = usePtHealth()
    await flush()

    expect(c.filteredCount.value).toEqual({ subscriptionCount: 2, episodeCount: 2 })
    expect(c.filtering.value).toBe(false)

    c.setBucket('BLOCKED')
    expect(c.filteredCount.value).toEqual({ subscriptionCount: 1, episodeCount: 1 })
    expect(c.filtering.value).toBe(true)
  })

  it('成因标签只列出确实有条目的那些', async () => {
    (getPtHealthApi as any).mockResolvedValue(report({
      diagnosisCounts: { AUTO_SEARCH_OFF: 2, SEARCH_NO_CANDIDATE: 0 }
    }))
    const c = usePtHealth()
    await flush()

    expect(c.diagnosisTabs.value.map((t: any) => t.key)).toEqual(['AUTO_SEARCH_OFF'])
  })

  /** 「一键开启」的作用域跟着筛选走：用户在某一档下点它，改的就该是眼前这批 */
  it('待开启订阅取自当前筛选结果', async () => {
    (getPtHealthApi as any).mockResolvedValue(report({
      subscriptionCount: 2,
      episodeCount: 2,
      bucketCounts: { OVERDUE_MISSING: 1, BLOCKED: 1 },
      subscriptions: [
        sub({ subId: 1 }),
        sub({
          subId: 2,
          buckets: ['BLOCKED'],
          diagnoses: ['BLOCKED'],
          episodes: [{ episode: 9, state: 'BLOCKED', airDate: null, overdueDays: null, bucket: 'BLOCKED', diagnosis: 'BLOCKED' }]
        })
      ]
    }))
    const c = usePtHealth()
    await flush()

    expect(c.autoSearchOffIds.value).toEqual([1, 2])
    c.setBucket('BLOCKED')
    expect(c.autoSearchOffIds.value).toEqual([2])
  })
})
