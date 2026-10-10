import { describe, it, expect, vi, beforeEach } from 'vitest'
import { nextTick } from 'vue'

vi.mock('@/composables/useMessage', () => ({
  message: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }
}))

const push = vi.fn()
vi.mock('vue-router', () => ({ useRouter: () => ({ push }) }))

let subscriptionPath: string | null = '/openlist/ptSubscription'
vi.mock('@/router', () => ({ getRoutePathForComponent: () => subscriptionPath }))

const roles = { value: [] as string[] }
vi.mock('@/stores/user', () => ({ useUserStore: () => ({ get roles() { return roles.value } }) }))

vi.mock('@/api/openlist/ptIndexer', () => ({
  getPtIndexerListApi: vi.fn().mockResolvedValue({ records: [{ id: 1, name: '馒头' }], total: 1 })
}))

vi.mock('@/api/openlist/ptSearch', () => ({
  searchResourceApi: vi.fn(),
  listResourceDownloadersApi: vi.fn(),
  pushResourceApi: vi.fn()
}))

const api = await import('@/api/openlist/ptSearch')
const { message } = await import('@/composables/useMessage')
const { usePtResourceSearch, looksLikeMovie, subscribeQuery } = await import('@/composables/usePtResourceSearch')

const item = (over: Record<string, any> = {}) => ({
  title: 'Three.Body.S01E05.2160p.WEB-DL',
  size: 1, seeders: 3, peers: 0, free: false, downloadVolumeFactor: 1,
  indexerName: '馒头', indexerId: 1, hitAndRun: false, downloadUrl: 'http://dl',
  ...over
})

