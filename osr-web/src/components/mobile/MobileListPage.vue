<template>
  <!-- 移动端列表页外壳。17 个页面原先各写一遍这套骨架：.mobile-page 容器、
       .task-list 容器、顶部加载条、底部空态——四样东西的位置关系（加载条必须在
       列表容器内、空态必须与加载条互斥）没有任何一页需要自己决定。 -->
  <div class="mobile-page">
    <slot name="head" />
    <div class="task-list">
      <!-- 首屏给骨架卡、之后的刷新给细进度条：刷新时换成骨架会把正在看的卡片整片抹掉 -->
      <template v-if="firstLoading">
        <div
          v-for="i in skeletonCount"
          :key="'sk-' + i"
          class="osr-sk-mcard osr-sheen osr-skeleton"
          :style="{ '--osr-i': i - 1 }"
          role="status"
          aria-busy="true"
          :aria-label="i === 1 ? '加载中' : undefined"
        >
          <span class="osr-bone osr-bone--block osr-sk-mcard__icon" />
          <div class="osr-sk-mcard__body">
            <div class="osr-sk-mcard__head">
              <span class="osr-bone osr-bone--title" :style="{ width: TITLE[i % TITLE.length] }" />
              <span class="osr-bone osr-bone--chip" />
            </div>
            <span class="osr-bone" :style="{ width: TEXT[i % TEXT.length] }" />
            <span class="osr-bone osr-bone--caption" :style="{ width: TEXT[(i + 2) % TEXT.length] }" />
          </div>
        </div>
      </template>
      <v-progress-linear v-else-if="refreshing" indeterminate color="primary" />
      <slot />
      <v-empty-state v-if="!loading && empty" :icon="emptyIcon" :title="emptyTitle" :text="emptyText" />
    </div>
    <slot name="foot" />
  </div>
</template>

<script setup lang="ts">
import { toRef } from 'vue'
import { useFirstLoad } from '@/composables/useFirstLoad'

const props = withDefaults(
  defineProps<{
    loading?: boolean
    /** 列表是否为空。加载中不展示空态，由本组件统一判断，页面只管传「列表长度为 0」 */
    empty?: boolean
    emptyIcon?: string
    emptyTitle?: string
    /** 空态下的一句说明，多数页面不需要 */
    emptyText?: string
    /** 首屏骨架卡张数，一屏手机大致放得下 5 张 */
    skeletonCount?: number
  }>(),
  {
    loading: false,
    empty: false,
    emptyIcon: 'inbox',
    emptyTitle: '暂无数据',
    emptyText: undefined,
    skeletonCount: 5
  }
)

// 首屏 / 刷新的判据见 useFirstLoad
const { firstLoading, refreshing } = useFirstLoad(toRef(props, 'loading'))

const TITLE = ['62%', '48%', '70%', '54%']
const TEXT = ['80%', '56%', '68%', '44%', '74%']
</script>
