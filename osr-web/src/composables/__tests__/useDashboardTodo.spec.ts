import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@/api/openlist/ptHealth', () => ({ getPtHealthApi: vi.fn() }))
vi.mock('@/api/openlist/ptIndexer', () => ({ getPtIndexerListApi: vi.fn() }))
vi.mock('@/router', () => ({ getRoutePathForComponent: (k: string) => `/p/${k}` }))

import { getPtHealthApi } from '@/api/openlist/ptHealth'
import { getPtIndexerListApi } from '@/api/openlist/ptIndexer'
import { useDashboardTodo, isIndexerAbnormal } from '../useDashboardTodo'

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
  beforeEach(() => { vi.clearAllMocks() })

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
})
