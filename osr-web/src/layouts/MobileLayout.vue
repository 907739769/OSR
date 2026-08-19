<template>
  <v-navigation-drawer v-model="menuOpen" temporary width="280">
    <div class="drawer-header">
      <img src="/icons/android-chrome-192x192.png" alt="Logo" class="drawer-logo" />
      <span class="drawer-title">OSR</span>
    </div>
    <v-list nav density="compact" class="mobile-menu" style="--v-list-prepend-gap: 12px" @click:select="menuOpen = false">
      <v-list-item to="/dashboard" prepend-icon="mdi-view-dashboard-outline" title="首页" rounded="lg" class="menu-item" @click="menuOpen = false" />
      <SidebarMenuItem v-for="menu in sidebarMenus" :key="menu.path" :menu="menu" />
      <v-divider class="my-2" />
      <v-list-item
        prepend-icon="mdi-tune-variant"
        title="自定义底栏"
        rounded="lg"
        class="menu-item"
        @click="openTabSettings"
      />
    </v-list>
  </v-navigation-drawer>

  <v-app-bar flat density="compact" height="50">
    <v-app-bar-nav-icon @click="menuOpen = !menuOpen" />
    <v-app-bar-title>{{ pageTitle }}</v-app-bar-title>
    <ThemeSwitch />
    <!-- 退出登录收进头像菜单，与 DesktopLayout 一致。
         原先它是紧挨 28px 头像的一个裸 mdi-logout 图标 —— 破坏性动作平铺在顶栏、
         两个热区间距只有 8px，手机上极易误触；项目自己的约定（表格操作列那条）
         就是「破坏性动作优先进菜单，收起来反而更安全」。 -->
    <v-menu location="bottom end">
      <template #activator="{ props: menuProps }">
        <v-avatar v-bind="menuProps" size="28" color="primary" class="mr-3 user-avatar">管</v-avatar>
      </template>
      <v-list density="compact">
        <v-list-item prepend-icon="mdi-cog-outline" title="修改密码" @click="showPasswordDialog = true" />
        <v-divider />
        <v-list-item prepend-icon="mdi-logout" title="退出登录" @click="handleLogout" />
      </v-list>
    </v-menu>
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

    <!-- height 必须与 tokens.scss 的 --osr-mobile-tabbar-height 一致：
         内容区的 padding-bottom 与 .fab-add 的 bottom 都按那个令牌算 -->
    <v-bottom-navigation :model-value="activeTab" grow height="56" class="mobile-tabbar">
      <v-btn
        v-for="tab in mainTabs"
        :key="tab.path"
        :value="tab.path"
        class="tabbar-item"
        @click="router.push(tab.path)"
      >
        <v-icon :icon="tab.icon" />
        <span>{{ tab.title }}</span>
      </v-btn>
      <!-- 「更多」= 打开侧边抽屉。抽屉原先只有左上角汉堡键一个入口，那是单手持机时
           最难够到的位置，而 tab 上这四个页面之外（PT 的 4 组 12 页全在此列）都得走它。 -->
      <v-btn :value="MORE_TAB" class="tabbar-item" @click="menuOpen = true">
        <v-icon icon="mdi-dots-horizontal" />
        <span>更多</span>
      </v-btn>
    </v-bottom-navigation>
  </v-main>

  <ChangePasswordDialog v-model:visible="showPasswordDialog" />
  <MobileTabSettingsDialog v-model="showTabSettings" />
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { confirm } from '@/composables/useConfirm'
import { useUserStore } from '@/stores/user'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'
import SidebarMenuItem from '@/components/SidebarMenuItem.vue'
import MobileTabSettingsDialog from '@/components/mobile/MobileTabSettingsDialog.vue'
import { useMobileTabs } from '@/composables/useMobileTabs'
import ThemeSwitch from '@/components/ThemeSwitch.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const menuOpen = ref(false)
const showPasswordDialog = ref(false)

const sidebarMenus = computed(() => userStore.routes.filter((r: any) => r.hidden !== true))

// 抽屉里的菜单已改成「分组标题 + 子项平铺」（与 PC 同一个 SidebarMenuItem），
// 因此不再需要维护折叠面板的展开态。原先那份 openedGroups 是 computed 绑到
// v-list 的 :opened 上 —— 那是受控属性，用户手动展开的其它分组会在下一次路由
// 变化时被重算强制收起，本身也是个 bug。

const pageTitle = computed(() => (route.meta?.title as string) || 'OSR')

// 底部主 tab 由 useMobileTabs 提供：默认那四个仍然写在 composable 里，
// 用户可以在「更多 → 自定义底栏」换成自己常用的（存 localStorage）。
const { tabs: mainTabs } = useMobileTabs()

/** 「更多」按钮的 model 值。它不是路由，只是打开抽屉，取一个不会与 path 撞车的常量 */
const MORE_TAB = '__more__'

const showTabSettings = ref(false)
const openTabSettings = () => {
  menuOpen.value = false
  showTabSettings.value = true
}

const activeTab = computed(() => {
  const match = mainTabs.value.find(
    (tab) => route.path === tab.path || route.path.startsWith(`${tab.path}/`)
  )
  return match?.path ?? MORE_TAB
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

.mobile-content {
  /* 左右内距取 tokens.scss 的 --osr-mobile-gutter：各页做「常驻顶部」时要用负边距
     把底色铺满整宽，两处必须是同一个值。顶栏高度同理，见该文件的 --osr-mobile-appbar-height
     （改上面 <v-app-bar height="50"> 时那个令牌要一起改） */
  padding: var(--osr-mobile-gutter);
  padding-bottom: calc(var(--osr-mobile-tabbar-height) + env(safe-area-inset-bottom, 8px) + 8px);
  -webkit-overflow-scrolling: touch;
}

.mobile-tabbar {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  padding-bottom: env(safe-area-inset-bottom, 0);
}

/* Vuetify 给底栏按钮的 min-width 是 80px，五个按钮 400px 放不进 375 的屏幕：
   v-bottom-navigation 是 overflow:hidden，于是首尾两个各被裁掉一截（实测 -12 / +13），
   页面上不报错、也不出横向滚动条，只是「首页」和「更多」的边缘缺了一块。
   选择器要写到 .v-btn.tabbar-item：只写 .tabbar-item 的话与 Vuetify 的
   `.v-bottom-navigation .v-btn` 同特异性，谁后加载谁赢——实测输了。 */
:deep(.v-btn.tabbar-item) {
  min-width: 0;

  span {
    font-size: 11px;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    max-width: 100%;
  }
}

.user-avatar {
  cursor: pointer;
}
</style>
