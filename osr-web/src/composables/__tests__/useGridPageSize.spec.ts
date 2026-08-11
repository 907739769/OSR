import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { useGridPageSize } from '../useGridPageSize'

/** 当前 mock 出来的 grid-template-columns 解析值，改它就等于改窗口宽度 */
let tracks = ''

function setColumns(n: number) {
  tracks = Array.from({ length: n }, () => '300px').join(' ')
}

/** 把 composable 挂进一个只有一个网格 div 的组件里跑 */
function mountGrid() {
  const apply = vi.fn()
  let api: ReturnType<typeof useGridPageSize>
  const wrapper = mount(defineComponent({
    setup() {
      api = useGridPageSize(apply)
      return () => h('div', { ref: api.gridRef })
    }
  }))
  return { api: api!, apply, wrapper }
}

describe('useGridPageSize', () => {
  beforeEach(() => {
    vi.spyOn(window, 'getComputedStyle').mockImplementation(() => ({ gridTemplateColumns: tracks }) as any)
  })

  afterEach(() => {
    vi.restoreAllMocks()
    vi.useRealTimers()
  })

  it('挂载时按实际列数取整到整行，一行 7 张时每页 21 条而不是 12 条', () => {
    setColumns(7)
    const { api, apply } = mountGrid()

    expect(api.columns.value).toBe(7)
    expect(api.pageSize.value).toBe(21)
    expect(apply).toHaveBeenCalledWith(21)
  })

  it('每页条数一定是列数的整数倍（最后一行要么满、要么就是最后一页）', () => {
    for (const cols of [1, 2, 3, 4, 5, 6, 7, 8]) {
      setColumns(cols)
      const { api } = mountGrid()
      expect(api.pageSize.value % cols).toBe(0)
      expect(api.pageSize.value).toBeGreaterThanOrEqual(12)
    }
  })

  it('分页器档位跟着列数走，首档就是当前每页条数', () => {
    setColumns(7)
    const { api } = mountGrid()

    expect(api.pageSizeOptions.value).toEqual([21, 42, 84])
    expect(api.pageSizeOptions.value[0]).toBe(api.pageSize.value)
  })

  it('换档存的是倍数，窗口变宽后档位按新列数换算，不会出现选不中的空白值', async () => {
    vi.useFakeTimers()
    setColumns(4)
    const { api, apply } = mountGrid()
    expect(api.pageSize.value).toBe(12)

    api.setPageSize(24) // 用户选了 2 倍档
    expect(api.pageSize.value).toBe(24)
    expect(apply).toHaveBeenLastCalledWith(24)

    setColumns(7)
    window.dispatchEvent(new Event('resize'))
    vi.advanceTimersByTime(200)

    // 仍然是 2 倍档，但基准跟着列数变了；当前值必定在档位列表里
    expect(api.pageSize.value).toBe(42)
    expect(api.pageSizeOptions.value).toContain(api.pageSize.value)
    expect(apply).toHaveBeenLastCalledWith(42)
  })

  it('resize 后列数没变就不回调，避免每拖一次窗口都打一发请求', () => {
    vi.useFakeTimers()
    setColumns(4)
    const { apply } = mountGrid()
    expect(apply).toHaveBeenCalledTimes(1)

    window.dispatchEvent(new Event('resize'))
    vi.advanceTimersByTime(200)

    expect(apply).toHaveBeenCalledTimes(1)
  })

  it('量不到列数时用兜底值，但首次一定回调一次（页面把初次加载交给了这里）', () => {
    tracks = 'none'
    const { api, apply } = mountGrid()

    expect(api.pageSize.value).toBe(12)
    expect(apply).toHaveBeenCalledTimes(1)
    expect(apply).toHaveBeenCalledWith(12)
  })

  it('拿到的还是没展开的 repeat() 时不瞎猜列数', () => {
    tracks = 'repeat(auto-fill, minmax(300px, 1fr))'
    const { api } = mountGrid()

    expect(api.columns.value).toBe(4)
    expect(api.pageSize.value).toBe(12)
  })
})
