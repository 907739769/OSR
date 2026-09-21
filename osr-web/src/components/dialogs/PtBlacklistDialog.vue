<template>
  <FormDialogShell
    :model-value="modelValue"
    :title="title"
    :max-width="480"
    :submitting="submitting"
    submit-text="拉黑"
    @update:model-value="(v: boolean) => emit('update:modelValue', v)"
    @submit="emit('submit')"
  >
    <p class="blacklist-dialog-message">{{ message }}</p>
    <v-text-field
      :model-value="reason"
      label="拉黑原因（可选）"
      placeholder="如：假种、画质差、字幕错位"
      density="compact"
      variant="outlined"
      maxlength="200"
      hide-details
      @update:model-value="(v: string) => emit('update:reason', v ?? '')"
      @keyup.enter="emit('submit')"
    />
  </FormDialogShell>
</template>

<script setup lang="ts">
import FormDialogShell from '@/components/dialogs/FormDialogShell.vue'

/**
 * 下载记录页的拉黑确认弹窗：单条 / 批量、种子 / 发布组四种入口共用。
 *
 * 原先单条拉黑点一下就生效、没有任何确认，而「拉黑发布组」的影响面是该组今后的所有种子；
 * 后端早就支持记一条拉黑原因，前端却从来没传过。原因会写进黑名单，日后在黑名单页能看到当初为什么拉黑。
 */
defineProps<{
  modelValue: boolean
  title: string
  message: string
  reason: string
  submitting?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  (e: 'update:reason', value: string): void
  (e: 'submit'): void
}>()
</script>

<style scoped lang="scss">
.blacklist-dialog-message {
  margin-bottom: 16px;
  line-height: 1.6;
  word-break: break-all;
}
</style>
