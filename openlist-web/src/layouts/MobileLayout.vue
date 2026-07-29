<template>
  <div class="mobile-layout">
    <!-- Overlay -->
    <transition name="fade">
      <div v-if="menuOpen" class="overlay" @click="menuOpen = false" />
    </transition>

    <!-- Drawer menu -->
    <transition name="slide-right">
      <div v-if="menuOpen" class="drawer-menu">
        <div class="drawer-header">
          <div class="drawer-brand">
            <img src="/icons/android-chrome-192x192.png" alt="Logo" class="drawer-logo" />
            <span class="drawer-title">OSR</span>
          </div>
          <el-icon class="close-btn" @click="menuOpen = false"><Close /></el-icon>
        </div>
        <el-scrollbar>
          <el-menu
            :default-active="activeMenu"
            router
            class="drawer-menu-list"
            @select="menuOpen = false"
          >
            <el-menu-item index="/dashboard">
              <el-icon><Odometer /></el-icon>
              <template #title>首页</template>
            </el-menu-item>
            <SidebarMenuItem v-for="menu in sidebarMenus" :key="menu.path" :menu="menu" />
          </el-menu>
        </el-scrollbar>
      </div>
    </transition>

    <!-- Main content -->
    <div class="mobile-main">
      <!-- Top bar -->
      <div class="mobile-navbar">
        <el-icon class="hamburger" @click="menuOpen = !menuOpen"><Menu /></el-icon>
        <span class="page-title">{{ pageTitle }}</span>
        <div class="navbar-actions">
          <el-dropdown @command="handleDropdownCommand">
            <span class="avatar-wrapper">
              <el-avatar :size="28" class="user-avatar">管</el-avatar>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">
                  <el-icon><Setting /></el-icon>
                  修改密码
                </el-dropdown-item>
                <el-dropdown-item command="logout" divided>
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </div>

      <!-- Content -->
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

      <!-- Bottom TabBar -->
      <div class="mobile-tabbar">
        <div
          v-for="tab in mainTabs"
          :key="tab.path"
          class="tabbar-item"
          :class="{ active: isTabActive(tab.path) }"
          @click="$router.push(tab.path)"
        >
          <el-icon :class="{ active: isTabActive(tab.path) }">
            <component :is="tab.icon" />
          </el-icon>
          <span>{{ tab.label }}</span>
        </div>
      </div>
    </div>

    <!-- Password Change Dialog -->
    <ChangePasswordDialog v-model:visible="showPasswordDialog" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import {
  Menu, Close, Odometer, VideoCamera, Files, EditPen,
  Setting, SwitchButton
} from '@element-plus/icons-vue'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'
import SidebarMenuItem from '@/components/SidebarMenuItem.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const menuOpen = ref(false)
const showPasswordDialog = ref(false)

const sidebarMenus = computed(() => userStore.routes)

const pageTitle = computed(() => {
  return (route.meta?.title as string) || 'OSR'
})

const activeMenu = computed(() => route.path)

// Main tabs for bottom tabbar (most frequently used pages)
const mainTabs = [
  { path: '/dashboard', label: '首页', icon: Odometer },
  { path: '/openliststrm/copy', label: '同步记录', icon: Files },
  { path: '/openliststrm/strm', label: 'STRM记录', icon: VideoCamera },
  { path: '/openliststrm/renameDetail', label: '重命名记录', icon: EditPen }
]

const isTabActive = (path: string) => {
  if (path === '/dashboard') return route.path === '/dashboard'
  return route.path.startsWith(path)
}

const handleDropdownCommand = async (command: string) => {
  if (command === 'profile') {
    showPasswordDialog.value = true
  } else if (command === 'logout') {
    try {
      await ElMessageBox.confirm('确定要退出登录吗？', '提示', {
        confirmButtonText: '确定',
        cancelButtonText: '取消',
        type: 'warning'
      })
      await userStore.logout()
      router.push('/login')
    } catch {
      // cancelled
    }
  }
}

</script>

<style scoped lang="scss">
/* ============================================
   Mobile Layout
   ============================================ */
.mobile-layout {
  display: flex;
  flex-direction: column;
  height: 100%;
  width: 100%;
  position: relative;
  background-color: var(--osr-bg-page);
}

/* ============================================
   Overlay
   ============================================ */
.overlay {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  z-index: 998;
  backdrop-filter: blur(2px);
}

/* ============================================
   Drawer Menu
   ============================================ */
.drawer-menu {
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  width: 280px;
  max-width: 85vw;
  background: var(--osr-surface);
  z-index: 999;
  display: flex;
  flex-direction: column;
  box-shadow: var(--osr-shadow-lg);
}

