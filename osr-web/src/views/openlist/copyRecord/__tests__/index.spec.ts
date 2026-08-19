import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { ref, reactive, computed } from 'vue'

/**
 * PC 表格页的选中反馈。
 *
 * 改造前这里什么都没有：勾中一行，页面上唯一的变化是三个批量按钮由灰变亮，
 * 全页搜不到「已选」二字；而 selectedRows 是页面局部 ref、翻页不清空，
 * 于是「批量删除记录」可能作用在已经翻过去、看不见的那些行上。
 * 移动端反倒一直是对的（批量条明确显示条数）。
 *
 * 同页的其它逻辑由 e2e 覆盖，这里只钉住这三条：没选中不出条、选中出条并显示条数、
 * 「清空选择」能把表格 model 与 composable 侧的派生态一起清掉。
 */

vi.mock('@/composables/useCopyRecord', () => ({ useCopyRecord: vi.fn() }))

import { useCopyRecord } from '@/composables/useCopyRecord'
import CopyRecordPage from '../index.vue'

function baseComposable(overrides: Record<string, any> = {}) {
  return {
    recordList: ref([{ copyId: 1, copySrcFileName: 'a.mkv', copyDstFileName: 'a.mkv', copyStatus: '3' }]),
    loading: ref(false),
    total: ref(1),
    queryParams: reactive({ pageNum: 1, pageSize: 10 }),
    getList: vi.fn(),
    queryRef: ref(),
    dateRange: ref([]),
    dateStart: ref(''),
    dateEnd: ref(''),
    handleQuery: vi.fn(),
    resetQuery: vi.fn(),
    multiple: computed(() => true),
    handleSelectionChange: vi.fn(),
    handleRetryOne: vi.fn(),
    handleBatchRetry: vi.fn(),
    handleDeleteOne: vi.fn(),
    handleBatchDelete: vi.fn(),
    handleRemoveNetDiskOne: vi.fn(),
    handleBatchRemoveNetDisk: vi.fn(),
    getCopyStatusText: () => '成功',
    getCopyStatusType: () => 'success',
    ...overrides
  }
}

describe('CopyRecord 选中反馈', () => {
  it('没有选中时不出现批量条', () => {
    (useCopyRecord as any).mockReturnValue(baseComposable())
    const wrapper = mount(CopyRecordPage)
    expect(wrapper.find('.batch-toolbar').exists()).toBe(false)
  })

  it('选中后出现批量条并显示条数', async () => {
    (useCopyRecord as any).mockReturnValue(baseComposable())
    const wrapper = mount(CopyRecordPage)
    ;(wrapper.vm as any).selectedRows = [{ copyId: 1 }, { copyId: 2 }]
    await wrapper.vm.$nextTick()

    const bar = wrapper.find('.batch-toolbar')
    expect(bar.exists()).toBe(true)
    expect(bar.text()).toContain('已选 2 项')
  })

  it('「清空选择」同时清掉表格 model 与 composable 侧的派生态', async () => {
    const composable = baseComposable()
    ;(useCopyRecord as any).mockReturnValue(composable)
    const wrapper = mount(CopyRecordPage)
    ;(wrapper.vm as any).selectedRows = [{ copyId: 1 }]
    await wrapper.vm.$nextTick()

    await wrapper.find('.batch-clear-btn').trigger('click')
    await wrapper.vm.$nextTick()

    expect(wrapper.find('.batch-toolbar').exists()).toBe(false)
    // 漏掉这一句就是「条没了、批量按钮还亮着」
    expect(composable.handleSelectionChange).toHaveBeenLastCalledWith([])
  })
})
