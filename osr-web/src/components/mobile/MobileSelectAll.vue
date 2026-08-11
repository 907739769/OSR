<template>
  <div class="select-all-bar">
    <v-checkbox
      :model-value="allSelected"
      :indeterminate="indeterminate"
      density="compact"
      hide-details
      class="select-all-checkbox"
      :label="label"
      @update:model-value="(v: boolean | null) => emit('toggle', !!v)"
    />
    <!-- 右侧留给页面自己的开关（如「批量操作」按钮）；不传就只有全选框 -->
    <slot />
  </div>
</template>

<script setup lang="ts">
/**
 * 移动端列表的「全选本页」行。
 *
 * 卡片常驻勾选框的页面把它放在列表上方——那类页面的 .batch-bar 要选中一项后才出现，
 * 全选框放进批量条里就永远够不着。带批量模式开关的页面（订阅 / 下载记录）不用它，
 * 全选框直接嵌在批量条里。
 *
 * 样式类 .select-all-bar / .select-all-checkbox 定义在 styles/mobile-list.scss，
 * 与批量条共用，这里不再重复。
 */
withDefaults(
  defineProps<{
    /** 当前页是否全部已选 */
    allSelected: boolean
    /** 半选态（当前页选了一部分） */
    indeterminate: boolean
    label?: string
  }>(),
  { label: '全选本页' }
)

const emit = defineEmits<{
  toggle: [checked: boolean]
}>()
</script>
