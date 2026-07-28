import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import type { Component } from 'vue'
import NProgress from 'nprogress'
import 'nprogress/nprogress.css'
import Cookies from 'js-cookie'
import { ElMessage } from 'element-plus'
import type { MenuRoute } from '@/stores/user'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'
import { createDeviceView } from './deviceView'

NProgress.configure({ showSpinner: false })

export const Layout = () => import('@/layouts/DesktopLayout.vue')

export const constantRoutes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/auth/Login.vue'),
    meta: { title: '登录', hidden: true }
  },
  {
    path: '/404',
    name: 'NotFound',
    component: () => import('@/views/error/404.vue'),
    meta: { title: '404', hidden: true }
  },
  {
    path: '/',
    redirect: '/dashboard'
  },
  {
    path: '/dashboard',
    name: 'Dashboard',
    component: () => import('@/views/dashboard/index.vue'),
    meta: { title: '首页', icon: 'Odometer' }
  },
  {
    path: '/system/dict/type',
    name: 'DictType',
    component: () => import('@/views/system/dict/type/index.vue'),
    meta: { title: '字典管理', icon: 'List', hidden: false }
  },
  {
    path: '/system/dict/data',
    name: 'DictData',
    component: () => import('@/views/system/dict/data/index.vue'),
    meta: { title: '字典数据', hidden: false }
  }
]

// PC 与移动端各有一套实现的页面，交给 createDeviceView 在运行时按 device 选择，
// 路由表本身不再区分设备。
const componentMap: Record<string, Component | (() => Promise<any>)> = {
  'Layout': () => import('@/layouts/DesktopLayout.vue'),
  'system/dict/type/index': () => import('@/views/system/dict/type/index.vue'),
  'system/dict/data/index': () => import('@/views/system/dict/data/index.vue'),
  'system/dict/index': () => import('@/views/system/dict/type/index.vue'),
  'system/config/index': () => import('@/views/system/config/index.vue'),
  'monitor/job/index': () => import('@/views/monitor/job/index.vue'),
  'monitor/log/index': () => import('@/views/monitor/log/realtime.vue'),
  'openlist/strmTask/index': createDeviceView(
    () => import('@/views/openlist/strmTask/index.vue'),
    () => import('@/views-mobile/strmTask/index.vue')
  ),
  'openlist/strmRecord/index': createDeviceView(
    () => import('@/views/openlist/strmRecord/index.vue'),
    () => import('@/views-mobile/strmRecord/index.vue')
  ),
  'openlist/copyTask/index': createDeviceView(
    () => import('@/views/openlist/copyTask/index.vue'),
    () => import('@/views-mobile/copyTask/index.vue')
  ),
  'openlist/copyRecord/index': createDeviceView(
    () => import('@/views/openlist/copyRecord/index.vue'),
    () => import('@/views-mobile/copyRecord/index.vue')
  ),
  'openlist/renameTask/index': createDeviceView(
    () => import('@/views/openlist/renameTask/index.vue'),
    () => import('@/views-mobile/renameTask/index.vue')
  ),
  'openlist/renameDetail/index': createDeviceView(
    () => import('@/views/openlist/renameDetail/index.vue'),
    () => import('@/views-mobile/renameDetail/index.vue')
  ),
  'openlist/renameOrphan/index': createDeviceView(
    () => import('@/views/openlist/renameOrphan/index.vue'),
    () => import('@/views-mobile/renameOrphan/index.vue')
  ),
  'openlist/renameConfig/index': () => import('@/views/openlist/renameConfig/index.vue'),
  'openlist/ptIndexer/index': createDeviceView(
    () => import('@/views/openlist/ptIndexer/index.vue'),
    () => import('@/views-mobile/ptIndexer/index.vue')
  ),
  'openlist/ptDownloader/index': createDeviceView(
    () => import('@/views/openlist/ptDownloader/index.vue'),
    () => import('@/views-mobile/ptDownloader/index.vue')
  ),
  'openlist/ptMediaServer/index': createDeviceView(
    () => import('@/views/openlist/ptMediaServer/index.vue'),
    () => import('@/views-mobile/ptMediaServer/index.vue')
  ),
  'openlist/ptSubscription/index': createDeviceView(
    () => import('@/views/openlist/ptSubscription/index.vue'),
    () => import('@/views-mobile/ptSubscription/index.vue')
  ),
  'openlist/ptFilterConfig/index': () => import('@/views/openlist/ptFilterConfig/index.vue'),
  'openlist/ptDownloadRecord/index': createDeviceView(
    () => import('@/views/openlist/ptDownloadRecord/index.vue'),
    () => import('@/views-mobile/ptDownloadRecord/index.vue')
  ),
  'openlist/ptStatsDashboard/index': () => import('@/views/openlist/ptStatsDashboard/index.vue'),
  'openlist/ptTorrentBlacklist/index': () => import('@/views/openlist/ptTorrentBlacklist/index.vue')
}

