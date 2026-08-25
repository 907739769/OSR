<template>
  <FormDialogShell v-model="open" :title="dialogTitle" :submitting="submitLoading" @submit="submitForm">
    <v-form ref="formRef">
      <v-text-field
        v-model="form.name"
        label="名称"
        placeholder="请输入名称"
        :rules="toRuleFns(rules.name)"
        class="mb-2"
      />
      <v-select
        v-model="form.type"
        label="类型"
        :items="[{ title: 'Emby', value: 'EMBY' }, { title: 'Jellyfin', value: 'JELLYFIN' }]"
        class="mb-2"
      />
      <v-text-field
        v-model="form.url"
        label="服务器地址"
        placeholder="如 http://192.168.1.10:8096"
        :rules="toRuleFns(rules.url)"
        class="mb-2"
      />
      <v-text-field
        v-model="form.apiKey"
        label="API Key"
        type="password"
        :placeholder="form.id ? '留空则不修改 API Key' : '请输入 API Key'"
        :rules="apiKeyRules"
        class="mb-2"
      />
      <v-text-field
        v-model="form.userId"
        label="用户ID"
        placeholder="留空则按服务器全库查询"
        class="mb-2"
      />
      <v-radio-group v-model="form.enabled" inline label="状态" hide-details>
        <v-radio label="启用" value="1" />
        <v-radio label="停用" value="0" />
      </v-radio-group>
    </v-form>

    <template #extra>
      <v-btn :loading="testLoading" variant="outlined" @click="handleTest">测试连接</v-btn>
    </template>
  </FormDialogShell>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import FormDialogShell from '@/components/dialogs/FormDialogShell.vue'
import { usePageState } from '@/composables/pageStateContext'
import { toRuleFns } from '@/composables/formRules'
import type { usePtMediaServer } from '@/composables/usePtMediaServer'

const {
  open, dialogTitle, submitLoading, formRef, form, rules, submitForm,
  testLoading, handleTest
} = usePageState<ReturnType<typeof usePtMediaServer>>()

// 编辑时后端出于安全考虑会把 apiKey 脱敏为空（留空提交 = 沿用已保存值），
// 只有新增时才要求必填，否则编辑弹窗永远校验不过
const apiKeyRules = computed(() => (form.value.id ? [] : toRuleFns(rules.apiKey)))
</script>
