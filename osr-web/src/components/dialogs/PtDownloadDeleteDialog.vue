<template>
  <FormDialogShell
    :model-value="modelValue"
    title="删除下载"
    :max-width="480"
    :submitting="submitting"
    submit-text="删除"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
    @submit="emit('submit')"
  >
    <template v-if="record">
      <p class="delete-dialog-title">{{ record.title }}</p>
      <p class="delete-dialog-message">{{ deleteTorrentMessage(record) }}</p>
      <!-- 已下完的种子只移除任务：文件可能正被媒体库 / STRM 用着，后端也会拒绝 -->
      <v-checkbox-btn
        v-if="canDeleteFiles(record)"
        :model-value="deleteFiles"
        label="同时删除已下载的文件"
        color="error"
        density="compact"
        class="delete-files-checkbox"
        @update:model-value="(v: boolean | null) => emit('update:deleteFiles', !!v)"
      />
      <!-- 站点规则五花八门：有的按下载量而不是下载完成来计 H&R，删之前得让用户自己心里有数 -->
      <div v-if="hitAndRunHint" class="delete-dialog-warning">
        <v-icon icon="triangle-alert" size="16" />
        <span>{{ hitAndRunHint }}</span>
      </div>
    </template>
  </FormDialogShell>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import FormDialogShell from '@/components/dialogs/FormDialogShell.vue'
import type { PtDownloadRecordView } from '@/api/openlist/ptDownloadRecord'
import { canDeleteFiles, deleteTorrentMessage, hasHitAndRun } from '@/composables/ptDownloadRecordLabels'

/**
 * 下载记录页「删除下载」的确认弹窗，PC 与移动端共用。
 *
 * 三种状态删掉的东西不一样（在途：删任务 + 集退回缺失；已完成：只移除任务、文件保留；
 * 下载超时的失败记录：清掉留在下载器里的残骸），文案由 `deleteTorrentMessage` 按状态给。
 * H&R 考核中的记录根本不会出现删除按钮（`canDeleteTorrent`），后端也会再拦一次。
 */
const props = defineProps<{
  modelValue: boolean
  record: PtDownloadRecordView | null
  deleteFiles: boolean
  submitting?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'update:deleteFiles', value: boolean): void
  (e: 'submit'): void
}>()

const hitAndRunHint = computed(() => {
  const r = props.record
  if (!r || !hasHitAndRun(r) || !canDeleteFiles(r) || !(r.progress && r.progress > 0)) return ''
  return '来源站点有 H&R 考核。部分站点对下载过一部分的种子也会计入考核，删除前请确认站点规则。'
})
</script>

<style scoped lang="scss">
.delete-dialog-title {
  margin-bottom: 8px;
  font-weight: 500;
  word-break: break-all;
}

.delete-dialog-message {
  margin-bottom: 12px;
  line-height: 1.6;
  color: var(--osr-text-secondary);
}

.delete-files-checkbox {
  margin-left: -8px;
}

.delete-dialog-warning {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.5;
  color: var(--osr-warning);
}
</style>
