import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref, reactive, computed } from 'vue'

// usePtDownloadRecord 内部会发起真实请求 (getList 在 setup 阶段自动调用)，
// 组件测试只关心模板渲染，因此整体 mock 掉这个 composable。
vi.mock('@/composables/usePtDownloadRecord', () => ({
  usePtDownloadRecord: vi.fn()
}))

import { usePtDownloadRecord } from '@/composables/usePtDownloadRecord'
import PtDownloadRecordPage from '../index.vue'

// composable 返回值的最小可用默认值，测试用例按需覆盖字段
function baseComposable(overrides: Record<string, any> = {}) {
  return {
    taskList: ref([]),
    loading: ref(false),
    total: ref(0),
    queryParams: reactive({ pageNum: 1, pageSize: 10, title: undefined, state: undefined, subId: undefined }),
    getList: vi.fn(),
    handleQuery: vi.fn(),
    resetQuery: vi.fn(),
    queryRef: ref(),
    retryingIds: reactive(new Set<number>()),
    handleRetry: vi.fn(),
    selectionMode: ref(false),
    toggleSelectionMode: vi.fn(),
    selectedIds: ref<number[]>([]),
    toggleRecordSelect: vi.fn(),
    handleCardClick: vi.fn(),
    isAllPageSelected: computed(() => false),
    isIndeterminate: computed(() => false),
    toggleSelectAllPage: vi.fn(),
    retryableSelectedIds: computed(() => [] as number[]),
    handleBatchRetry: vi.fn(),
    handleBatchBlacklistGuid: vi.fn(),
    handleBatchBlacklistReleaseGroup: vi.fn(),
    blacklistingIds: reactive(new Set<number>()),
    handleBlacklistGuid: vi.fn(),
    handleBlacklistReleaseGroup: vi.fn(),
    ...overrides
  }
}

describe('prototype', () => {
  it('能正常挂载并渲染页面根节点', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable())
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.page-container').exists()).toBe(true)
  })
})

describe('failReasonCode 标签', () => {
  it('有 failReasonCode 时渲染对应的分类标签文案', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'FAILED', failReason: 'boom', failReasonCode: 'ZOMBIE_TIMEOUT' }])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.record-fail .v-chip').exists()).toBe(true)
    expect(wrapper.text()).toContain('下载超时')
  })

  it('没有 failReasonCode 时不渲染分类标签（兼容历史数据）', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'FAILED', failReason: 'boom' }])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.record-fail .v-chip').exists()).toBe(false)
  })
})

describe('PtDownloadRecord 失败卡片视觉强化', () => {
  it('FAILED 状态的卡片带有 item-card--failed 类', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'FAILED', failReason: 'boom' }])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    const card = wrapper.find('.item-card')
    expect(card.classes()).toContain('item-card--failed')
  })

  it('非 FAILED 状态的卡片不带 item-card--failed 类', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 2, title: 'B', state: 'COMPLETED' }])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    const card = wrapper.find('.item-card')
    expect(card.classes()).not.toContain('item-card--failed')
  })
})

describe('PtDownloadRecord 骨架屏', () => {
  it('首次加载（loading 且列表为空）渲染 6 张骨架卡片，不渲染真实卡片', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([]),
      loading: ref(true)
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.findAll('.item-card-skeleton').length).toBe(6)
    expect(wrapper.find('.item-card').exists()).toBe(false)
  })

  it('已有数据时重新查询（loading 且列表非空）不回退成骨架屏', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'COMPLETED' }]),
      loading: ref(true)
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.item-card-skeleton').exists()).toBe(false)
    expect(wrapper.find('.item-card').exists()).toBe(true)
  })

  it('骨架屏数量根据页面宽度动态变化（至少 3 张）', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([]),
      loading: ref(true)
    }))
    const wrapper = mount(PtDownloadRecordPage)
    const count = wrapper.findAll('.item-card-skeleton').length
    expect(count).toBeGreaterThanOrEqual(3)
    expect(count).toBeLessThanOrEqual(12)
  })
})

