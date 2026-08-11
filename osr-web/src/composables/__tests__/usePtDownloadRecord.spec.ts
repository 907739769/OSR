import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { message } from '@/composables/useMessage'
import { confirm } from '@/composables/useConfirm'

vi.mock('@/composables/useMessage', () => ({
  message: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn()
  }
}))

vi.mock('@/composables/useConfirm', () => ({
  confirm: vi.fn()
}))

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
  batchRetryPtDownloadRecordApi: vi.fn(),
  blacklistGuidApi: vi.fn(),
  blacklistReleaseGroupApi: vi.fn(),
  batchBlacklistGuidApi: vi.fn(),
  batchBlacklistReleaseGroupApi: vi.fn()
}))

import { usePtDownloadRecord } from '../usePtDownloadRecord'
import {
  batchRetryPtDownloadRecordApi, getPtDownloadRecordListApi,
  batchBlacklistGuidApi, batchBlacklistReleaseGroupApi
} from '@/api/openlist/ptDownloadRecord'
import { usePtStatusSocket } from '../usePtStatusSocket'

describe('usePtDownloadRecord 的批量重试', () => {
  let confirmSpy: any
  let successSpy: any

  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtDownloadRecordListApi as any).mockResolvedValue({ records: [], total: 0 })
    confirmSpy = confirm as any
    confirmSpy.mockResolvedValue(undefined)
    successSpy = message.success as any
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  /** 勾选放开到全部记录，重试只对 FAILED 生效，所以夹具要同时给出两种状态 */
  const mixedList = [
    { id: 1, state: 'FAILED' },
    { id: 2, state: 'FAILED' },
    { id: 3, state: 'COMPLETED' }
  ]

  it('没有选中项时不发起确认框也不调用接口', async () => {
    const composable = usePtDownloadRecord()
    composable.taskList.value = mixedList
    composable.selectedIds.value = []

    await composable.handleBatchRetry()

    expect(confirmSpy).not.toHaveBeenCalled()
    expect(batchRetryPtDownloadRecordApi).not.toHaveBeenCalled()
  })

  it('选中项里没有失败记录时只给提示，不发起确认框也不调用接口', async () => {
    const composable = usePtDownloadRecord()
    composable.taskList.value = mixedList
    composable.selectedIds.value = [3]

    await composable.handleBatchRetry()

    expect(message.warning).toHaveBeenCalledWith('选中的记录里没有失败记录，只有失败记录才能重试')
    expect(confirmSpy).not.toHaveBeenCalled()
    expect(batchRetryPtDownloadRecordApi).not.toHaveBeenCalled()
  })

  it('用户取消确认框时不调用批量重试接口', async () => {
    confirmSpy.mockRejectedValue('cancel')
    const composable = usePtDownloadRecord()
    composable.taskList.value = mixedList
    composable.selectedIds.value = [1]

    await composable.handleBatchRetry()

    expect(batchRetryPtDownloadRecordApi).not.toHaveBeenCalled()
  })

  it('只把选中项里的失败记录送去重试，非失败记录不进请求', async () => {
    (batchRetryPtDownloadRecordApi as any).mockResolvedValue({ total: 2, pushedCount: 1, skippedCount: 1 })
    const composable = usePtDownloadRecord()
    composable.taskList.value = mixedList
    composable.selectedIds.value = [1, 2, 3]

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

describe('usePtDownloadRecord 的批量拉黑', () => {
  let confirmSpy: any

  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtDownloadRecordListApi as any).mockResolvedValue({ records: [], total: 0 })
    confirmSpy = confirm as any
    confirmSpy.mockResolvedValue(undefined)
  })

  it('批量拉黑种子对任意状态的选中记录都成立，确认后清空选中', async () => {
    (batchBlacklistGuidApi as any).mockResolvedValue({ total: 2, addedCount: 2, duplicateCount: 0, failedCount: 0 })
    const composable = usePtDownloadRecord()
    composable.taskList.value = [{ id: 1, state: 'COMPLETED' }, { id: 2, state: 'PUSHED' }]
    composable.selectedIds.value = [1, 2]

    await composable.handleBatchBlacklistGuid()

    expect(batchBlacklistGuidApi).toHaveBeenCalledWith([1, 2])
    expect(message.success).toHaveBeenCalledWith('已拉黑 2 项')
    expect(composable.selectedIds.value).toEqual([])
  })

  it('提示里带上已在黑名单中与未能拉黑的条数', async () => {
    (batchBlacklistReleaseGroupApi as any).mockResolvedValue({ total: 3, addedCount: 1, duplicateCount: 1, failedCount: 1 })
    const composable = usePtDownloadRecord()
    composable.selectedIds.value = [1, 2, 3]

    await composable.handleBatchBlacklistReleaseGroup()

    expect(message.success).toHaveBeenCalledWith('已拉黑 1 项，1 项已在黑名单中，1 项未能拉黑')
  })

  it('用户取消确认框时不调用批量拉黑接口', async () => {
    confirmSpy.mockRejectedValue('cancel')
    const composable = usePtDownloadRecord()
    composable.selectedIds.value = [1]

    await composable.handleBatchBlacklistGuid()

    expect(batchBlacklistGuidApi).not.toHaveBeenCalled()
  })

  it('没有选中项时不发起确认框', async () => {
    const composable = usePtDownloadRecord()
    composable.selectedIds.value = []

    await composable.handleBatchBlacklistGuid()

    expect(confirmSpy).not.toHaveBeenCalled()
    expect(batchBlacklistGuidApi).not.toHaveBeenCalled()
  })
})

describe('usePtDownloadRecord 的全选本页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtDownloadRecordListApi as any).mockResolvedValue({ records: [], total: 0 })
  })

  it('全选本页把当前页所有记录（含非失败记录）加入选中', () => {
    const composable = usePtDownloadRecord()
    composable.taskList.value = [{ id: 1, state: 'FAILED' }, { id: 2, state: 'COMPLETED' }]

    composable.toggleSelectAllPage(true)

    expect(composable.selectedIds.value).toEqual([1, 2])
    expect(composable.isAllPageSelected.value).toBe(true)
    expect(composable.isIndeterminate.value).toBe(false)
  })

  it('只选中一部分时是半选态', () => {
    const composable = usePtDownloadRecord()
    composable.taskList.value = [{ id: 1, state: 'FAILED' }, { id: 2, state: 'COMPLETED' }]
    composable.selectedIds.value = [1]

    expect(composable.isAllPageSelected.value).toBe(false)
    expect(composable.isIndeterminate.value).toBe(true)
  })

  it('退出批量模式时清空选中', () => {
    const composable = usePtDownloadRecord()
    composable.taskList.value = [{ id: 1, state: 'FAILED' }]
    composable.toggleSelectionMode()
    composable.selectedIds.value = [1]

    composable.toggleSelectionMode()

    expect(composable.selectionMode.value).toBe(false)
    expect(composable.selectedIds.value).toEqual([])
  })
})
