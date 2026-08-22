<template>
  <!-- 移动端列表页外壳。17 个页面原先各写一遍这套骨架：.mobile-page 容器、
       .task-list 容器、顶部加载条、底部空态——四样东西的位置关系（加载条必须在
       列表容器内、空态必须与加载条互斥）没有任何一页需要自己决定。 -->
  <div class="mobile-page">
    <slot name="head" />
    <div class="task-list">
      <v-progress-linear v-if="loading" indeterminate color="primary" />
      <slot />
      <v-empty-state v-if="!loading && empty" :icon="emptyIcon" :title="emptyTitle" :text="emptyText" />
    </div>
    <slot name="foot" />
  </div>
</template>

<script setup lang="ts">
withDefaults(
  defineProps<{
    loading?: boolean
    /** 列表是否为空。加载中不展示空态，由本组件统一判断，页面只管传「列表长度为 0」 */
    empty?: boolean
    emptyIcon?: string
    emptyTitle?: string
    /** 空态下的一句说明，多数页面不需要 */
    emptyText?: string
  }>(),
  {
    loading: false,
    empty: false,
    emptyIcon: 'inbox',
    emptyTitle: '暂无数据',
    emptyText: undefined
  }
)
</script>
