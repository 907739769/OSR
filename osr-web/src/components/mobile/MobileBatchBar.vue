<template>
  <!-- 批量操作条。13 个移动端列表页原先各抄一份，除了中间那几个动作按钮完全一样，
       抄漏的地方也一样多（「全选」必须紧挨「取消」前面这条约定，靠人记）。
       样式（含吸底）在 styles/mobile-list.scss 的 .batch-bar 单源。 -->
  <div v-if="visible" class="batch-bar">
    <span class="selected-count">已选 {{ count }} 项</span>
    <slot />
    <v-btn variant="text" size="small" class="batch-select-all-btn" @click="emit('toggle-all', !allSelected)">
      {{ allSelected ? '取消全选' : '全选' }}
    </v-btn>
    <v-btn variant="text" size="small" class="batch-cancel-btn" @click="emit('cancel')">
      取消
    </v-btn>
  </div>
</template>

<script setup lang="ts">
defineProps<{
  /** 显示条件。多数页面是「选中数 > 0」，进过批量模式的页面（订阅/下载记录）传 selectionMode */
  visible: boolean
  count: number
  /** 当前页是否已全选，决定按钮文案在「全选 / 取消全选」之间切换 */
  allSelected: boolean
}>()

const emit = defineEmits<{
  /** 参数是「切换后」的目标状态，直接接 usePageSelection 的 toggleSelectAllPage */
  'toggle-all': [value: boolean]
  cancel: []
}>()
</script>
