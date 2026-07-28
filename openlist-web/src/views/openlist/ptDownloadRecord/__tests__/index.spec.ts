import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref, reactive } from 'vue'

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
    selectedIds: ref<number[]>([]),
    toggleRecordSelect: vi.fn(),
    handleBatchRetry: vi.fn(),
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
    expect(wrapper.find('.record-fail el-tag').exists()).toBe(true)
    expect(wrapper.text()).toContain('下载超时')
  })

  it('没有 failReasonCode 时不渲染分类标签（兼容历史数据）', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'FAILED', failReason: 'boom' }])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.record-fail el-tag').exists()).toBe(false)
  })
})

describe('PtDownloadRecord 失败卡片视觉强化', () => {
  it('FAILED 状态的卡片带有 record-card--failed 类', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'FAILED', failReason: 'boom' }])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    const card = wrapper.find('.record-card')
    expect(card.classes()).toContain('record-card--failed')
  })

  it('非 FAILED 状态的卡片不带 record-card--failed 类', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 2, title: 'B', state: 'COMPLETED' }])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    const card = wrapper.find('.record-card')
    expect(card.classes()).not.toContain('record-card--failed')
  })
})

describe('PtDownloadRecord 骨架屏', () => {
  it('首次加载（loading 且列表为空）渲染 6 张骨架卡片，不渲染真实卡片', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([]),
      loading: ref(true)
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.findAll('.record-card-skeleton').length).toBe(6)
    expect(wrapper.find('.record-card').exists()).toBe(false)
  })

  it('已有数据时重新查询（loading 且列表非空）不回退成骨架屏', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([{ id: 1, title: 'A', state: 'COMPLETED' }]),
      loading: ref(true)
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.record-card-skeleton').exists()).toBe(false)
    expect(wrapper.find('.record-card').exists()).toBe(true)
  })

  it('骨架屏数量根据页面宽度动态变化（至少 3 张）', () => {
    ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
      taskList: ref([]),
      loading: ref(true)
    }))
    const wrapper = mount(PtDownloadRecordPage)
    const count = wrapper.findAll('.record-card-skeleton').length
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
    expect(wrapper.find('.record-card-checkbox').exists()).toBe(false)
  })

  it('selectionMode 为 true 时仅 FAILED 卡片显示 checkbox', () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([
        { id: 1, title: 'A', state: 'FAILED', failReason: 'boom' },
        { id: 2, title: 'B', state: 'COMPLETED' }
      ])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.findAll('.record-card-checkbox').length).toBe(1)
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
      handleBatchRetry
    }))
    const wrapper = mount(PtDownloadRecordPage)
    await wrapper.find('.batch-retry-btn').trigger('click')
    expect(handleBatchRetry).toHaveBeenCalled()
  })

  it('点击取消按钮退出批量操作模式，隐藏工具条', async () => {
    (usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      selectedIds: ref([1])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.batch-toolbar').exists()).toBe(true)
    await wrapper.find('.batch-cancel-btn').trigger('click')
    expect(wrapper.find('.batch-toolbar').exists()).toBe(false)
  })

  it('勾选下载记录调用 toggleRecordSelect', async () => {
    const toggleRecordSelect = vi.fn()
    ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([{ id: 1, title: 'A', state: 'FAILED', failReason: 'boom' }]),
      toggleRecordSelect
    }))
    const wrapper = mount(PtDownloadRecordPage)
    await wrapper.find('.record-card-checkbox').trigger('change')
    expect(toggleRecordSelect).toHaveBeenCalled()
  })

  it('批量模式下点击 FAILED 卡片调用 toggleRecordSelect', async () => {
    const toggleRecordSelect = vi.fn()
    ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([{ id: 1, title: 'A', state: 'FAILED', failReason: 'boom' }]),
      toggleRecordSelect
    }))
    const wrapper = mount(PtDownloadRecordPage)
    await wrapper.find('.record-card').trigger('click')
    expect(toggleRecordSelect).toHaveBeenCalled()
  })

  it('批量模式下 FAILED 卡片带有 selectable class', () => {
    ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([{ id: 1, title: 'A', state: 'FAILED', failReason: 'boom' }])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.record-card').classes()).toContain('selectable')
  })

  it('批量模式下非 FAILED 卡片不带 selectable class', () => {
    ;(usePtDownloadRecord as any).mockReturnValue(baseComposable({
      selectionMode: ref(true),
      taskList: ref([{ id: 1, title: 'A', state: 'COMPLETED' }])
    }))
    const wrapper = mount(PtDownloadRecordPage)
    expect(wrapper.find('.record-card').classes()).not.toContain('selectable')
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
