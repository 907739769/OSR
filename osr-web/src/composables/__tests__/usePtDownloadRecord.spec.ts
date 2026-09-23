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

// usePtDownloadRecord 内部调用 useRoute()（读 subId query 参数）与 useRouter()（清掉它），
// 测试环境没有安装 vue-router 插件，mock 掉整个模块。query 做成可变的，订阅筛选的用例要改它
const routeState = vi.hoisted(() => ({ query: {} as Record<string, any>, replace: vi.fn() }))
vi.mock('vue-router', () => ({
  useRoute: () => ({ query: routeState.query }),
  useRouter: () => ({ replace: routeState.replace })
}))

vi.mock('../usePtStatusSocket', () => ({
  usePtStatusSocket: vi.fn()
}))

// getList 在 setup 阶段同步调用，mock 掉整个 API 模块避免真实网络请求。
vi.mock('@/api/openlist/ptDownloadRecord', () => ({
  getPtDownloadRecordListApi: vi.fn().mockResolvedValue({ records: [], total: 0 }),
  getPtDownloadRecordStatsApi: vi.fn().mockResolvedValue({ total: 0 }),
  retryPtDownloadRecordApi: vi.fn(),
  batchRetryPtDownloadRecordApi: vi.fn(),
  blacklistGuidApi: vi.fn(),
  blacklistReleaseGroupApi: vi.fn(),
  batchBlacklistGuidApi: vi.fn(),
  batchBlacklistReleaseGroupApi: vi.fn(),
  previewCleanupPtDownloadRecordApi: vi.fn(),
  cleanupPtDownloadRecordApi: vi.fn(),
  CLEANUP_DAY_OPTIONS: [30, 90, 180, 365]
}))

vi.mock('@/api/openlist/ptIndexer', () => ({
  getPtIndexerListApi: vi.fn().mockResolvedValue({ records: [{ id: 1, name: '站点A' }], total: 1 })
}))

vi.mock('@/api/openlist/ptDownloader', () => ({
  getPtDownloaderListApi: vi.fn().mockResolvedValue({ records: [{ id: 2, name: 'qB' }], total: 1 })
}))

import { nextTick } from 'vue'
import { usePtDownloadRecord } from '../usePtDownloadRecord'
import {
  batchRetryPtDownloadRecordApi, getPtDownloadRecordListApi,
  batchBlacklistGuidApi, batchBlacklistReleaseGroupApi,
  blacklistReleaseGroupApi, previewCleanupPtDownloadRecordApi, cleanupPtDownloadRecordApi
} from '@/api/openlist/ptDownloadRecord'
import { usePtStatusSocket } from '../usePtStatusSocket'
import { getPtIndexerListApi } from '@/api/openlist/ptIndexer'
import { getPtDownloaderListApi } from '@/api/openlist/ptDownloader'

/** 等 composable 里 setup 阶段发出的几个 Promise 都落地 */
const flush = () => new Promise(resolve => setTimeout(resolve, 0))

