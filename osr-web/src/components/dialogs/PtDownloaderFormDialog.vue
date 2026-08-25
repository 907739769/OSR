<template>
  <FormDialogShell v-model="open" :title="dialogTitle" :submitting="submitLoading" @submit="submitForm">
    <v-form ref="formRef">
      <v-text-field
        v-model="form.name"
        label="名称"
        placeholder="请输入名称"
        :rules="toRuleFns(rules.name)"
      />
      <v-select
        v-model="form.type"
        label="类型"
        :items="DOWNLOADER_TYPE_OPTIONS"
      />
      <v-text-field
        v-model="form.host"
        label="主机"
        placeholder="主机名或 IP，不含协议与端口"
        :rules="toRuleFns(rules.host)"
      />
      <v-text-field
        v-model.number="form.port"
        label="端口"
        type="number"
        min="1"
        max="65535"
        :rules="toRuleFns(rules.port)"
      />
      <FormField label="HTTPS">
        <v-radio-group v-model="form.useHttps" inline hide-details>
          <v-radio label="关闭" value="0" />
          <v-radio label="开启" value="1" />
        </v-radio-group>
      </FormField>
      <v-text-field
        v-model="form.username"
        label="用户名"
        placeholder="请输入用户名"
      />
      <v-text-field
        v-model="form.password"
        label="密码"
        type="password"
        :placeholder="form.id ? '留空则不修改密码' : '请输入密码'"
      />
      <FormField>
        <v-text-field
          v-model="form.savePath"
          label="保存路径"
          placeholder="种子保存路径"
          :rules="toRuleFns(rules.savePath)"
          @blur="handleSavePathBlur"
        />
        <template v-if="savePathWarning" #tip>
          <span class="save-path-warning">{{ savePathWarning }}</span>
        </template>
      </FormField>
      <v-text-field
        v-model="form.tag"
        label="标签"
        placeholder="推送种子时打的标签"
        :rules="toRuleFns(rules.tag)"
      />
      <FormField tip="0 表示不限，达到上限时新任务会等到下一轮自动重试">
        <v-text-field
          v-model.number="form.maxConcurrent"
          label="最大并发数"
          type="number"
          min="0"
          :rules="toRuleFns(rules.maxConcurrent)"
        />
      </FormField>
      <FormField label="状态">
        <v-radio-group v-model="form.enabled" inline hide-details>
          <v-radio label="启用" value="1" />
          <v-radio label="停用" value="0" />
        </v-radio-group>
      </FormField>
      <FormField tip="推送种子时按分类在保存路径下自动建子目录，同步到网盘的目录结构会一并跟随">
        <v-select
          v-model="form.smartClassifyLevel"
          label="智能分类"
          :items="SMART_CLASSIFY_LEVEL_OPTIONS"
        />
      </FormField>
      <FormField tip="「仅做种」的下载器不参与订阅下载的负载均衡，用于接收 IYUU 转移/辅种过来的种子">
        <v-select v-model="form.role" label="分工" :items="ROLE_OPTIONS" />
      </FormField>
      <FormField
        label="自动删种"
        tip="按「删种规则」定期清理已达标的种子。仍在 H&R 考核中的种子永远不删；辅种整组同删。开启后请先用规则弹窗里的「预览」确认判定结果"
      >
        <v-radio-group v-model="form.autoDeleteEnabled" inline hide-details>
          <v-radio label="关闭" value="0" />
          <v-radio label="开启" value="1" />
        </v-radio-group>
      </FormField>
      <template v-if="form.autoDeleteEnabled === '1'">
        <FormField tip="逗号分隔。带其中任一标签的种子及其辅种组永不删除">
          <v-text-field
            v-model="form.autoDeleteExcludeTags"
            label="删种排除标签"
            placeholder="如：keep,手动保留"
          />
        </FormField>
        <FormField tip="0 表示不限。规则配错时最多损失一轮的量，不会一次清空整个保种盘">
          <v-text-field
            v-model.number="form.autoDeleteMaxPerRound"
            label="单轮最多删除组数"
            type="number"
            min="0"
          />
        </FormField>
      </template>
    </v-form>

    <template #extra>
      <v-btn :loading="testLoading" variant="outlined" @click="handleTest">测试连接</v-btn>
    </template>
  </FormDialogShell>
</template>

<script setup lang="ts">
import FormDialogShell from '@/components/dialogs/FormDialogShell.vue'
import FormField from '@/components/FormField.vue'
import { usePageState } from '@/composables/pageStateContext'
import { toRuleFns } from '@/composables/formRules'
import {
  DOWNLOADER_TYPE_OPTIONS,
  SMART_CLASSIFY_LEVEL_OPTIONS,
  ROLE_OPTIONS,
  type usePtDownloader
} from '@/composables/usePtDownloader'

const {
  open, dialogTitle, submitLoading, formRef, form, rules, submitForm,
  testLoading, handleTest, savePathWarning, handleSavePathBlur
} = usePageState<ReturnType<typeof usePtDownloader>>()
</script>

<style scoped lang="scss">
/* 保存路径的风险提示用警告色，区别于普通说明文字 */
.save-path-warning {
  color: rgb(var(--v-theme-warning));
}
</style>
