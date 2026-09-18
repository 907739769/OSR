import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('@/api/openlist/ptIndexer', () => ({
  getPtIndexerListApi: vi.fn().mockResolvedValue({ records: [], total: 0 }),
  addPtIndexerApi: vi.fn(),
  updatePtIndexerApi: vi.fn(),
  deletePtIndexerApi: vi.fn(),
  testPtIndexerApi: vi.fn(),
  getPtIndexerCategoriesApi: vi.fn()
}))

vi.mock('@/composables/useMessage', () => ({
  message: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() }
}))

import { usePtIndexer } from '../usePtIndexer'
import { getPtIndexerCategoriesApi } from '@/api/openlist/ptIndexer'
import { message } from '@/composables/useMessage'

const A = { id: 1, name: 'A', url: 'http://jackett/a', categories: '2000' }
const B = { id: 2, name: 'B', url: 'http://jackett/b', categories: undefined }
const catsA = [{ id: 2000, name: 'Movies', children: [] }]
const catsB = [{ id: 5000, name: 'TV', children: [] }]

describe('usePtIndexer 分类下拉', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('切到另一个索引器时不显示上一个索引器的分类，切回来仍在', async () => {
    const c = usePtIndexer({ autoLoad: false })
    ;(getPtIndexerCategoriesApi as any).mockResolvedValue(catsA)
    c.handleUpdate(A, '修改索引器')
    await c.fetchCategories()
    expect(c.categoryOptions.value).toEqual(catsA)

    c.handleUpdate(B, '修改索引器')
    expect(c.categoryOptions.value).toEqual([])

    c.handleAdd('新增索引器')
    expect(c.categoryOptions.value).toEqual([])

    c.handleUpdate(A, '修改索引器')
    expect(c.categoryOptions.value).toEqual(catsA)
  })

  it('改了接口地址，旧地址拉到的分类失效', async () => {
    const c = usePtIndexer({ autoLoad: false })
    ;(getPtIndexerCategoriesApi as any).mockResolvedValue(catsA)
    c.handleUpdate(A, '修改索引器')
    await c.fetchCategories()

    c.form.value.url = 'http://prowlarr/other'
    expect(c.categoryOptions.value).toEqual([])
  })

  it('请求没回来就切到别的索引器，结果不会串到新弹窗里', async () => {
    const c = usePtIndexer({ autoLoad: false })
    let resolveA!: (v: unknown) => void
    ;(getPtIndexerCategoriesApi as any).mockReturnValueOnce(new Promise(r => { resolveA = r }))
    c.handleUpdate(A, '修改索引器')
    const pending = c.fetchCategories()
    expect(c.categoriesLoading.value).toBe(true)

    c.handleUpdate(B, '修改索引器')
    expect(c.categoriesLoading.value).toBe(false)
    resolveA(catsA)
    await pending
    expect(c.categoryOptions.value).toEqual([])

    ;(getPtIndexerCategoriesApi as any).mockResolvedValue(catsB)
    await c.fetchCategories()
    expect(c.categoryOptions.value).toEqual(catsB)
  })

  it('手输分类：拆逗号、去重、只留数字', () => {
    const c = usePtIndexer({ autoLoad: false })
    c.handleAdd('新增索引器')
    // combobox 回写的数组里可能混着选项对象，类型上是 string[]，这里按真实形态喂
    c.categoriesSelected.value = ['2000', '5000, 5030', { title: 'TV', value: '5000' }, 'abc'] as any
    expect(c.form.value.categories).toBe('2000,5000,5030')
    expect(message.warning).toHaveBeenCalledOnce()

    c.categoriesSelected.value = []
    expect(c.form.value.categories).toBeUndefined()
  })
})
