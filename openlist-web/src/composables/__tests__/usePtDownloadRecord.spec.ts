import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ElMessage, ElMessageBox } from 'element-plus'

// usePtDownloadRecord 内部调用 useRoute()（读 subId query 参数），
// 测试环境没有安装 vue-router 插件，mock 掉整个模块。
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} })
}))

// getList 在 setup 阶段同步调用，mock 掉整个 API 模块避免真实网络请求。
vi.mock('@/api/openlist/ptDownloadRecord', () => ({
  getPtDownloadRecordListApi: vi.fn().mockResolvedValue({ records: [], total: 0 }),
  retryPtDownloadRecordApi: vi.fn(),
  batchRetryPtDownloadRecordApi: vi.fn()
}))

import { usePtDownloadRecord } from '../usePtDownloadRecord'
import { batchRetryPtDownloadRecordApi, getPtDownloadRecordListApi } from '@/api/openlist/ptDownloadRecord'

describe('usePtDownloadRecord 的批量重试', () => {
  let confirmSpy: any
  let successSpy: any

  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtDownloadRecordListApi as any).mockResolvedValue({ records: [], total: 0 })
    confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)
    successSpy = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({}) as any)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('没有选中项时不发起确认框也不调用接口', async () => {
    const composable = usePtDownloadRecord()
    composable.selectedIds.value = []

    await composable.handleBatchRetry()

    expect(confirmSpy).not.toHaveBeenCalled()
    expect(batchRetryPtDownloadRecordApi).not.toHaveBeenCalled()
  })

  it('用户取消确认框时不调用批量重试接口', async () => {
    confirmSpy.mockRejectedValue('cancel')
    const composable = usePtDownloadRecord()
    composable.selectedIds.value = [1]

    await composable.handleBatchRetry()

    expect(batchRetryPtDownloadRecordApi).not.toHaveBeenCalled()
  })

  it('有选中项时确认后调用批量重试接口并提示结果，随后清空选中并刷新列表', async () => {
    (batchRetryPtDownloadRecordApi as any).mockResolvedValue({ total: 2, pushedCount: 1, skippedCount: 1 })
    const composable = usePtDownloadRecord()
    composable.selectedIds.value = [1, 2]

    await composable.handleBatchRetry()

    expect(confirmSpy).toHaveBeenCalled()
    expect(batchRetryPtDownloadRecordApi).toHaveBeenCalledWith([1, 2])
    expect(successSpy).toHaveBeenCalledWith('已重新推送 1 条，1 条未搜到或已跳过')
    expect(composable.selectedIds.value).toEqual([])
  })

  it('toggleRecordSelect 在未选中时加入选中，已选中时移除', () => {
    const composable = usePtDownloadRecord()
    composable.toggleRecordSelect({ id: 5 })
    expect(composable.selectedIds.value).toEqual([5])
    composable.toggleRecordSelect({ id: 5 })
    expect(composable.selectedIds.value).toEqual([])
  })
})
