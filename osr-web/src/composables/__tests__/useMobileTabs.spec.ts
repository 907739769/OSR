import { describe, it, expect, beforeEach, vi } from 'vitest'
import { computed, ref } from 'vue'

/**
 * 底栏 tab 的三条约定：
 *   1. 没配过 → 用默认那四个（按 meta.componentKey 反查真实 path，不写死）
 *   2. 配过 → 按配置顺序取，菜单里已经没有的直接丢掉（改权限/删菜单后不能留死链）
 *   3. 最多 4 个（第 5 格固定是「更多」）
 * 第 2 条是这里最值得守的：漏掉过滤的话，用户点到的是一个 404。
 */

const links = ref([
  { path: '/openliststrm/copy', title: '同步任务记录', icon: 'file' },
  { path: '/openlist/ptCalendar', title: '追剧日历', icon: 'calendar' },
  { path: '/openlist/ptSubscription', title: '订阅管理', icon: 'bell' }
])

// HOME_LINK 也要一并 mock：useMobileTabs 在**模块作用域**（DEFAULT_TABS）就读它的字段，
// 只 mock useMenuLinks 的话这里会在 import 阶段就 TypeError，且报错位置指向被测文件而不是这份 mock
vi.mock('@/composables/useMenuLinks', () => ({
  useMenuLinks: () => computed(() => links.value),
  HOME_LINK: { path: '/dashboard', title: '首页', icon: 'layout-dashboard' }
}))

vi.mock('vue-router', () => ({
  // defaultTabs 同时依赖 route.path 与菜单（见 useMobileTabs 里的注释），两个都要给
  useRoute: () => ({ path: '/dashboard' }),
  useRouter: () => ({
    getRoutes: () => [
      { path: '/openliststrm/copy', meta: { componentKey: 'openlist/copyRecord/index' } },
      { path: '/openliststrm/strm', meta: { componentKey: 'openlist/strmRecord/index' } }
    ]
  })
}))

const { useMobileTabs, MAX_TABS } = await import('@/composables/useMobileTabs')

describe('useMobileTabs', () => {
  beforeEach(() => {
    localStorage.clear()
    // 模块级单例：每个用例都从「没配过」重新开始
    useMobileTabs().setTabs(null)
  })

  it('没配过时用默认 tab，未注册的路由不出现（不留 404 死链）', () => {
    const { tabs, isCustomized } = useMobileTabs()
    expect(isCustomized.value).toBe(false)
    // 默认 4 个里，重命名记录这一条在本用例的路由表里没注册，应当被丢掉
    expect(tabs.value.map((t) => t.title)).toEqual(['首页', '同步记录', 'STRM记录'])
  })

  it('配置后按配置顺序取，并落到 localStorage', () => {
    const { tabs, setTabs, isCustomized } = useMobileTabs()
    setTabs(['/openlist/ptCalendar', '/dashboard'])

    expect(isCustomized.value).toBe(true)
    expect(tabs.value.map((t) => t.title)).toEqual(['追剧日历', '首页'])
    expect(JSON.parse(localStorage.getItem('osr-mobile-tabs')!)).toEqual([
      '/openlist/ptCalendar', '/dashboard'
    ])
  })

  it('配置里指向已经不存在的菜单时把那条丢掉，而不是渲染出一个点了 404 的 tab', () => {
    const { tabs, setTabs } = useMobileTabs()
    setTabs(['/openlist/ptCalendar', '/openlist/removed', '/dashboard'])
    expect(tabs.value.map((t) => t.path)).toEqual(['/openlist/ptCalendar', '/dashboard'])
  })

  it(`最多 ${MAX_TABS} 个（第 5 格留给「更多」）`, () => {
    const { tabs, setTabs } = useMobileTabs()
    setTabs([
      '/dashboard', '/openliststrm/copy', '/openlist/ptCalendar',
      '/openlist/ptSubscription', '/dashboard'
    ])
    expect(tabs.value).toHaveLength(MAX_TABS)
  })

  it('setTabs(null) 恢复默认并清掉存储', () => {
    const { tabs, setTabs, isCustomized } = useMobileTabs()
    setTabs(['/openlist/ptCalendar'])
    setTabs(null)
    expect(isCustomized.value).toBe(false)
    expect(localStorage.getItem('osr-mobile-tabs')).toBeNull()
    expect(tabs.value.map((t) => t.title)).toContain('首页')
  })
})
