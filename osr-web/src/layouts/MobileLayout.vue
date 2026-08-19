<template>
  <v-navigation-drawer v-model="menuOpen" temporary width="280">
    <div class="drawer-header">
      <img src="/icons/android-chrome-192x192.png" alt="Logo" class="drawer-logo" />
      <span class="drawer-title">OSR</span>
    </div>
    <v-list nav density="compact" :opened="openedGroups" style="--v-list-prepend-gap: 12px" @click:select="menuOpen = false">
      <v-list-item to="/dashboard" prepend-icon="mdi-view-dashboard-outline" title="首页" rounded="lg" class="menu-item" @click="menuOpen = false" />
      <MobileSidebarMenuItem v-for="menu in sidebarMenus" :key="menu.path" :menu="menu" />
    </v-list>
  </v-navigation-drawer>

  <v-app-bar flat density="compact" height="50">
    <v-app-bar-nav-icon @click="menuOpen = !menuOpen" />
    <v-app-bar-title>{{ pageTitle }}</v-app-bar-title>
    <ThemeSwitch />
    <v-avatar size="28" color="primary" class="mr-3" @click="showPasswordDialog = true">管</v-avatar>
    <v-icon icon="mdi-logout" class="mr-2" @click="handleLogout" />
  </v-app-bar>

  <v-main>
    <div class="mobile-content">
      <!-- 同 DesktopLayout：原先的 <transition name="fade"> 既没有配套 CSS，
           又会让旧页面残留在新页面下方，这里一并去掉，原因见 DesktopLayout 的注释 -->
      <router-view v-slot="{ Component, route: currentRoute }">
        <keep-alive v-if="currentRoute.meta?.keepAlive" :max="6">
          <component :is="Component" :key="currentRoute.path" />
        </keep-alive>
        <component v-else :is="Component" :key="currentRoute.path" />
      </router-view>
    </div>

    <v-bottom-navigation :model-value="activeTab" grow height="56" class="mobile-tabbar">
      <v-btn
        v-for="tab in mainTabs"
        :key="tab.path"
        :value="tab.path"
        class="tabbar-item"
        @click="router.push(tab.path)"
      >
        <v-icon :icon="tab.icon" />
        <span>{{ tab.label }}</span>
      </v-btn>
    </v-bottom-navigation>
  </v-main>

  <ChangePasswordDialog v-model:visible="showPasswordDialog" />
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { confirm } from '@/composables/useConfirm'
import { useUserStore, type MenuRoute } from '@/stores/user'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'
import MobileSidebarMenuItem from '@/components/MobileSidebarMenuItem.vue'
import ThemeSwitch from '@/components/ThemeSwitch.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const menuOpen = ref(false)
const showPasswordDialog = ref(false)

const sidebarMenus = computed(() => userStore.routes)

// 展开当前路由所在的分类，避免用户切页后要重新点开折叠面板才能看到自己在哪
function containsPath(menu: MenuRoute, targetPath: string): boolean {
  if (menu.path === targetPath) return true
  return !!menu.children?.some((child) => containsPath(child, targetPath))
}

function collectAncestorGroupIds(menus: MenuRoute[], targetPath: string): string[] {
  const ids: string[] = []
  for (const menu of menus) {
    if (!menu.children?.length) continue
    if (containsPath(menu, targetPath)) {
      ids.push(menu.name || menu.path)
      ids.push(...collectAncestorGroupIds(menu.children, targetPath))
    }
  }
  return ids
}

const openedGroups = computed(() => collectAncestorGroupIds(sidebarMenus.value, route.path))

const pageTitle = computed(() => (route.meta?.title as string) || 'OSR')

// 底部主 tab（最常用的四个页面）。
// 路径不能写死：后端菜单 path 历史上有 /openlist/xxx 与 /openliststrm/xxx 两种前缀
// （见 router/index.ts 的 normalizeComponentPath），写死会让 tab 跳到 404。
// 改为按注册路由上的 meta.componentKey 反查真实 path。
interface TabDef {
  /** componentMap 的 key；为空表示用固定 path（首页是常量路由） */
  component?: string
  path?: string
  label: string
  icon: string
}

const TAB_DEFS: TabDef[] = [
  { path: '/dashboard', label: '首页', icon: 'mdi-view-dashboard-outline' },
  { component: 'openlist/copyRecord/index', label: '同步记录', icon: 'mdi-file-multiple-outline' },
  { component: 'openlist/strmRecord/index', label: 'STRM记录', icon: 'mdi-movie-open-outline' },
  { component: 'openlist/renameDetail/index', label: '重命名记录', icon: 'mdi-pencil-outline' }
]

// 菜单未授权 / 未注册的 tab 直接隐藏，而不是留一个点了报 404 的死链。
//
// 两个坑：
// 1. router.getRoutes() 不是响应式的，动态路由是登录后才注册的。这里同时依赖
//    route.path 与 sidebarMenus —— 路由注册完守卫会再导航一次（next({...to, replace:true})），
//    route.path 变化能保证 computed 至少重算一次，不会停在「只剩首页」的空结果上。
// 2. 用组件对象引用比对会在 HMR 下失效，所以走 meta.componentKey。
const mainTabs = computed(() => {
  void route.path
  void sidebarMenus.value.length

  const registered = new Map<string, string>()
  for (const r of router.getRoutes()) {
    const key = r.meta?.componentKey as string | undefined
    if (key && !registered.has(key)) registered.set(key, r.path)
  }

  const tabs: { label: string; icon: string; path: string }[] = []
  for (const tab of TAB_DEFS) {
    const path = tab.component ? registered.get(tab.component) : tab.path
    if (path) tabs.push({ label: tab.label, icon: tab.icon, path })
  }
  return tabs
})

const activeTab = computed(() => {
  const match = mainTabs.value.find((tab) => {
    if (tab.path === '/dashboard') return route.path === '/dashboard'
    return route.path.startsWith(tab.path)
  })
  return match?.path
})

const handleLogout = async () => {
  try {
    await confirm({ message: '确定要退出登录吗？', title: '提示', type: 'warning' })
    await userStore.logout()
    router.push('/login')
  } catch {
    // cancelled
  }
}
</script>

<style scoped lang="scss">
.drawer-header {
  display: flex;
  align-items: center;
  padding: 16px;

  .drawer-logo {
    width: 32px;
    height: 32px;
    margin-right: 10px;
  }

  .drawer-title {
    font-size: 18px;
    font-weight: 700;
    letter-spacing: 0.5px;
  }
}

:deep(.menu-item) {
  margin: 1px 6px;
  min-height: 44px;

  &.v-list-item--active {
    color: rgb(var(--v-theme-primary));
    background: rgba(var(--v-theme-primary), 0.1);
  }
}

.mobile-content {
  /* 左右内距取 tokens.scss 的 --osr-mobile-gutter：各页做「常驻顶部」时要用负边距
     把底色铺满整宽，两处必须是同一个值。顶栏高度同理，见该文件的 --osr-mobile-appbar-height
     （改上面 <v-app-bar height="50"> 时那个令牌要一起改） */
  padding: var(--osr-mobile-gutter);
  padding-bottom: calc(56px + env(safe-area-inset-bottom, 8px) + 8px);
  -webkit-overflow-scrolling: touch;
}

.mobile-tabbar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  padding-bottom: env(safe-area-inset-bottom, 0);
}
</style>
