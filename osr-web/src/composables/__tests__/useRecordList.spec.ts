import { describe, it, expect, vi } from 'vitest'
import { useRecordList, batchRetryMessage } from '../useRecordList'

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

describe('批量重试的提示', () => {
  it('有记录被跳过时把跳过条数说出来', () => {
    // 选了 10 条、其中 4 条已成功：只说「已提交」会让人以为 10 条都在跑
    expect(batchRetryMessage(10, 6)).toBe('已提交重试 6 条，跳过 4 条处理中或已成功的记录')
  })

  it('全部提交时只报条数', () => {
    expect(batchRetryMessage(3, 3)).toBe('已提交重试 3 条')
  })

  it('接口不返回条数时退回通用文案', () => {
    // STRM 的批量重试不返回条数，不能拼出「已提交重试 undefined 条」
    expect(batchRetryMessage(3, undefined)).toBe('已提交批量重试')
  })
})
