import { computed } from 'vue'
import { useUserStore, type MenuRoute } from '@/stores/user'
import { getIconComponent } from '@/composables/useMenuIcon'

export interface MenuLink {
  path: string
  title: string
  icon: string
}

/**
 * 首页。它是**常量路由**（`router/index.ts` 里写死的），不在后端 `getRouters` 下发的菜单树里，
 * 所以任何「把菜单铺出来」的地方都得自己补上它。
 *
 * 收口成一个常量是因为它此前在 `useMobileTabs` 里就写了两遍（`DEFAULT_TABS` 一遍、
 * `allLinks` 一遍），而移动端「更多」面板改用 `useMenuGroups` 之后是第三处——
 * 漏补的表现是「把首页从底栏拿掉之后，整个应用里再也回不到首页」，而且不报任何错。
 */
export const HOME_LINK: MenuLink = {
  path: '/dashboard',
  title: '首页',
  icon: 'layout-dashboard'
}

export interface MenuGroup {
  /** 分组标题。顶层就能直接跳的菜单归到标题为空的那一组，渲染时不画标题 */
  title: string
  items: MenuLink[]
}

/**
 * 菜单 path 的归一化。绝对路径（/ 开头）与外部链接（http(s)://）原样保留，
 * 相对路径拼父级前缀。
 *
 * 单独提出来是因为 `flattenMenus` 与 `useMenuGroups` 都要算它：两处各写一遍的话，
 * 漂移的表现是「同一个菜单在底栏能跳、在更多面板里 404」，而两边看着都对。
 */
function resolvePath(raw: string, parentPath: string): string {
  return /^https?:\/\//.test(raw) || raw.startsWith('/')
    ? raw
    : `${parentPath}/${raw}`.replace(/\/+/g, '/')
}

/**
 * 拍平菜单树取叶子节点。后端顶层是 Layout 容器，真正能跳的是它的 children，
 * 子菜单 path 可能是相对的，需要拼上父级前缀（与 MobileLayout 的取值口径一致）。
 */
function flattenMenus(menus: MenuRoute[], parentPath = ''): MenuLink[] {
  const result: MenuLink[] = []
  for (const menu of menus) {
    const raw = menu.path || ''
    const path = resolvePath(raw, parentPath)

    if (menu.children?.length) {
      result.push(...flattenMenus(menu.children, path))
    } else if (menu.hidden !== true && menu.path) {
      result.push({
        path,
        title: menu.meta?.title || '',
        icon: getIconComponent(menu.meta?.icon) || 'menu'
      })
    }
  }
  return result
}

/** 当前用户菜单的叶子链接（快捷入口等场景共用，避免各端写死路径） */
export function useMenuLinks() {
  const userStore = useUserStore()
  return computed(() => flattenMenus(userStore.routes))
}

/**
 * 同一份菜单，但**保留一层分组**，给移动端「更多」面板的图标格用。
 *
 * `useMenuLinks` 把整棵树拍平成叶子，那对底栏配置、快捷入口是对的（它们只要一个
 * 可跳的清单）；但面板要按「网盘同步 / PT 追剧 / PT 规则…」分块铺，四列图标格靠
 * 分组标题才扫得动——拍平之后 25 个格子连成一片，比原先那份列表还难找。
 *
 * 只保留**一层**：后端菜单本来就只有两级（20260752 把三级收敛掉了），
 * 再递归下去也没有第三层可分。首页与顶层直接可跳的菜单归到标题为空的一组并排在最前，
 * 不给它编一个「其他」之类的假标题——无标题的首块是列表界面的常见形态，编一个反而更怪。
 *
 * **首页必须由这里补进来**（`HOME_LINK`）：它是常量路由、不在后端菜单树里，而这份数据
 * 是「更多」面板唯一的来源。旧的侧边抽屉是把「首页」硬编码成第一条 `v-list-item` 的，
 * 换实现时最容易连着那行一起丢掉，症状是把首页从底栏移除后再也回不去。
 */
export function useMenuGroups() {
  const userStore = useUserStore()
  return computed<MenuGroup[]>(() => {
    const groups: MenuGroup[] = []
    const loose: MenuLink[] = [HOME_LINK]

    for (const menu of userStore.routes) {
      if (menu.hidden === true || !menu.path) continue
      if (menu.children?.length) {
        const items = flattenMenus(menu.children, resolvePath(menu.path, ''))
        // 整组子项都被隐藏时不要留一个空标题
        if (items.length) groups.push({ title: menu.meta?.title || '', items })
      } else {
        loose.push(...flattenMenus([menu]))
      }
    }

    // 后端菜单里万一也配了 /dashboard，去重，免得面板上出现两个「首页」
    const seen = new Set<string>()
    const uniqueLoose = loose.filter((l) => (seen.has(l.path) ? false : seen.add(l.path)))

    return [{ title: '', items: uniqueLoose }, ...groups]
  })
}
