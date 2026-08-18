import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref, computed } from 'vue'

afterEach(() => {
  document.querySelectorAll('.v-overlay-container').forEach((el) => el.remove())
})

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ query: {} })
}))

// usePtHealth 真实模块会 import '@/router'，而那个模块在作用域顶层就 createRouter()。
// 页面本身只用它反查一次路径，整个 mock 掉最省事。
vi.mock('@/router', () => ({
  getRoutePathForComponent: (key: string) => `/openlist/${key.split('/')[1]}`
}))

vi.mock('@/composables/usePtHealth', async (importOriginal) => {
  // bucketMeta / diagnosisMeta / posterUrl 是纯函数，用真的那份——
  // 页面正是靠它们把「该怎么办」渲染出来，桩掉就等于没测到这次改动
  const actual = await importOriginal<typeof import('@/composables/usePtHealth')>()
  return { ...actual, usePtHealth: vi.fn() }
})

import PtHealthPage from '../index.vue'
import { usePtHealth } from '@/composables/usePtHealth'

function episode(overrides: Record<string, any> = {}) {
  return {
    episode: 1,
    state: 'MISSING',
    airDate: '2026-08-01',
    overdueDays: 5,
    bucket: 'OVERDUE_MISSING',
    diagnosis: 'AUTO_SEARCH_OFF',
    ...overrides
  }
}

function subscription(overrides: Record<string, any> = {}) {
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
    episodes: [episode()],
    ...overrides
  }
}

function baseComposable(overrides: Record<string, any> = {}) {
  const subscriptions = overrides.subscriptions ?? ref([subscription()])
  return {
    loading: ref(false),
    loadFailed: ref(false),
    lastLoadedAt: ref(new Date(2026, 7, 18, 9, 5)),
    report: ref({
      overdueDays: 3,
      subscriptionCount: 1,
      episodeCount: 1,
      bucketCounts: { OVERDUE_MISSING: 1 },
      diagnosisCounts: { AUTO_SEARCH_OFF: 1 },
      subscriptions: subscriptions.value
    }),
    activeBucket: ref(''),
    activeDiagnosis: ref(''),
    subscriptions,
    filteredCount: computed(() => ({ subscriptionCount: 1, episodeCount: 1 })),
    filtering: computed(() => false),
    bucketTabs: computed(() => []),
    diagnosisTabs: computed(() => []),
    autoSearchOffIds: computed(() => [1]),
    batchActing: ref(false),
    isActing: vi.fn(() => false),
    anyActing: computed(() => false),
    load: vi.fn(),
    handleEnableAutoSearch: vi.fn(),
    handleSearchNow: vi.fn(),
    openSubscription: vi.fn(),
    setBucket: vi.fn(),
    setDiagnosis: vi.fn(),
    ...overrides
  }
}

describe('PtHealth 加载失败态', () => {
  /**
   * 失败时若只是渲染空列表，用户看到的是绿色对勾「没有发现缺集」——
   * 接口挂了和一切正常长得一模一样，对体检页来说这是最不该给的错误答案。
   */
  it('加载失败时给出失败提示与重试，而不是「没有发现缺集」', () => {
    (usePtHealth as any).mockReturnValue(baseComposable({
      loadFailed: ref(true),
      subscriptions: ref([])
    }))
    const wrapper = mount(PtHealthPage)
    const text = wrapper.text()
    expect(text).toContain('体检报告加载失败')
    expect(text).not.toContain('没有发现缺集')
    expect(text).toContain('重试')
  })

  it('加载成功且确实没有缺集时才说「没有发现缺集」', () => {
    (usePtHealth as any).mockReturnValue(baseComposable({
      loadFailed: ref(false),
      subscriptions: ref([])
    }))
    const wrapper = mount(PtHealthPage)
    expect(wrapper.text()).toContain('没有发现缺集')
    expect(wrapper.text()).not.toContain('体检报告加载失败')
  })
})

describe('PtHealth 处置建议', () => {
  /** 这页的立身之本就是说清「为什么还缺」，挂在 title 属性上等于移动端看不见 */
  it('诊断建议铺在卡片正文里，不只挂在 tooltip 上', () => {
    (usePtHealth as any).mockReturnValue(baseComposable())
    const wrapper = mount(PtHealthPage)
    const advice = wrapper.find('.advice-list')
    expect(advice.exists()).toBe(true)
    expect(advice.text()).toContain('自动补搜')
  })
})

describe('PtHealth 集号展示', () => {
  it('集数多时截断并给出展开入口，不再塞进内嵌滚动框', async () => {
    const episodes = Array.from({ length: 40 }, (_, i) => episode({ episode: i + 1 }))
    ;(usePtHealth as any).mockReturnValue(baseComposable({
      subscriptions: ref([subscription({ episodes })])
    }))
    const wrapper = mount(PtHealthPage)

    // 默认只铺前 24 个
    expect(wrapper.findAll('.episode-row .v-chip').length).toBe(24)
    const more = wrapper.find('.episode-more')
    expect(more.exists()).toBe(true)
    expect(more.text()).toContain('16')

    await more.trigger('click')
    expect(wrapper.findAll('.episode-row .v-chip').length).toBe(40)
    expect(wrapper.find('.episode-more').exists()).toBe(false)
  })

  it('集数不多时不出现展开入口', () => {
    (usePtHealth as any).mockReturnValue(baseComposable())
    const wrapper = mount(PtHealthPage)
    expect(wrapper.find('.episode-more').exists()).toBe(false)
  })
})

describe('PtHealth 按行动作', () => {
  it('只有正在跑的那一行按钮进入 loading', () => {
    const isActing = vi.fn((id: number) => id === 2)
    ;(usePtHealth as any).mockReturnValue(baseComposable({
      subscriptions: ref([subscription({ subId: 1 }), subscription({ subId: 2 })]),
      isActing
    }))
    const wrapper = mount(PtHealthPage)
    const cards = wrapper.findAll('.health-item')
    expect(cards).toHaveLength(2)
    // isActing 被逐行调用，说明 loading 不再是整页共用一个标志
    expect(isActing).toHaveBeenCalledWith(1)
    expect(isActing).toHaveBeenCalledWith(2)
  })
})
