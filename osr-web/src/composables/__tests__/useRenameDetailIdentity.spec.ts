import { describe, it, expect, vi, beforeEach } from 'vitest'
import { defineComponent } from 'vue'
import { mount } from '@vue/test-utils'
import { useRenameDetailList } from '../useRenameDetailList'
import request from '@/api/request'

vi.mock('@/api/request', () => ({ default: {
  get: vi.fn().mockResolvedValue({ records: [], total: 0 }),
  post: vi.fn().mockResolvedValue({})
} }))
vi.mock('../useMessage', () => ({ message: { success: vi.fn(), error: vi.fn() } }))
vi.mock('../useConfirm', () => ({ confirm: vi.fn() }))

function setup() {
  let state!: ReturnType<typeof useRenameDetailList>
  const wrapper = mount(defineComponent({
    setup() { state = useRenameDetailList(); return () => null }
  }))
  return { state, wrapper }
}

describe('重命名纠正作品身份', () => {
  beforeEach(() => { vi.clearAllMocks() })

  it('不把原来自动识别的错误 ID 默认为人工绑定；提交使用明确选择的 ID 和类型', async () => {
    const { state, wrapper } = setup()
    state.handleRetryOne({ id: 1, title: '错片', tmdbId: '999', mediaType: 'movie' })
    expect(state.retryForm.tmdbId).toBe('')
    state.retryForm.tmdbId = ' 79481 '
    state.retryForm.mediaType = 'tv'
    state.retryForm.season = '3'
    await state.handleRetrySubmit()
    expect(request.post).toHaveBeenCalledWith('/openliststrm/rename-details/execute', null, {
      params: expect.objectContaining({ ids: '1', tmdbId: '79481', mediaType: 'tv', season: '3' })
    })
    wrapper.unmount()
  })

  it('批量纠正传递同一作品身份，重新打开弹窗时清掉上次输入', async () => {
    const { state, wrapper } = setup()
    state.selectedIds.value = [1, 2]
    state.handleBatchExecute()
    state.batchForm.tmdbId = '123'
    state.batchForm.mediaType = 'movie'
    await state.handleBatchSubmit()
    expect(request.post).toHaveBeenCalledWith('/openliststrm/rename-details/execute', null, {
      params: { ids: '1,2', tmdbId: '123', mediaType: 'movie' }
    })
    state.handleBatchExecute()
    expect(state.batchForm.tmdbId).toBe('')
    wrapper.unmount()
  })
})