/** 最后一次 usePtStatusSocket 收到的回调（每个用例都会新建一个 composable） */
const socketHandlers = () => {
  const calls = (usePtStatusSocket as any).mock.calls
  return calls[calls.length - 1][0]
}

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

    expect(message.warning).toHaveBeenCalledWith('选中的记录里没有可重试的失败记录')
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
    (batchRetryPtDownloadRecordApi as any).mockResolvedValue({ batchId: 'b1', accepted: 2 })
    const composable = usePtDownloadRecord()
    composable.taskList.value = mixedList
    composable.selectedIds.value = [1, 2, 3]

    await composable.handleBatchRetry()

    expect(confirmSpy).toHaveBeenCalled()
    expect(batchRetryPtDownloadRecordApi).toHaveBeenCalledWith([1, 2])
    // 转后台执行：此刻只知道受理了几条，结果要等 WebSocket 回报
    expect(successSpy).toHaveBeenCalledWith('已在后台重试 2 条失败记录，完成后会提示结果')
    expect(composable.selectedIds.value).toEqual([])
  })

  it('已被后续推送接替的失败记录不算可重试', () => {
    const composable = usePtDownloadRecord()
    composable.taskList.value = [
      { id: 1, state: 'FAILED', supersededById: 9 },
      { id: 2, state: 'FAILED' }
    ]
    composable.selectedIds.value = [1, 2]

    expect(composable.retryableSelectedIds.value).toEqual([2])
  })

  it('有记录因无权操作被后端过滤时，提示里说出跳过了几条', async () => {
    (batchRetryPtDownloadRecordApi as any).mockResolvedValue({ batchId: 'b1', accepted: 1 })
    const composable = usePtDownloadRecord()
    composable.taskList.value = mixedList
    composable.selectedIds.value = [1, 2]

    await composable.handleBatchRetry()

    expect(successSpy).toHaveBeenCalledWith('已在后台重试 1 条失败记录，完成后会提示结果（1 条无权操作，已跳过）')
  })

  it('只认领自己发起的那一批 batchRetry 结果，别人的不提示', async () => {
    (batchRetryPtDownloadRecordApi as any).mockResolvedValue({ batchId: 'mine', accepted: 2 })
    const composable = usePtDownloadRecord()
    composable.taskList.value = mixedList
    composable.selectedIds.value = [1, 2]
    await composable.handleBatchRetry()
    successSpy.mockClear()

    socketHandlers().onBatchRetry({ type: 'batchRetry', batchId: 'others', total: 3, pushedCount: 3, skippedCount: 0 })
    expect(successSpy).not.toHaveBeenCalled()

    socketHandlers().onBatchRetry({ type: 'batchRetry', batchId: 'mine', total: 2, pushedCount: 1, skippedCount: 1 })
    expect(successSpy).toHaveBeenCalledWith('批量重试完成：重新推送 1 条，1 条未搜到或已跳过')

    // 同一批只提示一次
    successSpy.mockClear()
    socketHandlers().onBatchRetry({ type: 'batchRetry', batchId: 'mine', total: 2, pushedCount: 1, skippedCount: 1 })
    expect(successSpy).not.toHaveBeenCalled()
  })

  it('toggleRecordSelect 在未选中时加入选中，已选中时移除', () => {
    const composable = usePtDownloadRecord()
    composable.toggleRecordSelect({ id: 5 } as any)
    expect(composable.selectedIds.value).toEqual([5])
    composable.toggleRecordSelect({ id: 5 } as any)
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

  it('带上完成时间、失败原因分类与保种状态，原地更新后卡片不用刷新就对得上', () => {
    const composable = usePtDownloadRecord()
    composable.taskList.value = [
      { id: 1, state: 'DOWNLOADING', completedTime: null, hrState: null },
      { id: 2, state: 'PUSHED', failReasonCode: null }
    ]

    const handlers = socketHandlers()
    handlers.onDownload({ type: 'download', downloadId: 1, subId: 5, episode: 1, state: 'COMPLETED', progress: 1, completedTime: '2026-09-21 10:00:00', hrState: 'PENDING' })
    handlers.onDownload({ type: 'download', downloadId: 2, subId: 5, episode: 2, state: 'FAILED', failReason: '超时', failReasonCode: 'ZOMBIE_TIMEOUT' })

    expect(composable.taskList.value[0].completedTime).toBe('2026-09-21 10:00:00')
    expect(composable.taskList.value[0].hrState).toBe('PENDING')
    expect(composable.taskList.value[1].failReasonCode).toBe('ZOMBIE_TIMEOUT')
  })

  it('按状态筛选时，变成别的状态的记录从列表里移除并扣减总数', () => {
    const composable = usePtDownloadRecord()
    composable.queryParams.state = 'DOWNLOADING'
    composable.taskList.value = [{ id: 1, state: 'DOWNLOADING' }, { id: 2, state: 'DOWNLOADING' }]
    composable.total.value = 2

    socketHandlers().onDownload({ type: 'download', downloadId: 1, subId: 5, episode: 1, state: 'COMPLETED', progress: 1 })

    expect(composable.taskList.value.map((r: any) => r.id)).toEqual([2])
    expect(composable.total.value).toBe(1)
  })

  it('只是进度变化、状态没变时不移除也不扣总数', () => {
    const composable = usePtDownloadRecord()
    composable.queryParams.state = 'DOWNLOADING'
    composable.taskList.value = [{ id: 1, state: 'DOWNLOADING', progress: 0.1 }]
    composable.total.value = 1

    socketHandlers().onDownload({ type: 'download', downloadId: 1, subId: 5, episode: 1, state: 'DOWNLOADING', progress: 0.5 })

    expect(composable.taskList.value).toHaveLength(1)
    expect(composable.total.value).toBe(1)
  })

  it('找不到对应记录时静默忽略，不抛异常', () => {
    const composable = usePtDownloadRecord()
    composable.taskList.value = [{ id: 1, state: 'PUSHED' }]

    const handlers = (usePtStatusSocket as any).mock.calls[0][0]
    expect(() => handlers.onDownload({ type: 'download', downloadId: 999, subId: 5, episode: 1, state: 'FAILED', failReason: '超时' })).not.toThrow()
  })
})