.drawer-header {
  display: flex;
  align-items: center;
  padding: 16px;
  border-bottom: 1px solid var(--osr-border-base);
  background: var(--osr-surface);
  flex-shrink: 0;

  .drawer-brand {
    display: flex;
    align-items: center;
    flex: 1;

    .drawer-logo {
      width: 32px;
      height: 32px;
      margin-right: 10px;
    }

    .drawer-title {
      font-size: 18px;
      font-weight: 700;
      color: var(--osr-text-primary);
      letter-spacing: 0.5px;
    }
  }

  .close-btn {
    font-size: 22px;
    cursor: pointer;
    color: var(--osr-text-secondary);
    padding: 4px;
    border-radius: var(--osr-radius-sm);
    transition: all var(--osr-transition-fast);

    &:hover {
      color: var(--osr-primary);
      background-color: var(--osr-primary-light-9);
    }
  }
}

/* Drawer menu list */
:deep(.drawer-menu-list) {
  border-right: none;
  background-color: transparent !important;
  padding: 8px;

  .el-menu-item,
  .el-sub-menu__title {
    color: var(--osr-text-secondary);
    font-weight: 500;
    border-radius: var(--osr-radius-base);
    margin-bottom: 2px;
    transition: all var(--osr-transition-fast);

    .el-icon {
      font-size: 18px;
    }

    &:hover {
      background-color: var(--osr-bg-sidebar-hover) !important;
      color: var(--osr-text-primary);
    }
  }

  .el-menu-item.is-active {
    background-color: var(--osr-bg-sidebar-active) !important;
    color: var(--osr-primary) !important;
    font-weight: 600;

    &::before {
      content: '';
      position: absolute;
      left: 0;
      top: 50%;
      transform: translateY(-50%);
      width: 3px;
      height: 20px;
      background-color: var(--osr-primary);
      border-radius: 0 2px 2px 0;
    }
  }
}

:deep(.el-sub-menu.is-active > .el-sub-menu__title) {
  color: var(--osr-primary) !important;
  font-weight: 600;
}

/* ============================================
   Mobile Main
   ============================================ */
.mobile-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  min-height: 0;
}

/* ============================================
   Mobile Navbar
   ============================================ */
.mobile-navbar {
  height: 50px;
  background: var(--osr-surface);
  border-bottom: 1px solid var(--osr-border-light);
  display: flex;
  align-items: center;
  padding: 0 12px;
  flex-shrink: 0;
  box-shadow: var(--osr-shadow-sm);
  z-index: 10;

  .hamburger {
    font-size: 22px;
    cursor: pointer;
    margin-right: 12px;
    color: var(--osr-text-secondary);
    padding: 4px;
    border-radius: var(--osr-radius-sm);
    transition: all var(--osr-transition-fast);

    &:hover {
      color: var(--osr-primary);
      background-color: var(--osr-primary-light-9);
    }
  }

  .page-title {
    flex: 1;
    font-size: 16px;
    font-weight: 600;
    color: var(--osr-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .navbar-actions {
    .avatar-wrapper {
      cursor: pointer;
      padding: 2px;

      .user-avatar {
        border: 2px solid var(--osr-primary-light-8);
        background-color: var(--osr-primary);
        color: white;
        font-weight: 600;
        font-size: 12px;
      }
    }
  }
}

/* ============================================
   Mobile Content
   ============================================ */
.mobile-content {
  flex: 1;
  overflow-y: auto;
  padding: 12px;
  /* tabbar 高度 56px + 安全区域，防止内容被遮挡 */
  padding-bottom: calc(56px + env(safe-area-inset-bottom, 8px) + 8px);
  -webkit-overflow-scrolling: touch;
}

/* ============================================
   Mobile TabBar
   ============================================ */
.mobile-tabbar {
  height: 56px;
  background: var(--osr-surface);
  border-top: 1px solid var(--osr-border-light);
  display: flex;
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 997;
  padding-bottom: env(safe-area-inset-bottom, 0);
  box-shadow: 0 -1px 3px rgba(0, 0, 0, 0.04);

  .tabbar-item {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    font-size: 10px;
    color: var(--osr-text-secondary);
    transition: color var(--osr-transition-fast);
    gap: 2px;
    position: relative;

    &.active {
      color: var(--osr-primary);

      &::before {
        content: '';
        position: absolute;
        top: 0;
        left: 50%;
        transform: translateX(-50%);
        width: 24px;
        height: 2px;
        background-color: var(--osr-primary);
        border-radius: 0 0 2px 2px;
      }
    }

    .el-icon {
      font-size: 20px;
      transition: transform var(--osr-transition-fast);

      &.active {
        transform: scale(1.1);
      }
    }
  }
}

/* ============================================
   Transitions
   ============================================ */
.fade-enter-active,
.fade-leave-active {
  transition: opacity var(--osr-transition-base);
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.slide-right-enter-active,
.slide-right-leave-active {
  transition: transform var(--osr-transition-slow);
}

.slide-right-enter-from,
.slide-right-leave-to {
  transform: translateX(-100%);
}
</style>
