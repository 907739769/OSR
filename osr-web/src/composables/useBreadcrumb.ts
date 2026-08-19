import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore, type MenuRoute } from '@/stores/user'

/**
 * 面包屑：当前页在菜单树里的位置，形如「PT 追剧 / 追剧日历」。
 *
 * 放在顶栏那条一直空着的区域里。刻意不是「重复一遍页面标题」——菜单收敛成两级后，
 * 侧边栏的分组标题只是一行灰字，页面本身完全不体现自己属于哪一组；而 PT 那四个分组
 * （追剧/下载/规则/接入）恰恰是靠分组才分得清的。
 *
 * 取不到时退回 route.meta.title：常量路由（首页、字典数据）本来就不在菜单树里。
 */
export function useBreadcrumb() {
  const route = useRoute()
  const userStore = useUserStore()

  return computed<string[]>(() => {
    const target = route.path

    // 子菜单 path 可能是相对的，要拼上父级前缀（与 useMenuLinks 同一套口径）
    const walk = (menus: MenuRoute[], parentPath: string, trail: string[]): string[] | null => {
      for (const menu of menus) {
        const raw = menu.path || ''
        const path = /^https?:\/\//.test(raw) || raw.startsWith('/')
          ? raw
          : `${parentPath}/${raw}`.replace(/\/+/g, '/')
        const title = menu.meta?.title || ''

        if (menu.children?.length) {
          const hit = walk(menu.children, path, title ? [...trail, title] : trail)
          if (hit) return hit
        } else if (path === target) {
          return title ? [...trail, title] : trail
        }
      }
      return null
    }

    const hit = walk(userStore.routes, '', [])
    if (hit?.length) return hit

    const fallback = route.meta?.title as string | undefined
    return fallback ? [fallback] : []
  })
}
