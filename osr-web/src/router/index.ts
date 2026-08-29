import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import type { Component } from 'vue'
import NProgress from 'nprogress'
import 'nprogress/nprogress.css'
import Cookies from 'js-cookie'
import { message } from '@/composables/useMessage'
import type { MenuRoute } from '@/stores/user'
import { useUserStore } from '@/stores/user'
import { createDeviceView } from './deviceView'
import { isChunkLoadError } from './chunkError'

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
  }
]

// PC 与移动端各有一套实现的页面，交给 createDeviceView 在运行时按 device 选择，
// 路由表本身不再区分设备。
const componentMap: Record<string, Component | (() => Promise<any>)> = {
  'Layout': () => import('@/layouts/DesktopLayout.vue'),
  'system/config/index': () => import('@/views/system/config/index.vue'),
  // 与参数设置一样只有 PC 一套：签发令牌要复制一长串明文和一条命令行，
  // 那是坐在电脑前才做得了的事，为它单做一套移动端界面没有意义
  'system/mcpToken/index': () => import('@/views/system/mcpToken/index.vue'),
  'system/notifyRoute/index': createDeviceView(
    () => import('@/views/system/notifyRoute/index.vue'),
    () => import('@/views-mobile/notifyRoute/index.vue')
  ),
  'system/wecomUser/index': createDeviceView(
    () => import('@/views/system/wecomUser/index.vue'),
    () => import('@/views-mobile/wecomUser/index.vue')
  ),
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
  'openlist/renameConfig/index': createDeviceView(
    () => import('@/views/openlist/renameConfig/index.vue'),
    () => import('@/views-mobile/renameConfig/index.vue')
  ),
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
  'openlist/ptUpgradeConfig/index': () => import('@/views/openlist/ptUpgradeConfig/index.vue'),
  'openlist/ptDownloadRecord/index': createDeviceView(
    () => import('@/views/openlist/ptDownloadRecord/index.vue'),
    () => import('@/views-mobile/ptDownloadRecord/index.vue')
  ),
  'openlist/ptStatsDashboard/index': createDeviceView(
    () => import('@/views/openlist/ptStatsDashboard/index.vue'),
    () => import('@/views-mobile/ptStatsDashboard/index.vue')
  ),
  'openlist/ptTorrentBlacklist/index': createDeviceView(
    () => import('@/views/openlist/ptTorrentBlacklist/index.vue'),
    () => import('@/views-mobile/ptTorrentBlacklist/index.vue')
  ),
  'openlist/ptAutoAddRule/index': createDeviceView(
    () => import('@/views/openlist/ptAutoAddRule/index.vue'),
    () => import('@/views-mobile/ptAutoAddRule/index.vue')
  ),
  'openlist/ptCalendar/index': createDeviceView(
    () => import('@/views/openlist/ptCalendar/index.vue'),
    () => import('@/views-mobile/ptCalendar/index.vue')
  ),
  'openlist/ptHealth/index': createDeviceView(
    () => import('@/views/openlist/ptHealth/index.vue'),
    () => import('@/views-mobile/ptHealth/index.vue')
  ),
  'openlist/ptTransferRule/index': createDeviceView(
    () => import('@/views/openlist/ptTransferRule/index.vue'),
    () => import('@/views-mobile/ptTransferRule/index.vue')
  )
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
      keepAlive: KEEP_ALIVE_COMPONENTS.has(componentPath),
      // 归一化后的 componentMap key，供 getRoutePathForComponent 反查（见其注释）
      componentKey: componentPath
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

/**
 * 根据 componentMap 的 key（如 'openlist/strmRecord/index'）找到当前已注册的路由 path。
 * 菜单 path 由后端 DB 配置，历史遗留数据前缀不统一（/openliststrm/xxx、/openlist/xxx 都存在），
 * 不能靠字符串猜测。
 *
 * 注册路由时把归一化后的 key 记在 meta.componentKey 上，这里按 key 反查。
 * 不用「比对组件对象引用」：Vite HMR 下模块可能被重新求值，componentMap 里的对象
 * 与已注册路由上挂的对象会变成两个实例，引用比对就会全部落空。
 */
export function getRoutePathForComponent(componentKey: string): string | null {
  if (!componentMap[componentKey]) return null
  for (const r of router.getRoutes()) {
    if (r.meta?.componentKey === componentKey) return r.path
  }
  return null
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
  /**
   * 浏览器/手势「返回」时恢复原来的滚动位置，其余导航一律回到顶部。
   *
   * 列表页本来就带 keep-alive（筛选条件、页码都还在），唯独滚动位置每次回来都归零——
   * 从第 30 条点进详情再返回，要重新滑一遍才能接着看，这在移动端尤其明显。
   * savedPosition 只有 popstate（返回/前进）才有值，所以正常点击导航仍然是 top: 0。
   * 延一帧再滚：页面组件是异步加载的，keep-alive 恢复 DOM 也在下一帧，
   * 立即滚动会因为此刻文档还没那么高而被截断成「滚到底部」。
   */
  scrollBehavior(_to, _from, savedPosition) {
    if (!savedPosition) return { top: 0 }
    return new Promise((resolve) => {
      requestAnimationFrame(() => resolve(savedPosition))
    })
  }
})

