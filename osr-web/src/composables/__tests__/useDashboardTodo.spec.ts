import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@/api/openlist/ptHealth', () => ({ getPtHealthApi: vi.fn() }))
vi.mock('@/api/openlist/ptIndexer', () => ({ getPtIndexerListApi: vi.fn() }))
vi.mock('@/api/openlist/dashboard', () => ({ getTodoSignalsApi: vi.fn() }))
vi.mock('@/router', () => ({ getRoutePathForComponent: (k: string) => `/p/${k}` }))

import { getPtHealthApi } from '@/api/openlist/ptHealth'
import { getPtIndexerListApi } from '@/api/openlist/ptIndexer'
import { getTodoSignalsApi } from '@/api/openlist/dashboard'
import { useDashboardTodo, isIndexerAbnormal, describeProblems } from '../useDashboardTodo'

const NO_SIGNALS = { offlineDownloaders: [], unhealthyMediaServers: [], unresolvedFailedDownloads: 0 }

describe('isIndexerAbnormal', () => {
  it('启用且上次轮询非 OK 才算异常', () => {
    expect(isIndexerAbnormal({ enabled: '1', lastStatus: 'timeout' })).toBe(true)
    expect(isIndexerAbnormal({ enabled: '1', lastStatus: 'OK' })).toBe(false)
  })
  it('没轮询过、或已停用都不算异常', () => {
    expect(isIndexerAbnormal({ enabled: '1', lastStatus: null })).toBe(false)
    expect(isIndexerAbnormal({ enabled: '0', lastStatus: 'timeout' })).toBe(false)
  })
})

describe('useDashboardTodo', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(getTodoSignalsApi).mockResolvedValue(NO_SIGNALS as any)
  })

  it('只列出数量大于 0 的待办，并带上落地页路径', async () => {
    vi.mocked(getPtHealthApi).mockResolvedValue({ bucketCounts: { OVERDUE_MISSING: 3, BLOCKED: 0 } } as any)
    vi.mocked(getPtIndexerListApi).mockResolvedValue({ records: [{ enabled: '1', lastStatus: 'err' }, { enabled: '1', lastStatus: 'OK' }] } as any)
    const t = useDashboardTodo()
    await t.load()
    expect(t.items.value.map((i) => [i.key, i.count])).toEqual([['overdueMissing', 3], ['indexer', 1]])
    expect(t.items.value[0].path).toBe('/p/openlist/ptHealth/index')
    expect(t.failed.value).toBe(false)
  })

  it('一路失败时置 failed，另一路照常出数（不能当成没有待办）', async () => {
    vi.mocked(getPtHealthApi).mockRejectedValue(new Error('boom'))
    vi.mocked(getPtIndexerListApi).mockResolvedValue({ records: [{ enabled: '1', lastStatus: 'err' }] } as any)
    const t = useDashboardTodo()
    await t.load()
    expect(t.failed.value).toBe(true)
    expect(t.items.value.map((i) => i.key)).toEqual(['indexer'])
  })

  it('下载器离线排在最前，失败下载单列一项', async () => {
    vi.mocked(getPtHealthApi).mockResolvedValue({ bucketCounts: { OVERDUE_MISSING: 1 } } as any)
    vi.mocked(getPtIndexerListApi).mockResolvedValue({ records: [] } as any)
    vi.mocked(getTodoSignalsApi).mockResolvedValue({
      offlineDownloaders: [{ name: '家里qB', since: null, detail: null }],
      unhealthyMediaServers: [],
      unresolvedFailedDownloads: 4
    } as any)
    const t = useDashboardTodo()
    await t.load()
    expect(t.items.value.map((i) => [i.key, i.count])).toEqual([['downloader', 1], ['overdueMissing', 1], ['failedDownload', 4]])
    expect(t.items.value[0].hint).toBe('家里qB 连不上')
  })

  it('待办信号某一路为 null 算没取到，不能当成没有问题', async () => {
    vi.mocked(getPtHealthApi).mockResolvedValue({ bucketCounts: {} } as any)
    vi.mocked(getPtIndexerListApi).mockResolvedValue({ records: [] } as any)
    vi.mocked(getTodoSignalsApi).mockResolvedValue({ ...NO_SIGNALS, offlineDownloaders: null } as any)
    const t = useDashboardTodo()
    await t.load()
    expect(t.failed.value).toBe(true)
  })
})

describe('describeProblems', () => {
  it('两个以内全列，多了只列前两个并报总数', () => {
    const p = (name: string) => ({ name, since: null, detail: null })
    expect(describeProblems([p('A'), p('B')], ' 连不上')).toBe('A、B 连不上')
    expect(describeProblems([p('A'), p('B'), p('C')], ' 连不上')).toBe('A、B 等 3 台 连不上')
  })
})