describe('usePtResourceSearch', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    roles.value = []
    subscriptionPath = '/openlist/ptSubscription'
  })

  /** 与后端 ResourceSearchService#looksLikeMovie 同一判据：决定落盘目录，也决定转订阅时先搜电影还是剧集 */
  it('解析不出季号与集号的按电影', () => {
    expect(looksLikeMovie({ parsedSeason: undefined, parsedEpisode: undefined })).toBe(true)
    expect(looksLikeMovie({ parsedSeason: 1, parsedEpisode: undefined })).toBe(false)
    expect(looksLikeMovie({ parsedSeason: undefined, parsedEpisode: 5 })).toBe(false)
  })

  it('转订阅用解析出的片名，解析不出才用原标题', () => {
    expect(subscribeQuery(item({ parsedTitle: '三体', parsedSeason: 1 }) as any))
      .toEqual({ subscribe: '三体', mediaType: 'TV' })
    expect(subscribeQuery(item({ title: 'Dune.2024' }) as any))
      .toEqual({ subscribe: 'Dune.2024', mediaType: 'MOVIE' })
  })

  /** 罗马音 / 拼音命名的种子拿解析出的片名去订阅页搜 TMDb 多半落空，识别出的规范名一搜就是它 */
  it('识别出作品时转订阅用它的规范名与类型，不用解析出的片名', () => {
    expect(subscribeQuery(item({
      parsedTitle: 'Gei a ma de qing shu', matchedTitle: '给阿嬷的情书', matchedTmdbId: '1', mediaType: 'MOVIE', parsedSeason: 1
    }) as any)).toEqual({ subscribe: '给阿嬷的情书', mediaType: 'MOVIE' })
  })

  it('关键词不足两个字不发请求', async () => {
    const ctx = usePtResourceSearch()
    ctx.keyword.value = ' 三 '

    await ctx.handleSearch()

    expect(api.searchResourceApi).not.toHaveBeenCalled()
    expect(message.warning).toHaveBeenCalled()
  })

  it('选了站点才带 indexerIds，没选就搜全部', async () => {
    vi.mocked(api.searchResourceApi).mockResolvedValue({
      candidateCount: 0, rejectedCount: 0, tmdbLookupEnabled: true, distinctWorks: 0, identifiedWorks: 0,
      tmdbLookupSkipped: false, tmdbLookupTruncated: false, tmdbLookupUnavailable: false, items: []
    })
    const ctx = usePtResourceSearch()
    ctx.keyword.value = '三体'

    await ctx.handleSearch()
    expect(vi.mocked(api.searchResourceApi).mock.calls[0][0]).toEqual({ keyword: '三体', indexerIds: undefined })

    ctx.indexerIds.value = [1]
    await ctx.handleSearch()
    expect(vi.mocked(api.searchResourceApi).mock.calls[1][0]).toEqual({ keyword: '三体', indexerIds: [1] })
    expect(ctx.searched.value).toBe(true)
  })

  /** 规则只标注不淘汰：默认全部显示，勾上「只看规则放行的」才藏掉被挡的 */
  it('只看规则放行的', async () => {
    vi.mocked(api.searchResourceApi).mockResolvedValue({
      candidateCount: 2,
      rejectedCount: 1,
      tmdbLookupEnabled: true,
      distinctWorks: 2, identifiedWorks: 0,
      tmdbLookupSkipped: false,
      tmdbLookupTruncated: false, tmdbLookupUnavailable: false,
      items: [item({ guid: 'a' }), item({ guid: 'b', ruleRejection: '分辨率不在白名单' })] as any
    })
    const ctx = usePtResourceSearch()
    ctx.keyword.value = '三体'
    await ctx.handleSearch()
    await nextTick()

    expect(ctx.visibleResults.value).toHaveLength(2)
    ctx.passedOnly.value = true
    expect(ctx.visibleResults.value.map((i: any) => i.guid)).toEqual(['a'])
    expect(ctx.activeFilterCount.value).toBe(1)

    ctx.clearFilters()
    expect(ctx.passedOnly.value).toBe(false)
    expect(ctx.visibleResults.value).toHaveLength(2)
  })

  it('标题组数超过上限时说明只识别了一部分，没问的那些行不能显示「识别不出」', async () => {
    vi.mocked(api.searchResourceApi).mockResolvedValue({
      candidateCount: 21, rejectedCount: 0, tmdbLookupEnabled: true, distinctWorks: 21, identifiedWorks: 0,
      tmdbLookupSkipped: true, tmdbLookupTruncated: false, tmdbLookupUnavailable: false,
      items: [item({ guid: 'a' })] as any
    })
    const ctx = usePtResourceSearch()
    ctx.keyword.value = '三体'
    await ctx.handleSearch()

    expect(ctx.lookupNote.value).toContain('只识别了一部分')
    expect(ctx.lookupNote.value).toContain('21')
    // 上限那个数字在后端，文案里不写死
    expect(ctx.lookupNote.value).not.toContain('20')
    expect(ctx.lookupComplete.value).toBe(false)
  })

  it('识别跑完时不报状态，只报已识别数', async () => {
    vi.mocked(api.searchResourceApi).mockResolvedValue({
      candidateCount: 2, rejectedCount: 0, tmdbLookupEnabled: true, distinctWorks: 2, identifiedWorks: 1,
      tmdbLookupSkipped: false, tmdbLookupTruncated: false, tmdbLookupUnavailable: false,
      items: [item({ guid: 'a', matchedTmdbId: '79481' }), item({ guid: 'b' })] as any
    })
    const ctx = usePtResourceSearch()
    ctx.keyword.value = '三体'
    await ctx.handleSearch()

    expect(ctx.lookupNote.value).toBe('')
    expect(ctx.matchedWorks.value).toBe(1)
    expect(ctx.lookupComplete.value).toBe(true)
    // 分子分母不是同一口径（作品数 vs 标题组数），不许摆成「1 / 2」
    expect(ctx.lookupSummary.value).toBe('识别出 1 个作品，1 组标题识别不出')
  })

  it('同一个 tmdbId 的电影与剧集算两个作品', async () => {
    vi.mocked(api.searchResourceApi).mockResolvedValue({
      candidateCount: 2, rejectedCount: 0, tmdbLookupEnabled: true, distinctWorks: 2, identifiedWorks: 2,
      tmdbLookupSkipped: false, tmdbLookupTruncated: false, tmdbLookupUnavailable: false,
      items: [
        item({ guid: 'a', matchedTmdbId: '1399', mediaType: 'TV' }),
        item({ guid: 'b', matchedTmdbId: '1399', mediaType: 'MOVIE' })
      ] as any
    })
    const ctx = usePtResourceSearch()
    ctx.keyword.value = '三体'
    await ctx.handleSearch()

    expect(ctx.matchedWorks.value).toBe(2)
    expect(ctx.lookupSummary.value).toBe('识别出 2 个作品')
  })

  it('预算内没跑完时要说清已识别多少', async () => {
    vi.mocked(api.searchResourceApi).mockResolvedValue({
      candidateCount: 3, rejectedCount: 0, tmdbLookupEnabled: true, distinctWorks: 3, identifiedWorks: 1,
      tmdbLookupSkipped: false, tmdbLookupTruncated: true, tmdbLookupUnavailable: false,
      items: [item({ guid: 'a', matchedTmdbId: '1' })] as any
    })
    const ctx = usePtResourceSearch()
    ctx.keyword.value = '三体'
    await ctx.handleSearch()

    expect(ctx.lookupNote.value).toContain('未跑完')
    expect(ctx.lookupNote.value).toContain('3 组标题只识别了 1 组')
    // 没跑完时没带作品的那一行不能说「识别不出」——真相是还没识别
    expect(ctx.lookupComplete.value).toBe(false)
    expect(ctx.lookupSummary.value).toBe('')
  })

  it('识别数按作品计而不是按种子行数计', async () => {
    vi.mocked(api.searchResourceApi).mockResolvedValue({
      candidateCount: 3, rejectedCount: 0, tmdbLookupEnabled: true, distinctWorks: 1, identifiedWorks: 0,
      tmdbLookupSkipped: false, tmdbLookupTruncated: false, tmdbLookupUnavailable: false,
      items: [
        item({ guid: 'a', matchedTmdbId: '79481' }),
        item({ guid: 'b', matchedTmdbId: '79481' }),
        item({ guid: 'c', matchedTmdbId: '79481' })
      ] as any
    })
    const ctx = usePtResourceSearch()
    ctx.keyword.value = '斗破苍穹'
    await ctx.handleSearch()

    expect(ctx.matchedWorks.value).toBe(1)
  })

  it('TMDb key 未配置时说清是「没识别」而不是逐行「识别不出」', async () => {
    vi.mocked(api.searchResourceApi).mockResolvedValue({
      candidateCount: 1, rejectedCount: 0, tmdbLookupEnabled: true, distinctWorks: 1, identifiedWorks: 0,
      tmdbLookupSkipped: false, tmdbLookupTruncated: false, tmdbLookupUnavailable: true,
      items: [item({ guid: 'a' })] as any
    })
    const ctx = usePtResourceSearch()
    ctx.keyword.value = '三体'
    await ctx.handleSearch()

    expect(ctx.lookupNote.value).toContain('TMDb key 未配置')
    expect(ctx.lookupComplete.value).toBe(false)
  })

  it('直接下载只对管理员开放', () => {
    expect(usePtResourceSearch().isAdmin.value).toBe(false)
    roles.value = ['admin']
    expect(usePtResourceSearch().isAdmin.value).toBe(true)
  })

  it('只有一台可用下载器时直接选上', async () => {
    vi.mocked(api.listResourceDownloadersApi).mockResolvedValue([{ id: 7, name: 'qb' }])
    const ctx = usePtResourceSearch()

    await ctx.openPush(item() as any)

    expect(ctx.pushOpen.value).toBe(true)
    expect(ctx.downloaderId.value).toBe(7)
  })

  it('推送带上所选下载器与候选原样字段，成功后关弹窗', async () => {
    vi.mocked(api.listResourceDownloadersApi).mockResolvedValue([{ id: 7, name: 'qb' }, { id: 8, name: 'tr' }])
    vi.mocked(api.pushResourceApi).mockResolvedValue('已推送，保存到 /downloads/剧集' as any)
    const ctx = usePtResourceSearch()
    await ctx.openPush(item({ description: '三体 | S01E05' }) as any)
    expect(ctx.downloaderId.value).toBeNull()

    await ctx.confirmPush()
    expect(api.pushResourceApi).not.toHaveBeenCalled()

    ctx.downloaderId.value = 8
    await ctx.confirmPush()
    expect(vi.mocked(api.pushResourceApi).mock.calls[0][0]).toMatchObject({ downloaderId: 8, description: '三体 | S01E05' })
    expect(ctx.pushOpen.value).toBe(false)
  })

  it('转订阅跳到订阅页并带上片名', () => {
    const ctx = usePtResourceSearch()

    ctx.toSubscribe(item({ parsedTitle: '三体', parsedSeason: 1 }) as any)

    expect(push).toHaveBeenCalledWith({ path: '/openlist/ptSubscription', query: { subscribe: '三体', mediaType: 'TV' } })
  })

  it('没有订阅页权限时提示而不是跳 404', () => {
    subscriptionPath = null
    const ctx = usePtResourceSearch()

    ctx.toSubscribe(item() as any)

    expect(push).not.toHaveBeenCalled()
    expect(message.warning).toHaveBeenCalled()
  })
})
