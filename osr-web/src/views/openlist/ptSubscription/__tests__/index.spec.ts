import { describe, it, expect, vi, afterEach } from 'vitest'
import { mount, DOMWrapper } from '@vue/test-utils'
import { ref, reactive } from 'vue'

// v-menu/v-select 的下拉内容默认 teleport 到 document.body，不在 wrapper 的挂载子树内，
// wrapper.find/findAll 搜不到——用这个包一层 document.body 去找。
const body = () => new DOMWrapper(document.body)

// 每个 mount() 出的组件如果打开过 v-menu/v-select（eager 或点击展开），其内容会 teleport 到
// body 下的 .v-overlay-container，且不随组件卸载自动清理；不清理会导致下一个用例在 body 里
// 搜到上一个用例遗留的重复节点。这里统一在每个用例结束后清掉，隔离测试之间的状态。
afterEach(() => {
  document.querySelectorAll('.v-overlay-container').forEach((el) => el.remove())
})

// 页面 <script setup> 里直接调用 useRouter()，测试环境没有安装 vue-router 插件，
// mock 掉整个模块避免路由相关报错。
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ query: {} })
}))

// 页面用 getRoutePathForComponent 按 componentKey 反查路径（后端菜单 path 有两种前缀，
// 写死会跳 404）。真实 @/router 在模块作用域就 createRouter()，测试里连 vue-router 都是
// mock 的，整个 router 模块 mock 掉最省事，页面只关心「反查得到就渲染入口」。
vi.mock('@/router', () => ({
  getRoutePathForComponent: (key: string) => `/openlist/${key.split('/')[1]}`
}))

// usePtSubscription 在 setup 阶段同步调用 base.getList() 发真实请求，
// mock 掉整个组合式函数，避免真实网络请求，并直接控制 taskList/loading。
vi.mock('@/composables/usePtSubscription', () => ({
  usePtSubscription: vi.fn()
}))

import PtSubscriptionPage from '../index.vue'
import { usePtSubscription } from '@/composables/usePtSubscription'

/**
 * usePtSubscription 展开了一长串 dialog/表单相关状态和方法，页面模板里都会用到
 * （即使本文件只关心 card-grid 分支），全部给够避免挂载时读 undefined.value 报错。
 * v-dialog/v-data-table 等 Vuetify 组件按真实实现渲染（vitest.setup.ts 已全局安装插件），
 * 无需手动 stub。
 */