describe('PtDownloadRecord 批量重试', () => {
  it('selectionMode 为 false 时不渲染批量工具条和 checkbox', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'FAILED', failReason: 'boom' }])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.batch-toolbar').exists()).toBe(false)
    expect(wrapper.find('.item-card-checkbox').exists()).toBe(false)
  })

  // 勾选放开到全部状态：拉黑对任意记录都成立，只有重试限失败记录，
  // 那道限制改由「批量重试」按钮的生效条数承担
  it('selectionMode 为 true 时所有卡片都显示 checkbox，不再只有 FAILED', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([
        { id: 1, title: 'A', state: 'FAILED', failReason: 'boom' },
        { id: 2, title: 'B', state: 'COMPLETED' }
      ])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.findAll('.item-card-checkbox').length).toBe(2)
  })

  it('批量工具条有全选本页勾选框，勾上时调用 toggleSelectAllPage', async () => {
    const toggleSelectAllPage = vi.fn()
    ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      toggleSelectAllPage
    }))
    const wrapper = mount(PtDownloadRecordPage)
    const selectAll = wrapper.find('.batch-toolbar .select-all-checkbox input')
    expect(selectAll.exists()).toBe(true)
    await selectAll.setValue(true)
    expect(toggleSelectAllPage).toHaveBeenCalledWith(true)
  })

  it('选中项里没有失败记录时批量重试按钮禁用，有则标出生效条数', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([2]),
      retryableSelectedIds: computed(() => [] as number[])
    }))
    expect(mount(PtDownloadRecordPage).find('.batch-retry-btn').attributes('disabled')).toBeDefined()

    ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1, 2]),
      retryableSelectedIds: computed(() => [1])
    }))
    expect(mount(PtDownloadRecordPage).find('.batch-retry-btn').text()).toContain('1 条失败')
  })

  it('点击批量拉黑按钮调用对应处理函数', async () => {
    const handleBatchBlacklistGuid = vi.fn()
    const handleBatchBlacklistReleaseGroup = vi.fn()
    ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1]),
      handleBatchBlacklistGuid,
      handleBatchBlacklistReleaseGroup
    }))
    const wrapper = mount(PtDownloadRecordPage)
    await wrapper.find('.batch-blacklist-guid-btn').trigger('click')
    await wrapper.find('.batch-blacklist-group-btn').trigger('click')
    expect(handleBatchBlacklistGuid).toHaveBeenCalled()
    expect(handleBatchBlacklistReleaseGroup).toHaveBeenCalled()
  })

  it('批量工具条展示已选数量', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1, 2])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.batch-toolbar').text()).toContain('已选 2 项')
  })

  it('点击批量重试按钮调用 handleBatchRetry', async () => {
    const handleBatchRetry = vi.fn()
    ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1]),
      retryableSelectedIds: computed(() => [1]),
      handleBatchRetry
    }))
    const wrapper = mount(PtDownloadRecordPage)
    await wrapper.find('.batch-retry-btn').trigger('click')
    expect(handleBatchRetry).toHaveBeenCalled()
  })

  it('点击取消按钮调用 toggleSelectionMode 退出批量操作模式', async () => {
    const toggleSelectionMode = vi.fn()
    ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1]),
      toggleSelectionMode
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.batch-toolbar').exists()).toBe(true)
    await wrapper.find('.batch-cancel-btn').trigger('click')
    expect(toggleSelectionMode).toHaveBeenCalled()
  })

  it('勾选下载记录调用 toggleRecordSelect', async () => {
    const toggleRecordSelect = vi.fn()
    ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([{ id: 1, title: 'A', state: 'FAILED', failReason: 'boom' }]),
      toggleRecordSelect
    }))
    const wrapper = mount(PtDownloadRecordPage)
    await wrapper.find('.item-card-checkbox').trigger('click')
    expect(toggleRecordSelect).toHaveBeenCalled()
  })

  it('批量模式下点击卡片调用 handleCardClick（非失败记录同样可选）', async () => {
    const handleCardClick = vi.fn()
    ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([{ id: 1, title: 'A', state: 'COMPLETED' }]),
      handleCardClick
    }))
    const wrapper = mount(PtDownloadRecordPage)
    await wrapper.find('.item-card').trigger('click')
    expect(handleCardClick).toHaveBeenCalled()
  })

  it('批量模式下所有卡片都带 item-card--selectable class', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([{ id: 1, title: 'A', state: 'COMPLETED' }])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.item-card').classes()).toContain('item-card--selectable')
  })

  it('非批量模式下卡片不带 item-card--selectable class', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'FAILED', failReason: 'boom' }])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.item-card').classes()).not.toContain('item-card--selectable')
  })
})

describe('PtDownloadRecord 拉黑操作', () => {
  it('非 FAILED 状态的卡片也显示拉黑按钮，不显示立即重试按钮', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'COMPLETED' }])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.blacklist-guid-btn').exists()).toBe(true)
    expect(wrapper.find('.blacklist-group-btn').exists()).toBe(true)
    expect(wrapper.text()).not.toContain('立即重试')
  })

  it('点击拉黑该种子按钮调用 handleBlacklistGuid', async () => {
    const handleBlacklistGuid = vi.fn()
    ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'COMPLETED' }]),
      handleBlacklistGuid
    }))
    const wrapper = mount(PtDownloadRecordPage)
    await wrapper.find('.blacklist-guid-btn').trigger('click')
    expect(handleBlacklistGuid).toHaveBeenCalled()
  })

  it('点击拉黑该发布组按钮调用 handleBlacklistReleaseGroup', async () => {
    const handleBlacklistReleaseGroup = vi.fn()
    ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'COMPLETED' }]),
      handleBlacklistReleaseGroup
    }))
    const wrapper = mount(PtDownloadRecordPage)
    await wrapper.find('.blacklist-group-btn').trigger('click')
    expect(handleBlacklistReleaseGroup).toHaveBeenCalled()
  })
})