describe('usePtDownloadRecord 的拉黑', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtDownloadRecordListApi as any).mockResolvedValue({ records: [], total: 0 })
  })

  it('批量拉黑先打开弹窗而不是直接调接口，提交时带上填写的原因', async () => {
    (batchBlacklistGuidApi as any).mockResolvedValue({ total: 2, addedCount: 2, duplicateCount: 0, failedCount: 0 })
    const composable = usePtDownloadRecord()
    composable.taskList.value = [{ id: 1, state: 'COMPLETED' }, { id: 2, state: 'PUSHED' }]
    composable.selectedIds.value = [1, 2]

    composable.handleBatchBlacklistGuid()
    expect(composable.blacklistDialog.visible).toBe(true)
    expect(batchBlacklistGuidApi).not.toHaveBeenCalled()

    composable.blacklistDialog.reason = ' 假种 '
    await composable.submitBlacklist()

    expect(batchBlacklistGuidApi).toHaveBeenCalledWith([1, 2], '假种')
    expect(message.success).toHaveBeenCalledWith('已拉黑 2 项')
    expect(composable.selectedIds.value).toEqual([])
    expect(composable.blacklistDialog.visible).toBe(false)
  })

  it('提示里带上已在黑名单中与未能拉黑的条数；原因留空时不传', async () => {
    (batchBlacklistReleaseGroupApi as any).mockResolvedValue({ total: 3, addedCount: 1, duplicateCount: 1, failedCount: 1 })
    const composable = usePtDownloadRecord()
    composable.selectedIds.value = [1, 2, 3]

    composable.handleBatchBlacklistReleaseGroup()
    await composable.submitBlacklist()

    expect(batchBlacklistReleaseGroupApi).toHaveBeenCalledWith([1, 2, 3], undefined)
    expect(message.success).toHaveBeenCalledWith('已拉黑 1 项，1 项已在黑名单中，1 项未能拉黑')
  })

  it('没有选中项时不打开弹窗', () => {
    const composable = usePtDownloadRecord()
    composable.selectedIds.value = []

    composable.handleBatchBlacklistGuid()

    expect(composable.blacklistDialog.visible).toBe(false)
  })

  it('单条拉黑发布组也要确认：弹窗文案里写出是哪个组', () => {
    const composable = usePtDownloadRecord()
    composable.handleBlacklistReleaseGroup({ id: 1, subId: 1, title: 'x', state: 'COMPLETED', releaseGroup: 'CHDWEB' })

    expect(composable.blacklistDialog.visible).toBe(true)
    expect(composable.blacklistDialog.message).toContain('CHDWEB')
  })

  it('单条拉黑发布组成功后，本页同组的记录一起标成已拉黑', async () => {
    (blacklistReleaseGroupApi as any).mockResolvedValue(true)
    const composable = usePtDownloadRecord()
    // 等 setup 里那次 getList 落地，否则它晚到的空列表会盖掉下面手工放进去的记录
    await flush()
    composable.taskList.value = [
      { id: 1, state: 'COMPLETED', releaseGroup: 'CHDWeb', releaseGroupBlacklisted: false },
      { id: 2, state: 'FAILED', releaseGroup: 'chdweb', releaseGroupBlacklisted: false },
      { id: 3, state: 'FAILED', releaseGroup: 'OTHER', releaseGroupBlacklisted: false }
    ]

    composable.handleBlacklistReleaseGroup(composable.taskList.value[0])
    await composable.submitBlacklist()

    expect(blacklistReleaseGroupApi).toHaveBeenCalledWith(1, undefined)
    expect(composable.taskList.value.map((r: any) => r.releaseGroupBlacklisted)).toEqual([true, true, false])
  })
})

