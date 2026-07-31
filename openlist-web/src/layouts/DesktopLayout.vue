<template>
  <v-navigation-drawer :model-value="true" :rail="!appStore.sidebarOpened" permanent width="220" rail-width="64">
    <div class="logo">
      <img src="/icons/android-chrome-192x192.png" alt="Logo" class="logo-img" />
      <span v-show="appStore.sidebarOpened" class="logo-title">OSR</span>
    </div>
    <v-list nav density="compact" :opened="[]" style="--v-list-prepend-gap: 12px">
      <v-list-item to="/dashboard" prepend-icon="mdi-view-dashboard-outline" title="首页" rounded="lg" class="menu-item" />
      <SidebarMenuItem v-for="menu in sidebarMenus" :key="menu.path" :menu="menu" />
    </v-list>
  </v-navigation-drawer>

  <v-app-bar flat density="comfortable" height="56">
    <v-app-bar-nav-icon @click="toggleSidebar">
      <v-icon :icon="appStore.sidebarOpened ? 'mdi-menu-open' : 'mdi-menu'" />
    </v-app-bar-nav-icon>
    <v-spacer />
    <v-menu>
      <template #activator="{ props: menuProps }">
        <div class="avatar-wrapper" v-bind="menuProps">
          <v-avatar size="32" color="primary" class="mr-2">管</v-avatar>
          <span class="username">管理员</span>
          <v-icon icon="mdi-chevron-down" size="14" class="ml-1" />
        </div>
      </template>
      <v-list density="compact">
        <v-list-item prepend-icon="mdi-cog-outline" title="修改密码" @click="showPasswordDialog = true" />
        <v-divider />
        <v-list-item prepend-icon="mdi-logout" title="退出登录" @click="handleLogout" />
      </v-list>
    </v-menu>
  </v-app-bar>

  <v-main>
    <div class="content-wrapper">
      <router-view v-slot="{ Component, route: currentRoute }">
        <transition name="fade-slide">
          <keep-alive v-if="currentRoute.meta?.keepAlive" :max="6">
            <component :is="Component" :key="currentRoute.path" />
          </keep-alive>
          <component v-else :is="Component" :key="currentRoute.path" />
        </transition>
      </router-view>
    </div>
  </v-main>

  <ChangePasswordDialog v-model:visible="showPasswordDialog" />
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { confirm } from '@/composables/useConfirm'
import { useAppStore } from '@/stores/app'
import { useUserStore } from '@/stores/user'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'
import SidebarMenuItem from '@/components/SidebarMenuItem.vue'

const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()
const showPasswordDialog = ref(false)

const sidebarMenus = computed(() => userStore.routes.filter((r: any) => r.hidden !== true))

const toggleSidebar = () => {
  appStore.toggleSidebar()
}

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
.logo {
  display: flex;
  align-items: center;
  padding: 16px;
  height: var(--osr-navbar-height);
  flex-shrink: 0;
  overflow: hidden;

  .logo-img {
    width: 32px;
    height: 32px;
    margin-right: 10px;
    flex-shrink: 0;
  }

  .logo-title {
    font-size: 18px;
    font-weight: 700;
    white-space: nowrap;
    letter-spacing: 0.5px;
  }
}

:deep(.menu-item) {
  margin: 1px 6px;

  &.v-list-item--active {
    color: rgb(var(--v-theme-primary));
    background: rgba(var(--v-theme-primary), 0.1);
  }
}

.avatar-wrapper {
  display: flex;
  align-items: center;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: var(--osr-radius-base);

  .username {
    font-size: 14px;
    font-weight: 500;
  }
}

.content-wrapper {
  padding: 16px 20px 20px 20px;
  min-height: 0;
}

.fade-slide-enter-active,
.fade-slide-leave-active {
  transition: opacity var(--osr-transition-base);
}

.fade-slide-enter-from {
  opacity: 0;
  transform: translateX(8px);
}

.fade-slide-leave-to {
  opacity: 0;
  transform: translateX(-8px);
}
</style>
