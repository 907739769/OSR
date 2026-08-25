<template>
  <FormDialogShell v-model="open" :title="dialogTitle" :submitting="submitLoading" @submit="submitForm">
    <v-form ref="formRef">
      <FormField label="源目录">
        <DirectoryTreeSelect v-model="form.sourceFolder" type="local" placeholder="请选择源目录" />
      </FormField>
      <FormField label="目标目录">
        <DirectoryTreeSelect v-model="form.targetRoot" type="local" placeholder="请选择目标目录" />
      </FormField>
      <FormField label="状态">
        <v-radio-group v-model="form.status" inline hide-details>
          <v-radio label="停用" value="0" />
          <v-radio label="启用" value="1" />
        </v-radio-group>
      </FormField>
      <div class="section-label">刮削配置</div>
      <v-divider class="mb-3" />
      <FormField label="启用刮削" inline>
        <v-switch v-model="form.scrapeEnabled" true-value="1" false-value="0" color="primary" hide-details density="compact" />
      </FormField>
      <FormField v-if="form.scrapeEnabled === '1'" label="生成NFO" inline>
        <v-switch v-model="form.scrapeNfo" true-value="1" false-value="0" color="primary" hide-details density="compact" />
      </FormField>
      <FormField v-if="form.scrapeEnabled === '1'" label="下载图片" inline>
        <v-switch v-model="form.scrapeImages" true-value="1" false-value="0" color="primary" hide-details density="compact" />
      </FormField>
      <FormField v-if="form.scrapeEnabled === '1'" label="强制覆盖" inline>
        <v-switch v-model="form.scrapeForceOverwrite" true-value="1" false-value="0" color="primary" hide-details density="compact" />
      </FormField>
    </v-form>
  </FormDialogShell>
</template>

<script setup lang="ts">
import FormDialogShell from '@/components/dialogs/FormDialogShell.vue'
import FormField from '@/components/FormField.vue'
import DirectoryTreeSelect from '@/components/DirectoryTreeSelect/index.vue'
import { usePageState } from '@/composables/pageStateContext'
import type { useRenameTask } from '@/composables/useRenameTask'

const {
  open, dialogTitle, submitLoading, formRef, form, submitForm
} = usePageState<ReturnType<typeof useRenameTask>>()
</script>

<style scoped lang="scss">
.section-label {
  font-size: 13px;
  font-weight: 600;
  color: var(--osr-text-primary);
  margin-bottom: 8px;
}
</style>