describe('usePtDownloadRecord 的订阅筛选', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtDownloadRecordListApi as any).mockResolvedValue({ records: [], total: 0 })
  })

  afterEach(() => {
    routeState.query = {}
  })

  it('从订阅页带 subId 与剧名跳过来：按订阅筛选，筛选条写出剧名', () => {
    routeState.query = { subId: '148', subTitle: '闪耀的她' }
    const composable = usePtDownloadRecord()

    expect(composable.queryParams.subId).toBe(148)
    expect(composable.subFilterLabel.value).toBe('《闪耀的她》')
    expect(getPtDownloadRecordListApi).toHaveBeenCalledWith(expect.objectContaining({ subId: 148 }))
  })

  it('没带剧名时退回订阅 id', () => {
    routeState.query = { subId: '148' }
    const composable = usePtDownloadRecord()

    expect(composable.subFilterLabel.value).toBe('订阅 #148')
  })

  it('重置能回到全部记录，并把地址栏里的 subId 一起去掉', () => {
    routeState.query = { subId: '148', subTitle: '闪耀的她', tab: 'x' }
    const composable = usePtDownloadRecord()

    composable.resetQuery()

    expect(composable.queryParams.subId).toBeUndefined()
    expect(composable.subFilterLabel.value).toBe('')
    expect(routeState.replace).toHaveBeenCalledWith({ query: { tab: 'x' } })
  })

  it('关掉订阅筛选条：清掉 subId 并重新查询', () => {
    routeState.query = { subId: '148' }
    const composable = usePtDownloadRecord()
    ;(getPtDownloadRecordListApi as any).mockClear()

    composable.clearSubFilter()

    expect(composable.queryParams.subId).toBeUndefined()
    expect(getPtDownloadRecordListApi).toHaveBeenCalledWith(expect.not.objectContaining({ subId: 148 }))
  })
})

describe('usePtDownloadRecord 从统计仪表盘下钻', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtDownloadRecordListApi as any).mockResolvedValue({ records: [], total: 0 })
  })

  afterEach(() => {
    routeState.query = {}
  })

  /** 失败卡：区间内未被接替的失败，按失败日期筛——条数要与卡片上的数字对得上 */
  it('带状态、日期列、日期区间与隐藏已接替跳进来：首次加载就按这些条件查', () => {
    routeState.query = { state: 'FAILED', dateField: 'FAILED', beginDate: '2026-09-17', hideSuperseded: '1' }
    const composable = usePtDownloadRecord()

    expect(composable.queryParams.state).toBe('FAILED')
    expect(composable.queryParams.dateField).toBe('FAILED')
    expect(composable.queryParams.hideSuperseded).toBe(true)
    expect(composable.dateStart.value).toBe('2026-09-17')
    expect(composable.routeFilterTick.value).toBe(1)
    expect(getPtDownloadRecordListApi).toHaveBeenCalledWith(expect.objectContaining({
      state: 'FAILED', dateField: 'FAILED', hideSuperseded: true,
      params: { beginTime: '2026-09-17 00:00:00' }
    }))
  })

  it('没带筛选时不展开搜索区', () => {
    const composable = usePtDownloadRecord()
    expect(composable.routeFilterTick.value).toBe(0)
  })

  it('重置时把地址栏里所有下钻参数一起去掉，否则刷新又筛回去', () => {
    routeState.query = { hrState: 'VIOLATED', tab: 'x' }
    const composable = usePtDownloadRecord()

    composable.resetQuery()

    expect(composable.queryParams.hrState).toBeUndefined()
    expect(routeState.replace).toHaveBeenCalledWith({ query: { tab: 'x' } })
  })
})