function baseComposable(overrides: Record<string, any> = {}) {
  // selectionMode 与 toggleSelectionMode 要联动：页面已改成调 composable 的
  // toggleSelectionMode（PC 端原先内联写 `selectionMode = !selectionMode`，退出时不清空已选），
  // 给个纯 vi.fn() 的话「点取消收起工具条」这条用例就验不到东西了
  const selectionMode = overrides.selectionMode ?? ref(false)
  return {
    selectionMode,
    toggleSelectionMode: () => { selectionMode.value = !selectionMode.value },
    taskList: ref([]),
    loading: ref(false),
    total: ref(0),
    queryParams: reactive({ pageNum: 1, pageSize: 10 }),
    getList: vi.fn(),
    handleQuery: vi.fn(),
    resetQuery: vi.fn(),
    queryRef: ref(null),
    subscribeOpen: ref(false),
    searchLoading: ref(false),
    subscribeLoading: ref(false),
    searchResults: ref([]),
    searchForm: reactive({ mediaType: 'TV', keyword: '' }),
    picked: ref(null),
    pickedSeason: ref(1),
    openSubscribeDialog: vi.fn(),
    doSearch: vi.fn(),
    pick: vi.fn(),
    confirmSubscribe: vi.fn(),
    progressOpen: ref(false),
    progressLoading: ref(false),
    progress: ref(null),
    currentSubscription: ref(null),
    showProgress: vi.fn(),
    showProgressById: vi.fn(),
    searchLogOpen: ref(false),
    searchLogLoading: ref(false),
    searchLogs: ref([]),
    showSearchLogs: vi.fn(),
    filterOverrideOpen: ref(false),
    filterOverrideSaving: ref(false),
    filterOverrideForm: reactive({
      minSeeders: { enabled: false, value: 1 },
      minSize: { enabled: false, value: 0 },
      maxSize: { enabled: false, value: 0 },
      freeOnly: { enabled: false, value: '0' },
      requireChineseSubtitle: { enabled: false, value: '0' },
      includeKeywords: { enabled: false, value: '' },
      descriptionExcludeKeywords: { enabled: false, value: '' },
      excludeKeywords: { enabled: false, value: '' },
      resolutionWhitelist: { enabled: false, value: '' },
      resolutionPriority: { enabled: false, value: '' },
      preferredSize: { enabled: false, value: 0 }
    }),
    openFilterOverride: vi.fn(),
    saveFilterOverride: vi.fn(),
    searchDialogOpen: ref(false),
    searchDialogLoading: ref(false),
    searchDialogKeyword: ref(''),
    openSeasonSearch: vi.fn(),
    openEpisodeSearch: vi.fn(),
    confirmSearch: vi.fn(),
    toggleAutoSearch: vi.fn(),
    handleRefresh: vi.fn(),
    handlePause: vi.fn(),
    handleResume: vi.fn(),
    handleRemove: vi.fn(),
    handleDelete: vi.fn(),
    selectedIds: ref<number[]>([]),
    toggleSubSelect: vi.fn(),
    isSubSelected: vi.fn(() => false),
    handleBatchPause: vi.fn(),
    handleBatchResume: vi.fn(),
    episodeDetailOpen: ref(false),
    episodeDetailLoading: ref(false),
    episodeDetail: ref(null),
    resettingEpisode: ref(false),
    loadEpisodeDetail: vi.fn(),
    handleResetEpisode: vi.fn(),
    // 进度弹窗与每集明细里用到的展示函数。当前用例都不打开这两块，
    // 但漏了桩的话下次谁给 progress 塞了值就会炸在一个与他改动无关的地方
    episodeStateLabel: vi.fn(() => '缺失'),
    episodeStateColor: vi.fn(() => 'info'),
    qualityLabel: vi.fn(() => ''),
    upgradeStateHint: vi.fn(() => ''),
    seasonLabel: vi.fn(() => ''),
    episodeAirDate: vi.fn(() => ''),
    episodeUnaired: vi.fn(() => false),
    searchManualSelect: ref(false),
    isAllPageSelected: ref(false),
    toggleSelectAllPage: vi.fn(),
    searchAllMissingLoading: ref(false),
    handleSearchAllMissing: vi.fn(),
    searchAllMissingDone: ref(0),
    searchAllMissingTotal: ref(0),
    searchAllMissingAborted: ref(false),
    abortSearchAllMissing: vi.fn(),
    pickedSeasonEpisodeCount: ref(null),
    pickedSeasonCountLoading: ref(false),
    visibleMissingEpisodes: ref([]),
    missingHiddenCount: ref(0),
    expandMissing: vi.fn(),
    searchLogRejectedOnly: ref(false),
    visibleSearchLogs: ref([]),
    globalFilterHint: vi.fn(() => ''),
    clearFilterOverride: vi.fn(),
    filterOverrideCount: ref(0),
    candidateDialogOpen: ref(false),
    candidates: ref([]),
    pushingSelected: ref(false),
    pushSelectedCandidate: vi.fn(),
    formatSize: vi.fn(() => ''),
    ...overrides
  }
}

// vitest.setup.ts 已全局安装真实 Vuetify 插件，v-dialog/v-data-table 等组件按真实实现渲染，
// 不再需要像 Element Plus 时代那样手动 stub。

describe('PtSubscription 骨架屏', () => {
  it('首次加载（loading 且列表为空）只渲染骨架卡片，不渲染真实卡片', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([]),
      loading: ref(true)
    }))
    const wrapper = mount(PtSubscriptionPage)
    // 数量不写死：骨架张数改成按 useGridPageSize 量出的真实列数推算
    // （旧算法自己按 window.innerWidth 估，把 220px 的侧边栏整个漏掉了），
    // jsdom 下量不到布局会保持兜底列数，具体值由下面那条「3~12 张」的用例覆盖
    expect(wrapper.findAll('.item-card-skeleton').length).toBeGreaterThan(0)
    expect(wrapper.find('.item-card').exists()).toBe(false)
  })

  it('已有数据时重新查询（loading 且列表非空）不回退成骨架屏', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }]),
      loading: ref(true)
    }))
    const wrapper = mount(PtSubscriptionPage)
    expect(wrapper.find('.item-card-skeleton').exists()).toBe(false)
    expect(wrapper.find('.item-card').exists()).toBe(true)
  })

  it('骨架屏数量根据页面宽度动态变化（至少 3 张）', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([]),
      loading: ref(true)
    }))
    const wrapper = mount(PtSubscriptionPage)
    const count = wrapper.findAll('.item-card-skeleton').length
    expect(count).toBeGreaterThanOrEqual(3)
    expect(count).toBeLessThanOrEqual(12)
  })
})

