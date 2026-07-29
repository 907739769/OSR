import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref, reactive } from 'vue'

// 页面 <script setup> 里直接调用 useRouter()，测试环境没有安装 vue-router 插件，
// mock 掉整个模块避免路由相关报错。
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
  useRoute: () => ({ query: {} })
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
 * el-dialog/el-table/el-table-column 显式 stub 掉：这三个组件内部用 scoped slot
 * （el-table-column 的 #default="scope"）传行数据，测试环境没有注册真正的
 * Element Plus，未知组件会退化成普通 DOM 元素，普通元素遇到 scoped slot 对象会
 * 直接同步调用一次 slot 函数（不传参），导致 scope 是 undefined、scope.row 报错。
 * stub 之后这三个标签整体替换成空标记，跳过内部内容渲染，规避这个陷阱。
 */
function baseComposable(overrides: Record<string, any> = {}) {
  return {
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
      includeKeywords: { enabled: false, value: '' },
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
    selectionMode: ref(false),
    toggleSubSelect: vi.fn(),
    isSubSelected: vi.fn(() => false),
    handleBatchPause: vi.fn(),
    handleBatchResume: vi.fn(),
    ...overrides
  }
}

const mountOptions = {
  global: {
    stubs: {
      'el-dialog': true,
      'el-table': true,
      'el-table-column': true,
      // el-dropdown 未注册为真实组件时会退化成普通自定义元素渲染，
      // Vue 对普通元素的具名插槽（本文件模板里的 #dropdown）会被直接丢弃、只保留默认插槽，
      // 因此这里显式桩一个透传所有插槽的组件，让"更多"下拉菜单里的内容能在测试环境渲染出来。
      'el-dropdown': { template: '<div><slot /><slot name="dropdown" /></div>' }
    }
  }
}

describe('PtSubscription 骨架屏', () => {
  it('首次加载（loading 且列表为空）渲染 6 张骨架卡片，不渲染真实卡片', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([]),
      loading: ref(true)
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    expect(wrapper.findAll('.sub-card-skeleton').length).toBe(6)
    expect(wrapper.find('.sub-card').exists()).toBe(false)
  })

  it('已有数据时重新查询（loading 且列表非空）不回退成骨架屏', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }]),
      loading: ref(true)
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    expect(wrapper.find('.sub-card-skeleton').exists()).toBe(false)
    expect(wrapper.find('.sub-card').exists()).toBe(true)
  })

  it('骨架屏数量根据页面宽度动态变化（至少 3 张）', () => {
    ;(usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([]),
      loading: ref(true)
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    const count = wrapper.findAll('.sub-card-skeleton').length
    expect(count).toBeGreaterThanOrEqual(3)
    expect(count).toBeLessThanOrEqual(12)
  })
})

describe('PtSubscription 批量操作', () => {
  it('selectionMode 为 false 时不渲染批量工具条和 checkbox', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    expect(wrapper.find('.batch-toolbar').exists()).toBe(false)
    expect(wrapper.find('.sub-card-checkbox').exists()).toBe(false)
  })

  it('selectionMode 为 true 时每张卡片都显示 checkbox', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    expect(wrapper.find('.sub-card-checkbox').exists()).toBe(true)
  })

  it('批量工具条展示已选数量', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1, 2, 3])
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    expect(wrapper.find('.batch-toolbar').text()).toContain('已选 3 项')
  })

  it('点击批量暂停调用 handleBatchPause', async () => {
    const handleBatchPause = vi.fn()
    ;(usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1]),
      handleBatchPause
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
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
    const wrapper = mount(PtSubscriptionPage, mountOptions)
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
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    await wrapper.find('.batch-delete-btn').trigger('click')
    expect(handleDelete).toHaveBeenCalled()
  })

  it('点击取消退出批量操作模式，隐藏工具条', async () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1])
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
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
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    await wrapper.find('.sub-card-checkbox').trigger('change')
    expect(toggleSubSelect).toHaveBeenCalled()
  })

  it('批量模式下点击卡片调用 toggleSubSelect', async () => {
    const toggleSubSelect = vi.fn()
    ;(usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }]),
      toggleSubSelect
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    await wrapper.find('.sub-card').trigger('click')
    expect(toggleSubSelect).toHaveBeenCalled()
  })

  it('批量模式下卡片带有 selectable class', () => {
    ;(usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    expect(wrapper.find('.sub-card').classes()).toContain('selectable')
  })

  it('非批量模式下卡片不带 selectable class', () => {
    ;(usePtSubscription as any).mockReturnValue(baseComposable({
      selectionMode: ref(false),
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    expect(wrapper.find('.sub-card').classes()).not.toContain('selectable')
  })
})

describe('PtSubscription 按钮收纳', () => {
  it('sub-actions 只保留4个直接按钮：进度/下载记录/暂停或恢复/删除', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    const directButtons = wrapper.findAll('.sub-actions > el-button')
    const texts = directButtons.map(b => b.text())
    expect(texts).toEqual(['进度', '下载记录', '暂停', '删除'])
  })

  it('对账/匹配日志/过滤规则/搜索补齐收进更多下拉，不再是直接按钮', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', status: 'ACTIVE', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    const directButtonTexts = wrapper.findAll('.sub-actions > el-button').map(b => b.text())
    expect(directButtonTexts).not.toContain('对账')
    expect(directButtonTexts).not.toContain('匹配日志')
    expect(directButtonTexts).not.toContain('过滤规则')
    expect(directButtonTexts).not.toContain('搜索补齐')
    const dropdownItemTexts = wrapper.findAll('el-dropdown-item').map(i => i.text())
    expect(dropdownItemTexts).toEqual(['对账', '匹配日志', '过滤规则', '搜索补齐'])
  })

  it('已暂停的订阅显示恢复按钮而不是暂停按钮', () => {
    (usePtSubscription as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', status: 'PAUSED', mediaType: 'TV', season: 1, totalEpisodes: 12 }])
    }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    const texts = wrapper.findAll('.sub-actions > el-button').map(b => b.text())
    expect(texts).toContain('恢复')
    expect(texts).not.toContain('暂停')
  })
})

describe('PtSubscription 排序下拉', () => {
  it('切换排序下拉触发 handleQuery', async () => {
    const handleQuery = vi.fn()
    ;(usePtSubscription as any).mockReturnValue(baseComposable({ handleQuery }))
    const wrapper = mount(PtSubscriptionPage, mountOptions)
    await wrapper.find('.sort-select').trigger('change')
    expect(handleQuery).toHaveBeenCalled()
  })
})