router.beforeEach(async (to, _from, next) => {
  NProgress.start()
  const title = to.meta?.title || ''
  if (title) {
      document.title = `${title} - OSR`
  }

  // 设备判定**只在 App.vue** 一处做（matchMedia(MOBILE_MEDIA_QUERY) + change/resize 监听）。
  // 这里曾经另写一份 `window.innerWidth < 768`，两套判据对手机横屏（如 926×428）结论相反：
  // App.vue 判 mobile，用户一导航守卫就翻成 desktop，而 App.vue 此时不会收到
  // change/resize，布局就此卡在 desktop 直到用户转一次屏——不是闪一下，是持续判错。
  // 修法不是让守卫也用 MOBILE_MEDIA_QUERY（两处早晚漂移），是让守卫彻底不碰这件事。

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

/**
 * 记下这次硬刷新，**写不进去就返回 false，调用方必须因此放弃刷新**。
 *
 * 标记是防打转的唯一凭据（它得活过一次 location 跳转，所以只能落在 sessionStorage 上，
 * 模块级变量会随刷新一起归零）。写不进去时照样刷新的话，刷回来仍旧读不到标记、仍旧判定
 * "还没试过"、于是再刷一次——**一个停不下来的刷新循环，外加对服务端的请求风暴**，
 * 而 PWA standalone 下连地址栏都没有，用户只能杀掉应用。
 * 隐私模式的 Safari 会让 setItem 抛 QuotaExceededError，这不是假想情况。
 * 降级成"提示用户手动刷新"只是少了一次自动补救，代价小得多。
 */
function writeChunkReloadMark(path: string): boolean {
  try {
    sessionStorage.setItem(CHUNK_RELOAD_KEY, JSON.stringify({ path, at: Date.now() }))
    return true
  } catch {
    return false
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
  // 导航被 onError 中止时 afterEach 不会执行，进度条会停在 80% 一直转。
  // 这条对下面两个分支都要成立——包括"放弃重试"那条：用户此时留在旧页面上，
  // 一个永远转下去的进度条会让他以为还在加载，而实际上什么都不会再发生了。
  NProgress.done()

  if (!isChunkLoadError(error)) return

  // 刚为同一个路由刷新过却又失败，多半不是版本问题（断网 / 缓存损坏）。
  // PWA standalone 下没有地址栏可以中止循环，这里必须主动停手。
  const mark = readChunkReloadMark()
  if (mark && mark.path === to.fullPath && Date.now() - mark.at < CHUNK_RELOAD_WINDOW) {
    sessionStorage.removeItem(CHUNK_RELOAD_KEY)
    console.error('[router] 刷新后仍无法加载页面资源，停止自动刷新', error)
    message.error('页面资源加载失败，请检查网络后重试')
    return
  }

  if (!writeChunkReloadMark(to.fullPath)) {
    console.error('[router] 检测到旧资源失效，但无法记录刷新标记，改为提示用户手动刷新', error)
    message.error('页面资源已更新，请手动刷新页面')
    return
  }

  console.warn('[router] 检测到旧资源失效，强制刷新页面...')
  window.location.href = to.fullPath
})

export default router
