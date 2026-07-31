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
      <router-view v-slot="{ Component, route: currentRoute }">
        <transition name="fade">
          <keep-alive v-if="currentRoute.meta?.keepAlive" :max="6">
            <component :is="Component" :key="currentRoute.path" />
          </keep-alive>
          <component v-else :is="Component" :key="currentRoute.path" />
        </transition>
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

// 底部主 tab（最常用的四个页面）
const mainTabs = [
  { path: '/dashboard', label: '首页', icon: 'mdi-view-dashboard-outline' },
  { path: '/openliststrm/copy', label: '同步记录', icon: 'mdi-file-multiple-outline' },
  { path: '/openliststrm/strm', label: 'STRM记录', icon: 'mdi-movie-open-outline' },
  { path: '/openliststrm/renameDetail', label: '重命名记录', icon: 'mdi-pencil-outline' }
]

const activeTab = computed(() => {
  const match = mainTabs.find((tab) => {
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
  padding: 12px;
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
