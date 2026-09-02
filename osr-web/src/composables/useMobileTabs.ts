import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useMenuLinks, HOME_LINK, type MenuLink } from '@/composables/useMenuLinks'

/** 底栏最多放几个可跳转的 tab（第 5 个格子固定是「更多」） */
export const MAX_TABS = 4

const STORAGE_KEY = 'osr-mobile-tabs'

/**
 * 默认底栏。
 *
 * 用 componentMap 的 key 而不是写死 path：后端菜单 path 历史上有 `/openlist/xxx` 与
 * `/openliststrm/xxx` 两种前缀（见 router/index.ts 的 normalizeComponentPath），
 * 写死会让 tab 跳到 404。首页是常量路由，直接给 path。
 */
const DEFAULT_TABS: { component?: string; path?: string; label: string; icon: string }[] = [
  { path: HOME_LINK.path, label: HOME_LINK.title, icon: HOME_LINK.icon },
  { component: 'openlist/copyRecord/index', label: '同步记录', icon: 'files' },
  { component: 'openlist/strmRecord/index', label: 'STRM记录', icon: 'clapperboard' },
  { component: 'openlist/renameDetail/index', label: '重命名', icon: 'square-pen' }
]

function readStored(): string[] | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) && parsed.every((p) => typeof p === 'string') ? parsed : null
  } catch {
    // 存的东西坏了就当没配过，不要让首页整个打不开
    return null
  }
}

/** 模块级单例：设置弹窗改完，底栏要立刻跟着变 */
const selected = ref<string[] | null>(readStored())

/**
 * 移动端底栏 tab。
 *
 * 「哪四个页面最常用」因人而异——只用 STRM 的用户和只用 PT 的用户完全不同，
 * 而这份清单原先写死在 MobileLayout 里，改一次要发一次前端版本。
 * 现在默认值仍是下面那四个，用户可在「更多 → 自定义底栏」里换成自己的。
 */
export function useMobileTabs() {
  const router = useRouter()
  const route = useRoute()
  const menuLinks = useMenuLinks()

  /** 首页 + 当前用户有权限的全部菜单叶子，设置弹窗与底栏共用这一份。
      首页是常量路由、不在后端菜单树里，所以要单独补——定义收口在 HOME_LINK */
  const allLinks = computed<MenuLink[]>(() => [HOME_LINK, ...menuLinks.value])

  /**
   * 未注册 / 未授权的 tab 直接隐藏，而不是留一个点了报 404 的死链。
   *
   * router.getRoutes() 不是响应式的（动态路由是登录后才注册的），所以这里同时依赖
   * route.path 与 menuLinks：路由注册完守卫会再导航一次（`next({ ...to, replace: true })`），
   * route.path 变化能保证 computed 至少重算一次，不会停在「只剩首页」的空结果上。
   * **这两个依赖缺一不可**：只留 menuLinks 时实测底栏只剩「首页 + 更多」——菜单落地
   * 与动态路由注册在同一个守卫里，菜单先到、computed 先算了一遍，之后就再没被 invalidate。
   * 用 meta.componentKey 而不是组件对象引用比对——后者在 HMR 下会失效。
   */
  const defaultTabs = computed(() => {
    void route.path
    void menuLinks.value.length

    const registered = new Map<string, string>()
    for (const r of router.getRoutes()) {
      const key = r.meta?.componentKey as string | undefined
      if (key && !registered.has(key)) registered.set(key, r.path)
    }

    const tabs: MenuLink[] = []
    for (const def of DEFAULT_TABS) {
      const path = def.component ? registered.get(def.component) : def.path
      if (path) tabs.push({ path, title: def.label, icon: def.icon })
    }
    return tabs
  })

  const tabs = computed<MenuLink[]>(() => {
    if (!selected.value) return defaultTabs.value
    // 用户配过：按配置顺序取，菜单里已经没有的（改过权限/删过菜单）直接丢掉
    return selected.value
      .map((path) => allLinks.value.find((l) => l.path === path))
      .filter((l): l is MenuLink => !!l)
      .slice(0, MAX_TABS)
  })

  /** 传空数组或 null 都表示「恢复默认」 */
  const setTabs = (paths: string[] | null) => {
    if (!paths || !paths.length) {
      selected.value = null
      localStorage.removeItem(STORAGE_KEY)
      return
    }
    const next = paths.slice(0, MAX_TABS)
    selected.value = next
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next))
  }

  const isCustomized = computed(() => selected.value !== null)

  return { tabs, allLinks, setTabs, isCustomized, defaultTabs }
}
