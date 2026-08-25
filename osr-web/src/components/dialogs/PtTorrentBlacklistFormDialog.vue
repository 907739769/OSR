<template>
  <FormDialogShell
    v-model="open"
    :title="dialogTitle"
    :submitting="submitLoading"
    :max-width="480"
    @submit="submitForm"
  >
    <v-form ref="formRef">
      <v-text-field
        v-model="form.value"
        label="发布组名"
        placeholder="如 CHDWEB，大小写不敏感"
        :rules="toRuleFns(rules.value)"
        class="mb-3"
      />
      <v-textarea
        v-model="form.reason"
        label="原因"
        rows="2"
        placeholder="可选，如“转码质量差”"
      />
    </v-form>
  </FormDialogShell>
</template>

<script setup lang="ts">
import FormDialogShell from '@/components/dialogs/FormDialogShell.vue'
import { usePageState } from '@/composables/pageStateContext'
import { toRuleFns } from '@/composables/formRules'
import type { usePtTorrentBlacklist } from '@/composables/usePtTorrentBlacklist'

const {
  open, dialogTitle, submitLoading, formRef, form, rules, submitForm
} = usePageState<ReturnType<typeof usePtTorrentBlacklist>>()
</script>
