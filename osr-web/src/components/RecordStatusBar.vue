<template>
  <div v-if="stats" class="record-status-bar" role="group" aria-label="按状态筛选">
    <v-chip
      size="small"
      :variant="!modelValue ? 'flat' : 'outlined'"
      :color="!modelValue ? 'primary' : undefined"
      class="record-status-chip"
      @click="select(undefined)"
    >
      全部
      <span class="record-status-count">{{ stats.total ?? 0 }}</span>
    </v-chip>
    <v-chip
      v-for="option in options"
      :key="option.value"
      size="small"
      :variant="modelValue === option.value ? 'flat' : 'tonal'"
      :color="option.type"
      :disabled="!count(option.value) && modelValue !== option.value"
      class="record-status-chip"
      @click="select(option.value)"
    >
      {{ option.title }}
      <span class="record-status-count">{{ count(option.value) }}</span>
    </v-chip>
  </div>
</template>

<script setup lang="ts">
/**
 * 记录页顶部的状态统计条：「全部 1203 · 处理中 4 · 失败 17 · 成功 1182」，点一下即按该状态筛选。
 *
 * 用户进记录页最想先知道的是「有没有失败的、有几条」，此前只能自己去状态下拉里选一遍再看总数。
 * 数字跟随当前搜索条件（后端统计时忽略状态这一项），所以点「失败 17」筛出来的一定是 17 条。
 * 计数为 0 的状态禁用而不是隐藏：隐藏会让统计条的宽度随数据跳动，也让人以为少了一种状态。
 * 再点一次已选中的状态等于回到全部，与状态下拉的「清空」是同一个效果。
 */
export interface RecordStatusOption {
  value: string
  title: string
  type: 'primary' | 'success' | 'warning' | 'error' | 'info'
}

const props = defineProps<{
  /** 当前筛选的状态值，undefined 表示全部；与搜索区的状态下拉绑同一个字段 */
  modelValue?: string
  options: RecordStatusOption[]
  /** 统计接口的返回：{ 状态值: 条数, total }；为 null（还没拉到或接口失败）时整条不渲染 */
  stats: Record<string, number> | null
}>()

const emit = defineEmits<{ 'update:modelValue': [value: string | undefined] }>()

const count = (value: string) => props.stats?.[value] ?? 0

const select = (value: string | undefined) => {
  emit('update:modelValue', value === props.modelValue ? undefined : value)
}
</script>

<style scoped lang="scss">
.record-status-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.record-status-count {
  margin-left: 6px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
</style>