/**
 * Normalize backend component path to consistent desktop format (openlist/...).
 * Backend may send different path formats depending on menu configuration version:
 *   - "openlist/xxx/index" (canonical)
 *   - "openliststrm/copy/index" (legacy alias)
 *   - "openlist/xxx" (without /index)
 */
function normalizeComponentPath(component: string): string {
  if (!component) return component

  // Already in canonical format
  if (component.startsWith('views/openlist/')) return component

  // Legacy openliststrm aliases -> desktop component paths
  const aliasMap: Record<string, string> = {
    'openliststrm/task/index': 'openlist/copyTask/index',
    'openliststrm/copy/index': 'openlist/copyRecord/index',
    'openliststrm/strm_task/index': 'openlist/strmTask/index',
    'openliststrm/strm/index': 'openlist/strmRecord/index',
    'openliststrm/renameTask/index': 'openlist/renameTask/index',
    'openliststrm/renameDetail/index': 'openlist/renameDetail/index',
  }
  if (aliasMap[component]) return aliasMap[component]

  // 'openlist/xxx/index' format (from DB url like /openlist/copy)
  // Map to the correct component: openlist/copyRecord, openlist/strmTask, etc.
  const directPathMap: Record<string, string> = {
    'openlist/copy/index': 'openlist/copyRecord/index',
    'openlist/copyTask/index': 'openlist/copyTask/index',
    'openlist/strmTask/index': 'openlist/strmTask/index',
    'openlist/strmRecord/index': 'openlist/strmRecord/index',
    'openlist/renameTask/index': 'openlist/renameTask/index',
    'openlist/renameDetail/index': 'openlist/renameDetail/index',
    'openlist/copy/index/index': 'openlist/copyRecord/index',
    'openlist/strmTask/index/index': 'openlist/strmTask/index',
    'openlist/strmRecord/index/index': 'openlist/strmRecord/index',
    'openlist/copyTask/index/index': 'openlist/copyTask/index',
    'openlist/renameTask/index/index': 'openlist/renameTask/index',
    'openlist/renameDetail/index/index': 'openlist/renameDetail/index',
  }
  if (directPathMap[component]) return directPathMap[component]

  // Direct openlist/xxx -> ensure /index suffix
  if (component.startsWith('openlist/')) {
    return component.endsWith('/index')
      ? component
      : `${component}/index`
  }

  return component
}

/**
 * 需要缓存的列表页。这些页面都带筛选条件与分页，返回时若重新挂载会丢失
 * 筛选、页码和滚动位置，并多打一次接口——移动端来回切换尤其明显。
 */
const KEEP_ALIVE_COMPONENTS = new Set([
  'openlist/strmTask/index',
  'openlist/strmRecord/index',
  'openlist/copyTask/index',
  'openlist/copyRecord/index',
  'openlist/renameTask/index',
  'openlist/renameDetail/index',
  'openlist/renameOrphan/index'
])

function convertMenuToRoute(menu: MenuRoute): RouteRecordRaw {
  const children = menu.children && menu.children.length > 0
    ? menu.children.map(child => convertMenuToRoute(child))
    : []

  // 归一化成 openlist/xxx/index 这一种写法后再查表；PC / 移动端的选择由组件自己负责
  const componentPath = normalizeComponentPath(menu.component || '')

  const component = componentMap[componentPath] || (() => import('@/views/error/404.vue'))

  const isLayout = menu.component === 'Layout'
  const routePath = menu.path || ''
  const route: RouteRecordRaw = {
    path: routePath.startsWith('/') ? routePath : '/' + routePath,
    name: menu.name,
    component: isLayout ? Layout : component,
    meta: {
      title: menu.meta?.title || '',
      icon: menu.meta?.icon || '',
      hidden: menu.hidden || false,
      isParentLayout: isLayout,
      keepAlive: KEEP_ALIVE_COMPONENTS.has(componentPath)
    },
    children
  }

  if (children.length === 0 && menu.redirect && typeof menu.redirect === 'string' && menu.redirect.startsWith('/')) {
    route.redirect = menu.redirect
  }

  return route
}

