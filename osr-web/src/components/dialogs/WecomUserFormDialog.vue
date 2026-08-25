<template>
  <FormDialogShell v-model="open" :title="dialogTitle" :submitting="submitLoading" @submit="submitForm">
    <v-form ref="formRef">
      <v-text-field
        v-model="form.wecomUserid"
        label="企业微信 UserId"
        placeholder="企微管理后台「通讯录」成员详情页可查"
        :rules="toRuleFns(rules.wecomUserid)"
        class="mb-3"
      />
      <v-select
        v-model="form.sysUserId"
        label="绑定到 OSR 用户"
        :items="userOptions"
        :rules="toRuleFns(rules.sysUserId)"
        placeholder="请选择"
        class="mb-3"
      />
      <v-select
        v-model="form.status"
        label="状态"
        :items="[{ title: '正常', value: '0' }, { title: '停用（拒绝指令、不再定向推送）', value: '1' }]"
        class="mb-3"
      />
      <v-textarea v-model="form.remark" label="备注" rows="2" placeholder="可选" />
    </v-form>
  </FormDialogShell>
</template>

<script setup lang="ts">
import FormDialogShell from '@/components/dialogs/FormDialogShell.vue'
import { usePageState } from '@/composables/pageStateContext'
import { toRuleFns } from '@/composables/formRules'
import type { useWecomUser } from '@/composables/useWecomUser'

// rules 是 { 字段名: 规则数组 } 的普通对象（不是 ref），按字段名取
const {
  open, dialogTitle, submitLoading, formRef, form, rules, userOptions, submitForm
} = usePageState<ReturnType<typeof useWecomUser>>()
</script>