describe('PtSubscription 批量操作', () => {
  it('selectionMode 为 false 时不渲染批量工具条和 checkbox', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
    }))
    const wrapper = mount(PtSubscriptionPage)
    expect(wrapper.find('.batch-toolbar').exists()).toBe(false)
    expect(wrapper.find('.item-card-checkbox').exists()).toBe(false)
  })

  it('selectionMode 为 true 时每张卡片都显示 checkbox', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
    }))
    const wrapper = mount(PtSubscriptionPage)
    expect(wrapper.find('.item-card-checkbox').exists()).toBe(true)
  })

  it('批量工具条展示已选数量', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1, 2, 3])
    }))
    const wrapper = mount(PtSubscriptionPage)
    expect(wrapper.find('.batch-toolbar').text()).toContain('已选 3 项')
  })

  it('点击批量暂停调用 handleBatchPause', async () => {
    const handleBatchPause = vi.fn()
    ;(usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1]),
      handleBatchPause
    }))
    const wrapper = mount(PtSubscriptionPage)
    await wrapper.find('.batch-pause-btn').trigger('click')
    expect(handleBatchPause).toHaveBeenCalled()
  })

  it('点击批量恢复调用 handleBatchResume', async () => {
    const handleBatchResume = vi.fn()
    ;(usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1]),
      handleBatchResume
    }))
    const wrapper = mount(PtSubscriptionPage)
    await wrapper.find('.batch-resume-btn').trigger('click')
    expect(handleBatchResume).toHaveBeenCalled()
  })

  it('点击批量删除调用 handleDelete（复用useTaskList现成批量删除逻辑）', async () => {
    const handleDelete = vi.fn()
    ;(usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1]),
      handleDelete
    }))
    const wrapper = mount(PtSubscriptionPage)
    await wrapper.find('.batch-delete-btn').trigger('click')
    expect(handleDelete).toHaveBeenCalled()
  })

  it('点击取消退出批量操作模式，隐藏工具条', async () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1])
    }))
    const wrapper = mount(PtSubscriptionPage)
    expect(wrapper.find('.batch-toolbar').exists()).toBe(true)
    await wrapper.find('.batch-cancel-btn').trigger('click')
    expect(wrapper.find('.batch-toolbar').exists()).toBe(false)
  })

  it('勾选订阅卡片调用 toggleSubSelect', async () => {
    const toggleSubSelect = vi.fn()
    ;(usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }]),
      toggleSubSelect
    }))
    const wrapper = mount(PtSubscriptionPage)
    await wrapper.find('.item-card-checkbox').trigger('click')
    expect(toggleSubSelect).toHaveBeenCalled()
  })

  it('批量模式下点击卡片调用 toggleSubSelect', async () => {
    const toggleSubSelect = vi.fn()
    ;(usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }]),
      toggleSubSelect
    }))
    const wrapper = mount(PtSubscriptionPage)
    await wrapper.find('.item-card').trigger('click')
    expect(toggleSubSelect).toHaveBeenCalled()
  })

  it('批量模式下卡片带有 selectable class', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
    }))
    const wrapper = mount(PtSubscriptionPage)
    expect(wrapper.find('.item-card').classes()).toContain('item-card--selectable')
  })

  it('非批量模式下卡片不带 selectable class', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(false),
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
    }))
    const wrapper = mount(PtSubscriptionPage)
    expect(wrapper.find('.item-card').classes()).not.toContain('item-card--selectable')
  })
})

