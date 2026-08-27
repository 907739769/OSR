import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { message } from '../useMessage'
import { confirm } from '../useConfirm'
import { useTaskList } from '../useTaskList'

vi.mock('../useMessage', () => ({
  message: {
    success: vi.fn(),
    error: vi.fn(),
    warning: vi.fn(),
    info: vi.fn()
  }
}))

vi.mock('../useConfirm', () => ({
  confirm: vi.fn()
}))

const listApi = () => Promise.resolve({ records: [], total: 0 })

function build(overrides: Record<string, any> = {}) {
  return useTaskList({
    listApi,
    addApi: vi.fn(),
    updateApi: vi.fn(),
    deleteApi: vi.fn(),
    idField: 'id',
    initForm: () => ({ id: undefined }),
    rules: {},
    ...overrides
  })
}

describe('useTaskList 的 executeApi 可选性', () => {
  let warnSpy: any
  let confirmSpy: any

  beforeEach(() => {
    warnSpy = message.warning as any
    // confirm() 在真实环境下是弹窗 Promise，没有用户点击不会 resolve/reject，
    // 会导致依赖它的用例（如"传了 executeApi 时不走提示分支"）挂起直到超时。
    // 这里 mock 成立即 resolve，模拟用户点击"确定"，仅在 executeApi 存在、需要真正
    // 走到 confirm 弹窗那条分支的用例里才会用到；不传 executeApi 的用例在此之前已被
    // 守卫短路 return，不受影响。
    confirmSpy = confirm as any
    confirmSpy.mockResolvedValue(undefined)
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  it('不传 executeApi 时可以正常构造', () => {
    expect(() => build()).not.toThrow()
  })

  it('不传 executeApi 时 handleExecute 给出提示而非抛异常', async () => {
    const base = build()
    await base.handleExecute('是否确认执行？')
    expect(warnSpy).toHaveBeenCalledWith('该列表不支持执行操作')
    // 守卫必须在 confirm 之前短路 return，不能先弹确认框
    // 再告诉用户不支持——否则用户点了确定之后才被告知操作不可用
    expect(confirmSpy).not.toHaveBeenCalled()
  })

  it('不传 executeApi 时 handleExecuteOne 给出提示而非抛异常', async () => {
    const base = build()
    await base.handleExecuteOne({ id: 1 }, '是否确认执行？')
    expect(warnSpy).toHaveBeenCalledWith('该列表不支持执行操作')
    // 同上：守卫必须在 confirm 弹窗之前生效
    expect(confirmSpy).not.toHaveBeenCalled()
  })

  it('传了 executeApi 时不走提示分支', async () => {
    const executeApi = vi.fn().mockResolvedValue({})
    const base = build({ executeApi })
    // 确认弹窗已 mock 为立即 resolve，此处验证真正走到了执行路径：
    // 不仅没有出现「不支持执行」的提示，executeApi 也确实被调用
    await base.handleExecute('是否确认执行？').catch(() => undefined)
    expect(warnSpy).not.toHaveBeenCalledWith('该列表不支持执行操作')
    // handleExecute 传的是 selectedIds.value，未触发过 handleSelectionChange 时为空数组
    expect(executeApi).toHaveBeenCalledWith([])
  })
})

describe('useTaskList 的 handleUpdate 取数路径', () => {
  afterEach(() => {
    vi.clearAllMocks()
  })

  it('传了行数据时直接用它，不回查列表', async () => {
    // 卡片/表格行上的「修改」按钮传的就是整行。旧实现在这里仍去查一次
    // pageNum:1 / pageSize:100 的列表，白发一个请求；用户在第 2 页或数据超过 100 条时
    // find 还必然落空，弹「任务不存在」——而那条数据就在屏幕上。
    const listSpy = vi.fn().mockResolvedValue({ records: [], total: 0 })
    const base = build({ listApi: listSpy })
    listSpy.mockClear()

    base.handleUpdate({ id: 7, name: '索引器 A' }, '修改索引器')

    expect(listSpy).not.toHaveBeenCalled()
    expect(base.open.value).toBe(true)
    expect(base.form.value).toEqual({ id: 7, name: '索引器 A' })
    expect(base.dialogTitle.value).toBe('修改索引器')
  })

  it('工具栏路径（不传行数据）先在当前页数据里找，同样不回查', async () => {
    const listSpy = vi.fn().mockResolvedValue({ records: [], total: 0 })
    const base = build({ listApi: listSpy })
    base.taskList.value = [{ id: 7, name: '索引器 A' }, { id: 8, name: '索引器 B' }]
    base.toggleSelect(8)
    listSpy.mockClear()

    base.handleUpdate(undefined, '修改索引器')

    expect(listSpy).not.toHaveBeenCalled()
    expect(base.form.value).toEqual({ id: 8, name: '索引器 B' })
  })

  it('选中项已翻页离开当前页时才回查列表', async () => {
    // usePageSelection 的选择集跨页累加，勾选后翻页确实会走到这条兜底
    const listSpy = vi.fn().mockResolvedValue({ records: [{ id: 99, name: '旧页那条' }], total: 1 })
    const base = build({ listApi: listSpy })
    base.taskList.value = [{ id: 7 }]
    base.toggleSelect(99)
    listSpy.mockClear()

    base.handleUpdate(undefined, '修改索引器')
    await new Promise((resolve) => setTimeout(resolve, 0))

    expect(listSpy).toHaveBeenCalledTimes(1)
    expect(base.form.value).toEqual({ id: 99, name: '旧页那条' })
  })

  it('表单是行数据的副本，改弹窗不会顺手改掉列表里那一行', async () => {
    const base = build()
    const row: any = { id: 7, name: '索引器 A' }

    base.handleUpdate(row, '修改索引器')
    base.form.value.name = '改了一半没提交'

    expect(row.name).toBe('索引器 A')
  })
})

describe('useTaskList 的 resetQuery', () => {
  it('把查询条件还原成 defaultQuery 而不是一律清空', () => {
    // status 有非空默认值（订阅页 'ACTIVE'、孤儿页 '0' 都是这种），
    // 重置后必须回到这个默认值，而不是被清成"全部"
    const base = build({ defaultQuery: { status: 'ACTIVE', title: undefined } })
    const qp = base.queryParams as any

    qp.status = 'PAUSED'
    qp.title = '沙丘'
    base.resetQuery()

    expect(qp.status).toBe('ACTIVE')
    expect(qp.title).toBeUndefined()
    expect(qp.pageNum).toBe(1)
  })

  it('删掉默认值里没有的残留条件', () => {
    // 日期区间写入的 params、页面自行挂上去的字段，只 Object.assign 会残留下来继续参与查询
    const base = build({ defaultQuery: { status: undefined } })
    const qp = base.queryParams as any

    qp.params = { beginTime: '2026-01-01 00:00:00' }
    base.resetQuery()

    expect(qp.params).toBeUndefined()
  })

  it('不重置每页条数（分页偏好不属于筛选条件）', () => {
    const base = build({ defaultQuery: { pageSize: 12 } })
    const qp = base.queryParams as any

    qp.pageSize = 50
    base.resetQuery()

    expect(qp.pageSize).toBe(50)
  })

  it('页面没绑 ref="queryRef" 时依然完成重置', () => {
    // 老实现是 queryRef.value?.reset?.()，漏绑 ref 会被可选链吃掉，重置静默失效
    const base = build({ defaultQuery: { status: 'ACTIVE' } })
    const qp = base.queryParams as any

    expect(base.queryRef.value).toBeUndefined()
    qp.status = 'PAUSED'
    base.resetQuery()

    expect(qp.status).toBe('ACTIVE')
  })
})
