import { describe, it, expect, vi, beforeEach } from 'vitest'
import { nextTick, reactive } from 'vue'

const route = reactive<{ query: Record<string, any> }>({ query: {} })
const replace = vi.fn((to: { query: Record<string, any> }) => { route.query = to.query })

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ replace })
}))

import { useTabQuery } from '../useTabQuery'

const TABS = ['template', 'rules', 'test'] as const

beforeEach(() => {
  route.query = {}
  replace.mockClear()
})

describe('useTabQuery', () => {
  it('没有 ?tab= 时取默认值，且不写 URL', async () => {
    const tab = useTabQuery(TABS, 'template')
    await nextTick()
    expect(tab.value).toBe('template')
    expect(replace).not.toHaveBeenCalled()
  })

  it('刷新后停在 URL 指定的 tab', () => {
    route.query = { tab: 'rules' }
    expect(useTabQuery(TABS, 'template').value).toBe('rules')
  })

  it('URL 里是不认识的值时退回默认值', () => {
    route.query = { tab: 'nope' }
    expect(useTabQuery(TABS, 'template').value).toBe('template')
  })

  it('切 tab 用 replace 写回 URL，并保留其他 query', async () => {
    route.query = { foo: '1' }
    const tab = useTabQuery(TABS, 'template')
    tab.value = 'test'
    await nextTick()
    expect(replace).toHaveBeenCalledWith({ query: { foo: '1', tab: 'test' } })
  })

  it('浏览器前进后退改了 query 时跟上', async () => {
    const tab = useTabQuery(TABS, 'template')
    route.query = { tab: 'rules' }
    await nextTick()
    expect(tab.value).toBe('rules')
  })
})