describe('PtSubscription 按钮收纳', () => {
  it('卡片底部只留 2 个直接按钮：进度 / 下载记录', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
    }))
    const wrapper = mount(PtSubscriptionPage)
    const directButtons = wrapper.findAll('.card-footer > .v-btn:not(.more-actions-trigger)')
    expect(directButtons.map(b => b.text())).toEqual(['进度', '下载记录'])
  })

  it('删除收进「更多」，不再与「进度」平铺在一起', async () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
    }))
    const wrapper = mount(PtSubscriptionPage)
    const directButtonTexts = wrapper.findAll('.card-footer > .v-btn:not(.more-actions-trigger)').map(b => b.text())
    for (const hidden of ['删除', '暂停', '对账', '匹配日志', '过滤规则', '搜索补齐']) {
      expect(directButtonTexts).not.toContain(hidden)
    }
    // v-menu 已去掉 eager（每张卡片都提前渲染 4~7 个 list-item 是白渲染），
    // 下拉内容要点开才存在
    await wrapper.find('.more-actions-trigger').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    const dropdownItemTexts = body().findAll('.v-list-item').map(i => i.text()).filter(Boolean)
    expect(dropdownItemTexts).toEqual(['暂停', '搜索补齐', '对账', '匹配日志', '过滤规则', '删除'])
  })

  it('已暂停的订阅在「更多」里显示恢复而不是暂停', async () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', status: 'PAUSED', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
    }))
    const wrapper = mount(PtSubscriptionPage)
    await wrapper.find('.more-actions-trigger').trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    const dropdownItemTexts = body().findAll('.v-list-item').map(i => i.text()).filter(Boolean)
    expect(dropdownItemTexts).toContain('恢复')
    expect(dropdownItemTexts).not.toContain('暂停')
  })
})

describe('PtSubscription 卡片进度', () => {
  it('列表接口带回进度计数时，卡片直接显示「已入库/总数」，不必点开进度弹窗', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{
        id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1,
        totalEpisodes: 26, inLibraryCount: 12, inFlightCount: 2, missingCount: 12
      }])
    }))
    const wrapper = mount(PtSubscriptionPage)
    const text = wrapper.find('.sub-progress-text').text()
    expect(text).toContain('12/26')
    expect(text).toContain('在途 2')
  })

  it('没有进度计数的行不渲染进度条，也不显示成 0/0', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 26 }])
    }))
    const wrapper = mount(PtSubscriptionPage)
    expect(wrapper.find('.sub-progress').exists()).toBe(false)
  })

  it('配了过滤覆盖的订阅在卡片上有标记', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{
        id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12,
        filterOverride: '{"minSeeders":5}'
      }])
    }))
    const wrapper = mount(PtSubscriptionPage)
    expect(wrapper.find('.sub-flag').exists()).toBe(true)
  })

  it('filterOverride 是空 JSON 时不算有覆盖', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{
        id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12,
        filterOverride: '{}'
      }])
    }))
    const wrapper = mount(PtSubscriptionPage)
    expect(wrapper.find('.sub-flag').exists()).toBe(false)
  })
})

describe('PtSubscription 排序下拉', () => {
  it('切换排序下拉触发 handleQuery', async () => {
    const handleQuery = vi.fn()
    ;(usePtSubscription as any).mockReturnValue(baseComposable({ handleQuery }))
    const wrapper = mount(PtSubscriptionPage)
    // jsdom 下 VSelect 的浮层定位依赖真实布局几何信息，点击展开菜单在无头环境不可靠；
    // 直接对准 VSelect 组件实例派发 update:model-value，验证的是"排序变了就触发
    // handleQuery"这条业务逻辑本身，不依赖 Vuetify 浮层能否在 jsdom 下正确弹出。
    // 页面里不止一个 v-select（搜索面板里也有），必须按 .sort-select 类名精确定位，
    // 不能用 findComponent(VSelect) 直接拿第一个（会拿到搜索面板的 select）
    const select = wrapper.findComponent('.sort-select') as any
    expect(select.exists()).toBe(true)
    await select.vm.$emit('update:modelValue', 'lastMatchTime')
    expect(handleQuery).toHaveBeenCalled()
  })
})

describe('PtSubscription 网格里的空态与加载条', () => {
  /**
   * list.scss 用 `.card-grid > .v-empty-state / > .v-progress-linear` 让这两者横跨整行。
   * 选择器成立的前提是「它们确实是 .card-grid 的直接子元素，且根元素带这两个类名」——
   * 这条不成立的话样式静默失效（不报错，只是空态挤在左上角那一格里），所以钉在这里。
   */
  it('空态是 card-grid 的直接子元素，根元素类名为 v-empty-state', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([]),
      loading: ref(false)
    }))
    const wrapper = mount(PtSubscriptionPage)
    const empty = wrapper.find('.card-grid > .v-empty-state')
    expect(empty.exists()).toBe(true)
  })

  it('加载条是 card-grid 的直接子元素，根元素类名为 v-progress-linear', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }]),
      loading: ref(true)
    }))
    const wrapper = mount(PtSubscriptionPage)
    expect(wrapper.find('.card-grid > .v-progress-linear').exists()).toBe(true)
  })
})
