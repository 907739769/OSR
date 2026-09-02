import { describe, it, expect, beforeEach } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useUserStore, type MenuRoute } from '@/stores/user'
import { useMenuGroups, useMenuLinks, HOME_LINK } from '@/composables/useMenuLinks'

/**
 * 「更多」面板的图标格按分组铺，数据来自 `useMenuGroups`。
 *
 * 它与 `useMenuLinks` 必须给出**同一批可跳路径**——两者共用 `resolvePath`
 * 就是为了这个。漂移的表现是「同一个菜单在底栏能跳、在更多面板里 404」，
 * 而两边的代码看着都对。
 */
const routes: MenuRoute[] = [
  {
    path: '/openliststrm',
    component: 'Layout',
    meta: { title: '网盘同步' },
    children: [
      { path: 'copy', meta: { title: '同步任务记录', icon: 'files' } },
      // 相对 path 要拼上父级前缀
      { path: 'copyTask', meta: { title: '同步任务配置', icon: 'cloud' } }
    ]
  },
  {
    path: '/pt',
    component: 'Layout',
    meta: { title: 'PT 追剧' },
    children: [
      { path: '/openlist/ptCalendar', meta: { title: '追剧日历', icon: 'calendar-days' } },
      { path: 'sub', hidden: true, meta: { title: '不该出现的' } }
    ]
  },
  // 整组子项都被隐藏：不要留一个空标题
  {
    path: '/empty',
    component: 'Layout',
    meta: { title: '空分组' },
    children: [{ path: 'x', hidden: true, meta: { title: '隐藏' } }]
  },
  // 顶层直接可跳的菜单（首页不在这里——它是常量路由，由 HOME_LINK 补进来）
  { path: '/tools', meta: { title: '小工具', icon: 'wrench' } },
  // 整条隐藏
  { path: '/hidden', hidden: true, meta: { title: '藏起来的' } }
]

describe('useMenuGroups', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    useUserStore().routes = routes
  })

  it('保留一层分组，子菜单 path 拼上父级前缀', () => {
    const groups = useMenuGroups().value
    const named = groups.filter((g) => g.title)

    expect(named.map((g) => g.title)).toEqual(['网盘同步', 'PT 追剧'])
    expect(named[0].items.map((i) => i.path)).toEqual([
      '/openliststrm/copy',
      '/openliststrm/copyTask'
    ])
    // 子菜单自己就是绝对路径时原样保留，不再拼前缀
    expect(named[1].items.map((i) => i.path)).toEqual(['/openlist/ptCalendar'])
  })

  it('首页与顶层直接可跳的菜单归到没有标题的一组并排在最前', () => {
    const groups = useMenuGroups().value
    expect(groups[0].title).toBe('')
    expect(groups[0].items.map((i) => i.title)).toEqual(['首页', '小工具'])
  })

  it('首页始终在，哪怕后端菜单树里一条都没有——它是常量路由', () => {
    useUserStore().routes = []
    const groups = useMenuGroups().value
    expect(groups).toHaveLength(1)
    expect(groups[0].items).toEqual([HOME_LINK])
  })

  it('后端菜单里也配了 /dashboard 时不出现两个「首页」', () => {
    useUserStore().routes = [
      { path: HOME_LINK.path, meta: { title: '首页', icon: 'layout-dashboard' } }
    ]
    const paths = useMenuGroups().value.flatMap((g) => g.items).map((i) => i.path)
    expect(paths.filter((p) => p === HOME_LINK.path)).toHaveLength(1)
  })

  it('隐藏的菜单不出现，整组都被隐藏时不留空标题', () => {
    const all = useMenuGroups().value.flatMap((g) => g.items)
    expect(all.map((i) => i.title)).not.toContain('不该出现的')
    expect(all.map((i) => i.title)).not.toContain('藏起来的')
    expect(useMenuGroups().value.map((g) => g.title)).not.toContain('空分组')
  })

  it('除首页外与 useMenuLinks 给出同一批可跳路径（两处共用 resolvePath，不许漂移）', () => {
    const flat = useMenuLinks().value.map((i) => i.path).sort()
    const grouped = useMenuGroups()
      .value.flatMap((g) => g.items)
      .map((i) => i.path)
      .filter((p) => p !== HOME_LINK.path)
      .sort()
    expect(grouped).toEqual(flat)
  })
})
