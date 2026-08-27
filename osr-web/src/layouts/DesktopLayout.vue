<template>
  <v-navigation-drawer :model-value="true" :rail="!appStore.sidebarOpened" permanent width="220" rail-width="64">
    <div class="logo">
      <img src="/icons/android-chrome-192x192.png" alt="Logo" class="logo-img" />
      <span v-show="appStore.sidebarOpened" class="logo-title">OSR</span>
    </div>
    <v-list nav density="compact" :opened="[]" style="--v-list-prepend-gap: 12px">
      <v-list-item to="/dashboard" prepend-icon="layout-dashboard" title="首页" rounded="lg" class="menu-item" />
      <!-- rail 态（64px）下分组标题没地方显示，交给组件的 showGroupLabel 控制 -->
      <SidebarMenuItem
        v-for="menu in sidebarMenus"
        :key="menu.path"
        :menu="menu"
        :show-group-label="appStore.sidebarOpened"
        collapsible
      />
    </v-list>
  </v-navigation-drawer>

  <v-app-bar flat density="comfortable" height="56">
    <v-app-bar-nav-icon @click="toggleSidebar">
      <v-icon :icon="appStore.sidebarOpened ? 'panel-left-close' : 'menu'" />
    </v-app-bar-nav-icon>
    <!-- 顶栏这条位置以前整条空着。放面包屑而不是重复一遍页面标题：
         菜单收敛成两级后，页面本身完全不体现自己属于哪个分组，而 PT 那四组
         （追剧/下载/规则/接入）恰恰是靠分组才分得清的 -->
    <nav v-if="breadcrumb.length" class="app-breadcrumb" aria-label="面包屑">
      <template v-for="(item, i) in breadcrumb" :key="i">
        <span v-if="i" class="app-breadcrumb-sep">/</span>
        <span class="app-breadcrumb-item" :class="{ 'app-breadcrumb-item--current': i === breadcrumb.length - 1 }">{{ item }}</span>
      </template>
    </nav>
    <v-spacer />
    <ThemeSwitch />
    <v-menu>
      <template #activator="{ props: menuProps }">
        <div class="avatar-wrapper" v-bind="menuProps">
          <v-avatar size="32" color="primary" class="mr-2">{{ avatarText }}</v-avatar>
          <span class="username">{{ displayName }}</span>
          <v-icon icon="chevron-down" size="14" class="ml-1" />
        </div>
      </template>
      <v-list density="compact">
        <v-list-item prepend-icon="settings" title="修改密码" @click="showPasswordDialog = true" />
        <v-divider />
        <v-list-item prepend-icon="log-out" title="退出登录" @click="handleLogout" />
      </v-list>
    </v-menu>
  </v-app-bar>

  <v-main>
    <div ref="contentRef" class="content-wrapper">
      <!-- 这里刻意不加 <transition>（入场动画走 usePageTransition 的 WAAPI 单向播放）。
           原先包了一层 <transition name="fade-slide">，但在「<KeepAlive> 与裸 <component>
           交替 + 页面组件异步加载」这个结构下过渡钩子从首屏起就没正常收敛过：
           fade-slide-enter-from 一直挂在元素上不被移除，离场过渡也永远收不到结束事件，
           结果是每导航一次旧页面就留在新页面下方，越堆越多。
           加 mode="out-in" / :duration 都压不住（离场卡住后新页面根本进不来），
           所以直接去掉这层过渡。要重做页面切换动画得先解决异步组件的过渡时机，
           属于单独一件事。 -->
      <!-- 错误边界包在 router-view 外面、外壳里面：页面炸了顶栏和侧边栏还在，
           用户能自己走到别的页面去，而不是对着一整块白屏只能刷新。 -->
      <ErrorBoundary>
        <router-view v-slot="{ Component, route: currentRoute }">
          <keep-alive v-if="currentRoute.meta?.keepAlive" :max="6">
            <component :is="Component" :key="currentRoute.path" />
          </keep-alive>
          <component v-else :is="Component" :key="currentRoute.path" />
        </router-view>
      </ErrorBoundary>
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
import { useCurrentUser } from '@/composables/useCurrentUser'
import { useBreadcrumb } from '@/composables/useBreadcrumb'
import { usePageTransition } from '@/composables/usePageTransition'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'
import SidebarMenuItem from '@/components/SidebarMenuItem.vue'
import ThemeSwitch from '@/components/ThemeSwitch.vue'

const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()
const showPasswordDialog = ref(false)
const { displayName, avatarText } = useCurrentUser()
const breadcrumb = useBreadcrumb()
// 页面切换的入场动画。只做入场不做离场，理由见 usePageTransition 的注释
const { contentRef } = usePageTransition()

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

.app-breadcrumb {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-left: 4px;
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;

  .app-breadcrumb-item {
    font-size: 13px;
    color: var(--osr-text-secondary);
  }

  /* 末级是当前页，给足对比度；上级只是定位用的弱信息 */
  .app-breadcrumb-item--current {
    font-size: 15px;
    font-weight: 600;
    color: var(--osr-text-primary);
  }

  .app-breadcrumb-sep {
    font-size: 12px;
    color: var(--osr-text-disabled);
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

</style>
