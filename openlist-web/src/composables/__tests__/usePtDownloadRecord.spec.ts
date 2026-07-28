import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ElMessage, ElMessageBox } from 'element-plus'

// usePtDownloadRecord 内部调用 useRoute()（读 subId query 参数），
// 测试环境没有安装 vue-router 插件，mock 掉整个模块。
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: {} })
}))

vi.mock('../usePtStatusSocket', () => ({
  usePtStatusSocket: vi.fn()
}))

// getList 在 setup 阶段同步调用，mock 掉整个 API 模块避免真实网络请求。
vi.mock('@/api/openlist/ptDownloadRecord', () => ({
  getPtDownloadRecordListApi: vi.fn().mockResolvedValue({ records: [], total: 0 }),
  retryPtDownloadRecordApi: vi.fn(),
  batchRetryPtDownloadRecordApi: vi.fn()
}))

import { usePtDownloadRecord } from '../usePtDownloadRecord'
import { batchRetryPtDownloadRecordApi, getPtDownloadRecordListApi } from '@/api/openlist/ptDownloadRecord'
import { usePtStatusSocket } from '../usePtStatusSocket'

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

describe('usePtDownloadRecord 实时状态推送', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtDownloadRecordListApi as any).mockResolvedValue({ records: [], total: 0 })
    ;(usePtStatusSocket as any).mockReturnValue({ connect: vi.fn(), disconnect: vi.fn() })
  })

  it('收到 download 事件后原地更新对应记录的状态/进度/失败原因', () => {
    const composable = usePtDownloadRecord()
    composable.taskList.value = [{ id: 1, state: 'PUSHED', progress: null, failReason: null }]

    const handlers = (usePtStatusSocket as any).mock.calls[0][0]
    handlers.onDownload({ type: 'download', downloadId: 1, subId: 5, episode: 1, state: 'DOWNLOADING', progress: 0.6 })

    expect(composable.taskList.value[0].state).toBe('DOWNLOADING')
    expect(composable.taskList.value[0].progress).toBe(0.6)
  })

  it('找不到对应记录时静默忽略，不抛异常', () => {
    const composable = usePtDownloadRecord()
    composable.taskList.value = [{ id: 1, state: 'PUSHED' }]

    const handlers = (usePtStatusSocket as any).mock.calls[0][0]
    expect(() => handlers.onDownload({ type: 'download', downloadId: 999, subId: 5, episode: 1, state: 'FAILED', failReason: '超时' })).not.toThrow()
  })
})