function extractLeafRoutes(menus: MenuRoute[]): MenuRoute[] {
  const leaves: MenuRoute[] = []
  for (const menu of menus) {
    if (menu.component === 'Layout' && menu.children?.length) {
      leaves.push(...extractLeafRoutes(menu.children))
    } else {
      leaves.push(menu)
    }
  }
  return leaves
}

export function addDynamicRoutes(menuList: MenuRoute[]) {
  if (!Array.isArray(menuList) || menuList.length === 0) {
    return
  }

  const leafMenus = extractLeafRoutes(menuList)

  for (const menu of leafMenus) {
    try {
      const route = convertMenuToRoute(menu)
      const existing = router.getRoutes().find(r => r.path === route.path && r.name === route.name)
      if (existing) {
        continue
      }
      router.addRoute(route)
    } catch (e) {
      console.error('[router] failed to add route for menu:', menu, e)
    }
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: constantRoutes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach(async (to, _from, next) => {
  NProgress.start()
  const title = to.meta?.title || ''
  if (title) {
      document.title = `${title} - OSR`
  }

  const appStore = useAppStore()
  const isMobile = window.innerWidth < 768
  const newDevice = isMobile ? 'mobile' : 'desktop'

  if (appStore.device !== newDevice) {
    appStore.toggleDevice(newDevice)
  }

  const hasToken = Cookies.get('token')

  if (hasToken) {
    if (to.path === '/login') {
      next({ path: '/' })
      NProgress.done()
    } else {
      const userStore = useUserStore()
      try {
        if (!userStore.routes.length) {
          await userStore.getUserInfo()
          const menuRoutes = await userStore.getRouters()
          if (menuRoutes && (menuRoutes as any).length > 0) {
            addDynamicRoutes(menuRoutes as MenuRoute[])
            // 动态路由刚注册完，重新触发一次导航让它生效。
            // 必须走 next()，直接 router.replace() 后 return 会让本次守卫没有结局，
            // vue-router 会抛 "Invalid navigation guard"。
            next({ ...to, replace: true })
            return
          }
        }
        next()
      } catch (e) {
        console.error('[router] guard error:', e)
        userStore.clearToken()
        next('/login')
      }
    }
  } else {
    if (to.meta?.hidden !== true && to.path !== '/login') {
      next(`/login?redirect=${to.path}`)
    } else {
      next()
    }
  }
})

// 记录上一次因 chunk 失效触发的硬刷新，避免刷新后仍失败时无限循环
const CHUNK_RELOAD_KEY = 'osr:chunk-reload'
const CHUNK_RELOAD_WINDOW = 30_000

type ChunkReloadMark = { path: string; at: number }

function readChunkReloadMark(): ChunkReloadMark | null {
  try {
    const raw = sessionStorage.getItem(CHUNK_RELOAD_KEY)
    return raw ? JSON.parse(raw) as ChunkReloadMark : null
  } catch {
    return null
  }
}

router.afterEach((to) => {
  NProgress.done()

  // 目标路由已经能正常打开，说明硬刷新救回来了，清掉标记
  const mark = readChunkReloadMark()
  if (mark && mark.path === to.fullPath) {
    sessionStorage.removeItem(CHUNK_RELOAD_KEY)
  }
})

// 兜底拦截：旧版 JS Chunk 已失效时强制硬刷新，避免登录页卡死
router.onError((error, to) => {
  const isChunkError =
    error.message.includes('Failed to fetch dynamically imported module') ||
    error.message.includes('Importing a module script failed') ||
    error.name === 'ChunkLoadError'

  if (!isChunkError) return

  // 刚为同一个路由刷新过却又失败，多半不是版本问题（断网 / 缓存损坏）。
  // PWA standalone 下没有地址栏可以中止循环，这里必须主动停手。
  const mark = readChunkReloadMark()
  if (mark && mark.path === to.fullPath && Date.now() - mark.at < CHUNK_RELOAD_WINDOW) {
    sessionStorage.removeItem(CHUNK_RELOAD_KEY)
    console.error('[router] 刷新后仍无法加载页面资源，停止自动刷新', error)
    ElMessage.error('页面资源加载失败，请检查网络后重试')
    return
  }

  console.warn('[router] 检测到旧资源失效，强制刷新页面...')
  try {
    sessionStorage.setItem(CHUNK_RELOAD_KEY, JSON.stringify({ path: to.fullPath, at: Date.now() }))
  } catch {
    // sessionStorage 不可用（隐私模式等）时仍然刷新，只是失去防循环能力
  }
  window.location.href = to.fullPath
})

export default router
