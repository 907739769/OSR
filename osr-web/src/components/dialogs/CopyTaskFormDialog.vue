<template>
  <FormDialogShell v-model="open" :title="dialogTitle" :submitting="submitLoading" @submit="handleSubmitClick">
    <v-form ref="formRef">
      <FormField label="源目录">
        <DirectoryTreeSelect v-model="form.copyTaskSrc" type="openlist" placeholder="请选择源目录" />
      </FormField>
      <FormField label="目标目录">
        <DirectoryTreeSelect v-model="form.copyTaskDst" type="openlist" placeholder="请选择目标目录" />
      </FormField>
      <FormField label="监控目录">
        <DirectoryTreeSelect v-model="form.monitorDir" type="local" placeholder="请选择监控目录（可选）" />
      </FormField>
      <FormField label="状态">
        <v-radio-group v-model="form.copyTaskStatus" inline hide-details>
          <v-radio label="启用" value="1" />
          <v-radio label="停用" value="0" />
        </v-radio-group>
      </FormField>
    </v-form>
  </FormDialogShell>
</template>

<script setup lang="ts">
import FormDialogShell from '@/components/dialogs/FormDialogShell.vue'
import FormField from '@/components/FormField.vue'
import DirectoryTreeSelect from '@/components/DirectoryTreeSelect/index.vue'
import { usePageState } from '@/composables/pageStateContext'
import { message } from '@/composables/useMessage'
import type { useCopyTask } from '@/composables/useCopyTask'

const {
  open, dialogTitle, submitLoading, formRef, form, submitForm
} = usePageState<ReturnType<typeof useCopyTask>>()

// DirectoryTreeSelect 不支持 v-form 的 :rules 校验，改为提交前手动校验必填项
const handleSubmitClick = () => {
  if (!form.value.copyTaskSrc) {
    message.warning('源目录不能为空')
    return
  }
  if (!form.value.copyTaskDst) {
    message.warning('目标目录不能为空')
    return
  }
  submitForm()
}
</script>
