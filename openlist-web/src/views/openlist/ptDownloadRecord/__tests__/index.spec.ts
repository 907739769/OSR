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
