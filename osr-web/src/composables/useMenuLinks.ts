import { computed } from 'vue'
import { useUserStore, type MenuRoute } from '@/stores/user'
import { getIconComponent } from '@/composables/useMenuIcon'

export interface MenuLink {
  path: string
  title: string
  icon: string
}

/**
 * 拍平菜单树取叶子节点。后端顶层是 Layout 容器，真正能跳的是它的 children，
 * 子菜单 path 可能是相对的，需要拼上父级前缀（与 MobileLayout 的取值口径一致）。
 */
function flattenMenus(menus: MenuRoute[], parentPath = ''): MenuLink[] {
  const result: MenuLink[] = []
  for (const menu of menus) {
    const raw = menu.path || ''
    // 绝对路径（/ 开头）与外部链接（http(s)://）原样保留，相对路径拼父级前缀
    const path = /^https?:\/\//.test(raw) || raw.startsWith('/')
      ? raw
      : `${parentPath}/${raw}`.replace(/\/+/g, '/')

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