describe('usePtDownloadRecord 的筛选项与清理', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtDownloadRecordListApi as any).mockResolvedValue({ records: [], total: 0 })
  })

  it('索引器 / 下载器下拉从各自的列表接口取', async () => {
    // 前面的 describe 用了 restoreAllMocks，模块 mock 的返回值要在这里重新给
    (getPtIndexerListApi as any).mockResolvedValue({ records: [{ id: 1, name: '站点A' }], total: 1 })
    ;(getPtDownloaderListApi as any).mockResolvedValue({ records: [{ id: 2, name: 'qB' }], total: 1 })
    const composable = usePtDownloadRecord()
    await flush()

    expect(composable.indexerOptions.value).toEqual([{ title: '站点A', value: 1 }])
    expect(composable.downloaderOptions.value).toEqual([{ title: 'qB', value: 2 }])
  })

  it('打开清理弹窗先预览条数，改保留天数会重新预览', async () => {
    (previewCleanupPtDownloadRecordApi as any).mockResolvedValue(12)
    const composable = usePtDownloadRecord()

    composable.openCleanup()
    await flush()
    expect(previewCleanupPtDownloadRecordApi).toHaveBeenLastCalledWith(180)
    expect(composable.cleanupDialog.count).toBe(12)

    composable.cleanupDialog.days = 365
    await nextTick()
    await flush()
    expect(previewCleanupPtDownloadRecordApi).toHaveBeenLastCalledWith(365)
  })

  it('预览为 0 条时点清理不发请求', async () => {
    (previewCleanupPtDownloadRecordApi as any).mockResolvedValue(0)
    const composable = usePtDownloadRecord()
    composable.openCleanup()
    await flush()

    await composable.submitCleanup()

    expect(cleanupPtDownloadRecordApi).not.toHaveBeenCalled()
  })

  it('清理成功后提示条数并关闭弹窗', async () => {
    (previewCleanupPtDownloadRecordApi as any).mockResolvedValue(5)
    ;(cleanupPtDownloadRecordApi as any).mockResolvedValue(5)
    const composable = usePtDownloadRecord()
    composable.openCleanup()
    await flush()

    await composable.submitCleanup()

    expect(cleanupPtDownloadRecordApi).toHaveBeenCalledWith(180)
    expect(message.success).toHaveBeenCalledWith('已清理 5 条下载记录')
    expect(composable.cleanupDialog.visible).toBe(false)
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
  })

  // isAllPageSelected 同时决定按钮文案（全选 / 取消全选），只选中一部分时仍是「全选」
  it('只选中一部分时不算全选', () => {
    const composable = usePtDownloadRecord()
    composable.taskList.value = [{ id: 1, state: 'FAILED' }, { id: 2, state: 'COMPLETED' }]
    composable.selectedIds.value = [1]

    expect(composable.isAllPageSelected.value).toBe(false)
  })

  it('再次点全选（此时是取消全选）把当前页整批摘掉', () => {
    const composable = usePtDownloadRecord()
    composable.taskList.value = [{ id: 1, state: 'FAILED' }, { id: 2, state: 'COMPLETED' }]
    composable.toggleSelectAllPage(true)

    composable.toggleSelectAllPage(false)

    expect(composable.selectedIds.value).toEqual([])
    expect(composable.isAllPageSelected.value).toBe(false)
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

describe('usePtDownloadRecord 的默认分页', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtDownloadRecordListApi as any).mockResolvedValue({ records: [], total: 0 })
  })

  // 移动端是单列，12 条正好；PC 端挂载后由 useGridPageSize 按实际列数改成整行的条数。
  // 落回 useRecordList 的默认 10 会让最后一行空半截，用户读成「没填满 = 没有下一页」
  it('默认每页 12 条（移动端与 PC 量出列数前的兜底）', () => {
    const composable = usePtDownloadRecord()

    expect(composable.queryParams.pageSize).toBe(12)
    expect(getPtDownloadRecordListApi).toHaveBeenCalledWith(
      expect.objectContaining({ pageNum: 1, pageSize: 12 })
    )
  })

  it('重置查询条件不会把每页条数改回默认值', async () => {
    const composable = usePtDownloadRecord()
    composable.queryParams.pageSize = 48

    composable.resetQuery()

    expect(composable.queryParams.pageSize).toBe(48)
  })

  // PC 端把首次加载交给 useGridPageSize，免得先按兜底值发一次再按真实列数重发
  it('autoLoad: false 时 setup 阶段不发请求', () => {
    usePtDownloadRecord({ autoLoad: false })

    expect(getPtDownloadRecordListApi).not.toHaveBeenCalled()
  })
})

describe('已推送迟迟不开始的提示', () => {
  const NOW = new Date('2026-09-23T12:00:00').getTime()
  const record = (patch: Record<string, any>) => ({ id: 1, subId: 1, title: 'x', state: 'PUSHED', ...patch }) as any

  it('推送超过一小时仍是已推送才提示', async () => {
    const { stalePushedHint } = await import('../ptDownloadRecordLabels')
    expect(stalePushedHint(record({ pushedTime: '2026-09-23 11:30:00' }), NOW)).toBe('')
    expect(stalePushedHint(record({ pushedTime: '2026-09-23 09:00:00' }), NOW)).toContain('3 小时前')
  })

  it('其它状态或没有推送时间不提示', async () => {
    const { stalePushedHint } = await import('../ptDownloadRecordLabels')
    expect(stalePushedHint(record({ state: 'DOWNLOADING', pushedTime: '2026-09-22 09:00:00' }), NOW)).toBe('')
    expect(stalePushedHint(record({ pushedTime: null }), NOW)).toBe('')
  })
})
