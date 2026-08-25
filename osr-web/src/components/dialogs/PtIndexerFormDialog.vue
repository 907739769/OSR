<template>
  <FormDialogShell v-model="open" :title="dialogTitle" :submitting="submitLoading" @submit="submitForm">
    <v-form ref="formRef">
      <v-text-field
        v-model="form.name"
        label="名称"
        placeholder="请输入名称"
        :rules="toRuleFns(rules.name)"
      />
      <v-text-field
        v-model="form.url"
        label="接口地址"
        placeholder="如 http://jackett:9117/api/v2.0/indexers/xxx/results/torznab/api"
        :rules="toRuleFns(rules.url)"
      />
      <v-text-field
        v-model="form.apiKey"
        label="apikey"
        type="password"
        :placeholder="form.id ? '留空则不修改 apikey' : '请输入 Torznab apikey'"
        :rules="apiKeyRules"
      />
      <FormField label="分类">
        <v-select
          v-model="categoriesSelected"
          :items="categoryFlatOptions"
          multiple
          chips
          closable-chips
          hide-details
          placeholder="点击右侧「获取分类」后选择，或直接输入分类 ID"
        />
        <v-btn :loading="categoriesLoading" variant="outlined" @click="fetchCategories">获取分类</v-btn>
      </FormField>
      <v-text-field
        v-model.number="form.pollInterval"
        label="轮询周期"
        type="number"
        min="60"
        step="60"
        suffix="秒"
        :rules="toRuleFns(rules.pollInterval)"
      />
      <FormField label="状态">
        <v-radio-group v-model="form.enabled" inline hide-details>
          <v-radio label="启用" value="1" />
          <v-radio label="停用" value="0" />
        </v-radio-group>
      </FormField>

      <FormField label="H&R 考核">
        <v-radio-group v-model="form.hrEnabled" inline hide-details density="comfortable">
          <v-radio label="无" value="0" />
          <v-radio label="有" value="1" />
        </v-radio-group>
        <template #tip>
          该站点是否有 Hit&nbsp;and&nbsp;Run 考核。开启后，来自本站的种子下载完成会进入保种追踪：
          达标前从下载器消失会立刻告警，达标后通知可安全删除；推送时还会把下面的要求写成种子的分享限额，
          防止下载器的自动管理提前把种子清掉
        </template>
      </FormField>

      <template v-if="form.hrEnabled === '1'">
        <v-text-field
          v-model.number="form.hrSeedHours"
          label="最短做种时长"
          type="number"
          min="0"
          suffix="小时"
        />
        <FormField>
          <v-text-field
            v-model.number="form.hrRatio"
            label="最低分享率"
            type="number"
            min="0"
            step="0.1"
          />
          <template #tip>
            两项是<strong>或</strong>的关系，满足任一即视为达标（站点通行表述就是「做满 N 小时或分享率达到 R」）。
            不考核的那一项填 0。<strong>两项都填 0 等于没配</strong>，后端会按未开启 H&amp;R 处理。
            另外 Transmission 的 RPC 没有「最短做种时长」这个概念，该项对 Transmission 只能靠 OSR 侧追踪告警兜底
          </template>
        </FormField>
      </template>
    </v-form>

    <template #extra>
      <v-btn :loading="testLoading" variant="outlined" @click="handleTest">测试连接</v-btn>
    </template>
  </FormDialogShell>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import FormDialogShell from '@/components/dialogs/FormDialogShell.vue'
import FormField from '@/components/FormField.vue'
import { usePageState } from '@/composables/pageStateContext'
import { toRuleFns } from '@/composables/formRules'
import type { usePtIndexer } from '@/composables/usePtIndexer'

const {
  open, dialogTitle, submitLoading, formRef, form, rules, submitForm,
  testLoading, handleTest,
  categoriesLoading, categoryOptions, fetchCategories, categoriesSelected
} = usePageState<ReturnType<typeof usePtIndexer>>()

// 编辑时后端出于安全考虑会把 apikey 脱敏为空（留空提交 = 沿用已保存值），
// 只有新增时才要求必填，否则编辑弹窗永远校验不过
const apiKeyRules = computed(() => (form.value.id ? [] : toRuleFns(rules.apiKey)))

// 原来的父子分类分组结构在 Vuetify v-select 中拍平为一层，父分类照常可选，
// 子分类前缀全角空格保留原有的缩进视觉效果
const categoryFlatOptions = computed(() => {
  const list: { title: string; value: string }[] = []
  categoryOptions.value.forEach(parent => {
    list.push({ title: `${parent.name} (${parent.id})`, value: String(parent.id) })
    parent.children.forEach(child => {
      list.push({ title: `\u3000${child.name} (${child.id})`, value: String(child.id) })
    })
  })
  return list
})
</script>
