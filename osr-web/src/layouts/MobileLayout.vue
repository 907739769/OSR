<template>
  <!-- 顶栏。玻璃底由 styles/surface.scss 统一给，这里只管两件随滚动变化的事：
       小标题淡入、底部分隔线浮现（都由 .mobile-appbar--scrolled 驱动，
       样式在 styles/mobile-chrome.scss）。

       原先左上角那个汉堡键已经删掉——抽屉整个换成了从底部升起的「更多」面板，
       它唯一的入口在底栏最右格，那也是单手持机最容易够到的位置。 -->
  <v-app-bar flat density="compact" height="50" class="mobile-appbar" :class="{ 'mobile-appbar--scrolled': scrolled }">
    <span class="appbar-title" :class="{ 'appbar-title--visible': scrolled }">{{ pageTitle }}</span>
    <v-spacer />
    <ThemeSwitch />
    <!-- 退出登录收进头像菜单，与 DesktopLayout 一致。
         原先它是紧挨 28px 头像的一个裸 log-out 图标 —— 破坏性动作平铺在顶栏、
         两个热区间距只有 8px，手机上极易误触；项目自己的约定（表格操作列那条）
         就是「破坏性动作优先进菜单，收起来反而更安全」。 -->
    <v-menu location="bottom end">
      <template #activator="{ props: menuProps }">
        <v-avatar v-bind="menuProps" size="28" color="primary" class="mr-3 user-avatar">{{ avatarText }}</v-avatar>
      </template>
      <v-list density="compact">
        <v-list-item prepend-icon="settings" title="修改密码" @click="showPasswordDialog = true" />
        <v-divider />
        <v-list-item prepend-icon="log-out" title="退出登录" @click="handleLogout" />
      </v-list>
    </v-menu>
  </v-app-bar>

  <v-main>
    <div ref="contentRef" class="mobile-content">
      <!-- 大标题：随内容一起滚走，滚过之后顶栏里那个小标题才淡入（iOS 的做法）。
           刻意不做「高度收缩」动画，理由见 mobile-chrome.scss 的 .mobile-bigtitle。
           全站 H5 页面的标题只有这一处（图标 + 标题），页面自己不要再写标题；
           退回 PC 实现的页面里那个 PageHeader 在移动端只保留操作区，见 PageHeader.vue -->
      <h1 class="mobile-bigtitle">
        <span class="mobile-bigtitle-icon"><v-icon :icon="pageIcon" /></span>
        <span class="mobile-bigtitle-text">{{ pageTitle }}</span>
      </h1>

      <!-- 同 DesktopLayout：原先的 <transition name="fade"> 既没有配套 CSS，
           又会让旧页面残留在新页面下方，这里一并去掉，原因见 DesktopLayout 的注释 -->
      <!-- 错误边界包在 router-view 外面、外壳里面，理由同 DesktopLayout：
           页面炸了顶栏与底部 tab 还在，用户走得掉。 -->
      <ErrorBoundary>
        <router-view v-slot="{ Component, route: currentRoute }">
          <keep-alive v-if="currentRoute.meta?.keepAlive" :max="6">
            <component :is="Component" :key="currentRoute.path" />
          </keep-alive>
          <component v-else :is="Component" :key="currentRoute.path" />
        </router-view>
      </ErrorBoundary>
    </div>

    <MobileTabBar :compact="compact" :more-open="moreOpen" :with-action="!!pageAction" @open-more="moreOpen = true" />
    <!-- 页面主动作（新增/扫描/保存）并在底栏右侧，取代原先各页右下角的悬浮按钮，
         理由见 composables/useMobilePageAction.ts -->
    <MobilePageAction :action="pageAction" :compact="compact" />
  </v-main>

  <MobileMorePanel v-model="moreOpen" />
  <ChangePasswordDialog v-model:visible="showPasswordDialog" />
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { confirm } from '@/composables/useConfirm'
import { useUserStore } from '@/stores/user'
import { useCurrentUser } from '@/composables/useCurrentUser'
import ChangePasswordDialog from '@/components/ChangePasswordDialog.vue'
import MobileTabBar from '@/components/mobile/MobileTabBar.vue'
import MobileMorePanel from '@/components/mobile/MobileMorePanel.vue'
import MobilePageAction from '@/components/mobile/MobilePageAction.vue'
import { provideMobilePageChrome } from '@/composables/useMobilePageAction'
import ThemeSwitch from '@/components/ThemeSwitch.vue'
import { usePageTransition } from '@/composables/usePageTransition'
import { useMobileChrome } from '@/composables/useMobileChrome'
import { useRecentPages } from '@/composables/useRecentPages'
import { getIconComponent, FALLBACK_ICON } from '@/composables/useMenuIcon'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const showPasswordDialog = ref(false)
const moreOpen = ref(false)
const { avatarText } = useCurrentUser()

// 页面切换的入场动画。与 PC 端共用同一个 composable，两端节奏一致
const { contentRef } = usePageTransition()

// 顶栏小标题与底栏收缩的滚动状态。两个布尔量判据不同（一个看绝对位置、
// 一个看滚动方向），见 useMobileChrome 的注释
const { scrolled, compact, reset } = useMobileChrome()

// 各页登记的主动作；批量条出现时它为 null，底栏随之还原全宽
const { action: pageAction } = provideMobilePageChrome()

// 「更多」面板顶部那行「常用」的数据来源
const { record } = useRecentPages()

const pageTitle = computed(() => (route.meta?.title as string) || 'OSR')
// 取菜单配置的图标（sys_menu.icon），与侧边栏/更多面板同一来源；没配的退化成通用图标，
// 保证每页标题前都有图标、样式一致
const pageIcon = computed(() => getIconComponent(route.meta?.icon as string) || FALLBACK_ICON)

watch(
  () => route.path,
  (path) => {
    // 新页面从顶部开始，而滚动位置恰好没变时不会再有 scroll 事件——不重置的话
    // 顶栏会带着上一页留下的小标题与分隔线
    reset()
    record(path)
  },
  { immediate: true }
)

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
.mobile-content {
  /* 左右内距取 tokens.scss 的 --osr-mobile-gutter：各页做「常驻顶部」时要用负边距
     把底色铺满整宽，两处必须是同一个值。顶栏高度同理，见该文件的 --osr-mobile-appbar-height
     （改上面 <v-app-bar height="50"> 时那个令牌要一起改） */
  padding: var(--osr-mobile-gutter);
  /* **必须用 --osr-mobile-tabbar-occupied 而不是 -height**：底栏是悬浮的，
     它在底部占掉的高度还含离底间距与安全区。用错的表现是最后一张卡片被压在栏下面，
     而页面不报任何错。同一条约定在 mobile-list.scss 的 .batch-bar 也成立。
     页面主动作与底栏同在一行，不需要在这里额外让位 */
  padding-bottom: calc(var(--osr-mobile-tabbar-occupied) + 8px);
  -webkit-overflow-scrolling: touch;
}

.user-avatar {
  cursor: pointer;
}
</style>
