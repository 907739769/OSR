<template>
  <FormDialogShell
    :model-value="modelValue"
    title="清理旧下载记录"
    :max-width="480"
    :submitting="submitting"
    submit-text="清理"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
    @submit="emit('submit')"
  >
    <v-select
      :model-value="days"
      :items="dayItems"
      label="清理多久以前推送的记录"
      density="compact"
      variant="outlined"
      hide-details
      @update:model-value="(v: number) => emit('update:days', v)"
    />
    <ul class="cleanup-rules">
      <li>只清理已完成或已失败的记录；推送中、下载中的不动</li>
      <li>仍在 H&amp;R 保种考核中的不动——删种与转移做种靠它保护正在考核的种子</li>
      <li>仍被某一集引用的不动——洗版要靠它找回当初那个种子的质量</li>
      <li>被清理的记录会从 PT 统计面板的历史数据里一并消失</li>
    </ul>
    <div class="cleanup-count">
      <v-progress-circular v-if="loading" indeterminate size="16" width="2" />
      <template v-else-if="count === null">预览失败，暂时无法清理</template>
      <template v-else-if="count === 0">没有符合条件的记录</template>
      <template v-else>将清理 <strong>{{ count }}</strong> 条记录，删除后不可恢复</template>
    </div>
  </FormDialogShell>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import FormDialogShell from '@/components/dialogs/FormDialogShell.vue'

/**
 * 下载记录「按规则清理」弹窗。下载记录只增不减，长期运行后翻页越来越难；
 * 但单条删除对系统生成的记录不开放，这里只提供按保留天数清理，规则写在弹窗里让用户知道删的是哪些。
 * 规则的权威实现在后端 DownloadRecordAdminService#cleanup，这里的文案要跟着它改。
 */
const props = defineProps<{
  modelValue: boolean
  days: number
  dayOptions: number[]
  count: number | null
  loading?: boolean
  submitting?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'update:days', value: number): void
  (e: 'submit'): void
}>()

const dayItems = computed(() => props.dayOptions.map(d => ({ title: `${d} 天前`, value: d })))
</script>

<style scoped lang="scss">
.cleanup-rules {
  margin: 16px 0 12px;
  padding-left: 20px;
  font-size: var(--osr-fs-sm);
  color: var(--osr-text-secondary);
  line-height: 1.7;
}

.cleanup-count {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 24px;
}
</style>
