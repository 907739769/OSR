import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { nextTick } from 'vue'

// usePtDownloader 内部直接从 '@/api/openlist/ptDownloader' 具名导入，
// 这里 mock 掉整个模块，避免真实发起网络请求。
vi.mock('@/api/openlist/ptDownloader', () => ({
  getPtDownloaderListApi: vi.fn().mockResolvedValue({ records: [], total: 0 }),
  addPtDownloaderApi: vi.fn(),
  updatePtDownloaderApi: vi.fn(),
  deletePtDownloaderApi: vi.fn(),
  testPtDownloaderApi: vi.fn(),
  validateSavePathApi: vi.fn(),
  getDownloaderSpaceApi: vi.fn().mockResolvedValue([])
}))

import { usePtDownloader } from '../usePtDownloader'
import { validateSavePathApi, getPtDownloaderListApi } from '@/api/openlist/ptDownloader'

/**
 * base.handleUpdate 内部先 await listApi(...).then(...)，这是一层独立于 Vue 调度的
 * 微任务链；仅 nextTick() 只保证 Vue 响应式副作用（watch/render）被冲刷，不保证
 * 先于它的 Promise.then 回调已经跑完。这里多等几个微任务 tick 把两条链都冲干净。
 */
async function flushPromises() {
  await Promise.resolve()
  await Promise.resolve()
  await nextTick()
}

describe('usePtDownloader 的保存路径警告生命周期', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(getPtDownloaderListApi as any).mockResolvedValue({ records: [], total: 0 })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('新增时警告被清空：上一次编辑留下的警告不应残留到新表单', async () => {
    const composable = usePtDownloader()
    await nextTick()

    // 模拟上一次编辑时校验出的警告仍挂在 ref 上
    composable.savePathWarning.value = '保存路径不在任何文件同步任务的监听目录下'

    composable.handleAdd('新增下载器')
    await nextTick()

    expect(composable.savePathWarning.value).toBe('')
  })

  it('校验请求失败时显示明确的未知态文案，而不是清空为空串', async () => {
    (validateSavePathApi as any).mockRejectedValue(new Error('网络错误'))
    const composable = usePtDownloader()
    await nextTick()

    composable.form.value.savePath = '/data/downloads'
    await composable.handleSavePathBlur()

    expect(composable.savePathWarning.value).toBe('校验失败，无法确认保存路径是否在监听目录下')
    expect(composable.savePathWarning.value).not.toBe('')
  })

  it('校验请求只传 savePath 字段，不把包含明文密码的整个表单发出去', async () => {
    (validateSavePathApi as any).mockResolvedValue('')
    const composable = usePtDownloader()
    await nextTick()

    composable.form.value.savePath = '/data/downloads'
    composable.form.value.password = 'super-secret'
    composable.form.value.username = 'admin'
    await composable.handleSavePathBlur()

    expect(validateSavePathApi).toHaveBeenCalledTimes(1)
    expect(validateSavePathApi).toHaveBeenCalledWith({ savePath: '/data/downloads' })
  })

  it('编辑既有记录时会自动触发一次校验，即使从未 blur 过', async () => {
    const existingRow = {
      id: 1,
      name: 'qb-1',
      savePath: '/data/not-watched',
      type: 'QBITTORRENT',
      host: '127.0.0.1',
      port: 8080
    }
    ;(getPtDownloaderListApi as any).mockResolvedValue({ records: [existingRow], total: 1 })
    ;(validateSavePathApi as any).mockResolvedValue('保存路径不在任何文件同步任务的监听目录下')

    const composable = usePtDownloader()
    await nextTick()

    composable.handleUpdate(existingRow, '修改下载器')
    // handleUpdate 内部先异步查询列表接口拿到该行，再填充表单、打开对话框，
    // open 变化触发 watch 回调后才会发起（同样是异步的）校验请求，
    // 因此这里需要两轮 flushPromises：一轮等表单填充+对话框打开，一轮等校验请求本身。
    await flushPromises()
    await flushPromises()

    expect(validateSavePathApi).toHaveBeenCalledWith({ savePath: '/data/not-watched' })
    expect(composable.savePathWarning.value).toBe('保存路径不在任何文件同步任务的监听目录下')
  })

  it('初始化表单时 maxConcurrent 默认值为 0（表示不限）', async () => {
    const composable = usePtDownloader()
    await nextTick()

    expect(composable.form.value.maxConcurrent).toBe(0)
  })
})

describe('usePtDownloader 剩余空间', () => {
  it('随列表一起拉剩余空间：读到的写体积，低于告警线标出来，没读到写「未读取」', async () => {
    const api = await import('@/api/openlist/ptDownloader')
    vi.mocked(api.getDownloaderSpaceApi).mockResolvedValue([
      { id: 1, freeBytes: 8 * 1024 ** 3, checkedAt: '', warnBytes: 50 * 1024 ** 3, low: true, lowSince: null }
    ])
    const ctx = usePtDownloader({ autoLoad: false })
    ctx.taskList.value = [{ id: 1 }, { id: 2 }] as any
    await new Promise(r => setTimeout(r, 0))

    expect(ctx.spaceText({ id: 1 })).toBe('8.00 GB')
    expect(ctx.spaceLow({ id: 1 })).toBe(true)
    expect(ctx.spaceText({ id: 2 })).toBe('未读取')
    expect(ctx.spaceLow({ id: 2 })).toBe(false)
  })

  it('阈值清空后落成 null：后端靠 null 表示不告警 / 不看空间', async () => {
    const ctx = usePtDownloader({ autoLoad: false })
    ctx.form.value = { freeSpaceWarnGb: 50, autoDeleteFreeBelowGb: 100 } as any
    await nextTick()
    ctx.form.value.freeSpaceWarnGb = '' as any
    ctx.form.value.autoDeleteFreeBelowGb = '' as any
    await nextTick()

    expect(ctx.form.value.freeSpaceWarnGb).toBeNull()
    expect(ctx.form.value.autoDeleteFreeBelowGb).toBeNull()
  })
})
