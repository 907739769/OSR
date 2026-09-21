<template>
  <div class="ordered-list">
    <div v-for="(item, index) in modelValue" :key="item" class="ordered-row">
      <span class="ordered-index">{{ index + 1 }}</span>
      <span class="ordered-label">{{ labelOf ? labelOf(item) : item }}</span>
      <v-btn
        variant="text"
        size="small"
        icon="arrow-up"
        :disabled="index === 0"
        :aria-label="`上移 ${item}`"
        @click="emit('update:modelValue', moveItem(modelValue, index, -1))"
      />
      <v-btn
        variant="text"
        size="small"
        icon="arrow-down"
        :disabled="index === modelValue.length - 1"
        :aria-label="`下移 ${item}`"
        @click="emit('update:modelValue', moveItem(modelValue, index, 1))"
      />
      <v-btn
        v-if="removable"
        variant="text"
        size="small"
        icon="x"
        :aria-label="`移除 ${item}`"
        @click="emit('update:modelValue', modelValue.filter((_, i) => i !== index))"
      />
    </div>
    <div v-if="!modelValue.length && emptyText" class="ordered-empty">{{ emptyText }}</div>
  </div>
</template>

<script setup lang="ts">
/**
 * 有先后顺序的一列值：序号 + 名称 + 上移/下移（可选移除）。
 *
 * PT 过滤规则的排序维度与三个优先级列表、洗版规则的比较维度都用它。
 * 只发 `update:modelValue`、不改 prop，调用方用 `v-model` 接。
 */
import { moveItem } from '@/composables/orderList'

defineProps<{
  modelValue: string[]
  /** 显示名；不传则直接显示值 */
  labelOf?: (item: string) => string
  /** 是否允许移除（维度列表是全集、不许删，优先级列表可以） */
  removable?: boolean
  /** 列表为空时的说明 */
  emptyText?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: string[]] }>()
</script>

<style scoped>
.ordered-list {
  width: 100%;
}

.ordered-row {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 2px 0;
}

.ordered-index {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  margin-right: 4px;
  border-radius: 50%;
  background: var(--osr-bg-page);
  font-size: var(--osr-fs-xs);
}

.ordered-label {
  flex: 1;
  min-width: 0;
}

.ordered-empty {
  padding: 4px 0;
  font-size: var(--osr-fs-sm);
  color: var(--osr-text-secondary);
}
</style>
