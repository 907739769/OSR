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
        :items="MEDIA_SERVER_TYPES"
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

      <!-- 用户ID 保持可手填：拉取要管理员权限、也可能因为服务器不通而失败，
           那时输入框仍是唯一的出路。下面的列表只是省掉「去 Web 控制台 URL 里抠一串
           32 位十六进制」这一步，不取代它 -->
      <v-text-field
        v-model="form.userId"
        label="用户ID"
        placeholder="留空则按服务器全库查询"
        hint="可点右侧按钮从媒体服务器拉取用户列表"
        persistent-hint
        class="mb-1"
      >
        <template #append-inner>
          <v-btn
            :loading="usersLoading"
            variant="text"
            size="small"
            icon="users"
            :title="'拉取用户列表'"
            @click.stop="handleLoadUsers"
          />
        </template>
      </v-text-field>

      <div v-if="users.length" class="user-picks mb-2">
        <v-chip
          size="small"
          variant="tonal"
          :color="!form.userId ? 'primary' : undefined"
          @click="form.userId = undefined"
        >
          全库查询
        </v-chip>
        <v-chip
          v-for="u in users"
          :key="u.id"
          size="small"
          variant="tonal"
          :color="form.userId === u.id ? 'primary' : undefined"
          :title="u.id"
          @click="form.userId = u.id"
        >
          {{ u.name || u.id }}
        </v-chip>
      </div>

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
import { MEDIA_SERVER_TYPES } from '@/composables/mediaServerTypes'
import type { usePtMediaServer } from '@/composables/usePtMediaServer'

const {
  open, dialogTitle, submitLoading, formRef, form, rules, submitForm,
  testLoading, handleTest,
  users, usersLoading, handleLoadUsers
} = usePageState<ReturnType<typeof usePtMediaServer>>()

// 编辑时后端出于安全考虑会把 apiKey 脱敏为空（留空提交 = 沿用已保存值），
// 只有新增时才要求必填，否则编辑弹窗永远校验不过
const apiKeyRules = computed(() => (form.value.id ? [] : toRuleFns(rules.apiKey)))
</script>

<style scoped>
.user-picks {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
