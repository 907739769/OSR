import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref, reactive } from 'vue'

// 页面 <script setup> 里直接调用 useRouter()，测试环境没有安装 vue-router 插件，
// mock 掉整个模块避免路由相关报错。
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() })
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
    ...overrides
  }
}

const mountOptions = {
  global: { stubs: { 'el-dialog': true, 'el-table': true, 'el-table-column': true } }
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
})
