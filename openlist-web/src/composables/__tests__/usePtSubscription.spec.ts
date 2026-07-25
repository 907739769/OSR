import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { ElMessage, ElMessageBox } from 'element-plus'

// base.getList() 在 setup 阶段同步调用，mock 掉整个 API 模块避免真实网络请求。
vi.mock('@/api/openlist/ptSubscription', () => ({
  getPtSubscriptionListApi: vi.fn().mockResolvedValue({ records: [], total: 0 }),
  addPtSubscriptionApi: vi.fn(),
  updatePtSubscriptionApi: vi.fn(),
  deletePtSubscriptionApi: vi.fn(),
  tmdbSearchApi: vi.fn(),
  subscribeApi: vi.fn(),
  getSubscriptionProgressApi: vi.fn(),
  refreshSubscriptionApi: vi.fn(),
  pauseSubscriptionApi: vi.fn(),
  resumeSubscriptionApi: vi.fn(),
  searchSupplementApi: vi.fn(),
  getSubscriptionSearchLogsApi: vi.fn(),
  batchPauseSubscriptionApi: vi.fn(),
  batchResumeSubscriptionApi: vi.fn(),
  batchDeletePtSubscriptionApi: vi.fn()
}))

import { usePtSubscription } from '../usePtSubscription'
import {
  getPtSubscriptionListApi,
  batchPauseSubscriptionApi,
  batchResumeSubscriptionApi
} from '@/api/openlist/ptSubscription'

describe('usePtSubscription 的批量暂停/恢复', () => {
  let confirmSpy: any
  let successSpy: any

  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtSubscriptionListApi as any).mockResolvedValue({ records: [], total: 0 })
    confirmSpy = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as any)
    successSpy = vi.spyOn(ElMessage, 'success').mockImplementation(() => ({}) as any)
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('没有选中项时批量暂停不发起确认框', async () => {
    const composable = usePtSubscription()

    await composable.handleBatchPause()

    expect(confirmSpy).not.toHaveBeenCalled()
    expect(batchPauseSubscriptionApi).not.toHaveBeenCalled()
  })

  it('有选中项时批量暂停确认后调用接口、提示结果、清空选中并刷新', async () => {
    (batchPauseSubscriptionApi as any).mockResolvedValue({ successCount: 2, failedIds: [3] })
    const composable = usePtSubscription()
    composable.selectedIds.value = [1, 2, 3]

    await composable.handleBatchPause()

    expect(batchPauseSubscriptionApi).toHaveBeenCalledWith([1, 2, 3])
    expect(successSpy).toHaveBeenCalledWith('成功 2 项，1 项已跳过（可能已被删除）')
    expect(composable.selectedIds.value).toEqual([])
  })

  it('全部成功时提示语不带跳过后缀', async () => {
    (batchPauseSubscriptionApi as any).mockResolvedValue({ successCount: 2, failedIds: [] })
    const composable = usePtSubscription()
    composable.selectedIds.value = [1, 2]

    await composable.handleBatchPause()

    expect(successSpy).toHaveBeenCalledWith('成功 2 项')
  })

  it('批量恢复同构：确认后调用 batchResumeSubscriptionApi', async () => {
    (batchResumeSubscriptionApi as any).mockResolvedValue({ successCount: 1, failedIds: [] })
    const composable = usePtSubscription()
    composable.selectedIds.value = [9]

    await composable.handleBatchResume()

    expect(batchResumeSubscriptionApi).toHaveBeenCalledWith([9])
  })

  it('toggleSubSelect 未选中时加入、已选中时移除，isSubSelected 与之同步', () => {
    const composable = usePtSubscription()
    expect(composable.isSubSelected(7)).toBe(false)
    composable.toggleSubSelect({ id: 7 })
    expect(composable.isSubSelected(7)).toBe(true)
    composable.toggleSubSelect({ id: 7 })
    expect(composable.isSubSelected(7)).toBe(false)
  })
})
