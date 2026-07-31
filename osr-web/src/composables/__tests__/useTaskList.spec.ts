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
