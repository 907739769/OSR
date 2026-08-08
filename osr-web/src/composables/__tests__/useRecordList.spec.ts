import { describe, it, expect, vi } from 'vitest'
import { useRecordList } from '../useRecordList'

vi.mock('../useMessage', () => ({
  message: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }
}))

vi.mock('../useConfirm', () => ({
  confirm: vi.fn()
}))

function build() {
  return useRecordList({
    listApi: () => Promise.resolve({ records: [], total: 0 }),
    batchDeleteApi: vi.fn(),
    idField: 'id',
    recordLabel: '测试记录'
  })
}

describe('useRecordList 的日期区间', () => {
  it('两侧都填时拼出完整区间', () => {
    const base = build()
    base.dateStart.value = '2026-08-01'
    base.dateEnd.value = '2026-08-08'
    base.handleQuery()

    expect((base.queryParams as any).params).toEqual({
      beginTime: '2026-08-01 00:00:00',
      endTime: '2026-08-08 23:59:59'
    })
  })

  it('只填开始日期时不拼出空的结束时间', () => {
    // 老实现按「数组长度是 2」判定，会拼出 " 23:59:59"，
    // 后端拿它比 DATETIME 列 → Incorrect DATETIME value → 500
    const base = build()
    base.dateStart.value = '2026-08-01'
    base.handleQuery()

    expect((base.queryParams as any).params).toEqual({ beginTime: '2026-08-01 00:00:00' })
  })

  it('只填结束日期时不拼出空的开始时间', () => {
    const base = build()
    base.dateEnd.value = '2026-08-08'
    base.handleQuery()

    expect((base.queryParams as any).params).toEqual({ endTime: '2026-08-08 23:59:59' })
  })

  it('清空最后一侧后整体去掉 params', () => {
    const base = build()
    base.dateStart.value = '2026-08-01'
    base.handleQuery()
    base.dateStart.value = ''
    base.handleQuery()

    expect(base.dateRange.value).toBeNull()
    expect((base.queryParams as any).params).toBeUndefined()
  })

  it('清空其中一侧时保留另一侧', () => {
    const base = build()
    base.dateStart.value = '2026-08-01'
    base.dateEnd.value = '2026-08-08'
    base.dateStart.value = ''
    base.handleQuery()

    expect((base.queryParams as any).params).toEqual({ endTime: '2026-08-08 23:59:59' })
  })
})
