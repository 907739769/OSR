import { describe, it, expect, vi } from 'vitest'
import { reactive } from 'vue'
import { useDataTable, ITEMS_PER_PAGE_OPTIONS } from '../useDataTable'
import { resetQueryParams } from '../queryParams'

function build() {
  const queryParams = reactive<Record<string, any>>({ pageNum: 3, pageSize: 10 })
  const getList = vi.fn()
  const table = useDataTable({ queryParams: queryParams as any, getList })
  return { queryParams, getList, table }
}

describe('useDataTable 的表头排序', () => {
  it('把排序转成后端要的 orderByColumn / isAsc 并回到第 1 页', () => {
    const { queryParams, getList, table } = build()

    table.onSortChange([{ key: 'createTime', order: 'desc' }])

    expect(queryParams.orderByColumn).toBe('createTime')
    expect(queryParams.isAsc).toBe('desc')
    expect(queryParams.pageNum).toBe(1)
    expect(getList).toHaveBeenCalledTimes(1)
  })

  it('order 缺省按升序处理（Vuetify 首次点击可能不带 order）', () => {
    const { queryParams, table } = build()

    table.onSortChange([{ key: 'title' }])

    expect(queryParams.isAsc).toBe('asc')
  })

  it('取消排序时删掉参数而不是留空串，否则后端会拿空列名去拼 ORDER BY', () => {
    const { queryParams, table } = build()
    table.onSortChange([{ key: 'createTime', order: 'asc' }])

    table.onSortChange([])

    expect('orderByColumn' in queryParams).toBe(false)
    expect('isAsc' in queryParams).toBe(false)
    expect(table.sortBy.value).toEqual([])
  })

  it('sortBy 回填给表格，箭头方向与实际排序一致', () => {
    const { table } = build()

    table.onSortChange([{ key: 'status', order: 'desc' }])

    expect(table.sortBy.value).toEqual([{ key: 'status', order: 'desc' }])
  })

  it('「重置」清筛选条件但保留排序：箭头是表格自己的状态，清了参数就会和顺序对不上', () => {
    const { queryParams, table } = build()
    queryParams.title = '航海王'
    table.onSortChange([{ key: 'createTime', order: 'desc' }])

    resetQueryParams(queryParams as any, { pageNum: 1, pageSize: 10 })

    expect(queryParams.title).toBeUndefined()
    expect(queryParams.orderByColumn).toBe('createTime')
    expect(queryParams.isAsc).toBe('desc')
  })
})

describe('useDataTable 的每页条数档位', () => {
  it('不含 Vuetify 默认的「全部」档（-1）：后端只会回 1000 条，写「全部」是误导', () => {
    expect(ITEMS_PER_PAGE_OPTIONS).not.toContain(-1)
    expect(ITEMS_PER_PAGE_OPTIONS[ITEMS_PER_PAGE_OPTIONS.length - 1]).toBe(1000)
  })

  it('档位随 table 一起返回，页面直接绑给 items-per-page-options', () => {
    const { table } = build()

    expect(table.itemsPerPageOptions).toBe(ITEMS_PER_PAGE_OPTIONS)
  })
})
