<template>
  <FormDialogShell v-model="open" :title="dialogTitle" :submitting="submitLoading" @submit="submitForm">
    <v-form ref="formRef">
      <v-text-field
        v-model="form.name"
        label="规则名"
        placeholder="如「qB 下完搬到 TR 保种」"
        :rules="toRuleFns(rules.name)"
        class="mb-2"
      />
      <v-select
        v-model="form.sourceDownloaderId"
        :items="sourceOptions"
        label="源下载器"
        :rules="toRuleFns(rules.sourceDownloaderId)"
        hint="Transmission 无法导出种子文件，不能作为来源，因此不在此列出"
        persistent-hint
        class="mb-3"
      />
      <v-select
        v-model="form.targetDownloaderId"
        :items="downloaderOptions"
        label="目标下载器"
        :rules="toRuleFns(rules.targetDownloaderId)"
        class="mb-2"
      />
      <div class="inline-fields mb-2">
        <v-text-field
          v-model.number="form.minSeedHours"
          label="最短做种时长(小时)"
          type="number"
          hint="在源下载器上做满这么久才搬，0 表示不限"
          persistent-hint
        />
        <v-text-field
          v-model.number="form.maxPerRound"
          label="单轮上限"
          type="number"
          hint="每轮最多发起多少个转移"
          persistent-hint
        />
      </div>
      <div class="inline-fields mb-2">
        <v-text-field v-model.number="form.minSizeGb" label="体积下限(GB)" type="number" />
        <v-text-field v-model.number="form.maxSizeGb" label="体积上限(GB)" type="number" placeholder="留空表示不限" />
      </div>
      <v-text-field
        v-model="form.includeTags"
        label="仅转移带这些标签的种子"
        placeholder="逗号分隔，留空表示不限"
        class="mb-2"
      />
      <v-text-field
        v-model="form.excludeTags"
        label="排除标签"
        placeholder="逗号分隔，带其中任一标签的种子永不转移"
        class="mb-2"
      />
      <v-text-field
        v-model="form.targetTag"
        label="目标端标签"
        placeholder="加到目标下载器时打的标签"
        class="mb-2"
      />
      <v-textarea
        v-model="form.pathMapping"
        label="保存路径映射"
        rows="2"
        placeholder="两个下载器挂载一致时留空；不一致填 [{&quot;from&quot;:&quot;/downloads&quot;,&quot;to&quot;:&quot;/data/downloads&quot;}]"
        hint="目标下载器要能在映射后的路径下找到同一份文件，否则校验不通过、转移会被撤销"
        persistent-hint
        class="mb-3"
      />
      <v-text-field
        v-model.number="form.verifyTimeoutMinutes"
        label="校验超时(分钟)"
        type="number"
        hint="目标端校验超过这个时间仍未完成即判失败并撤销"
        persistent-hint
        class="mb-3"
      />
      <FormField label="转移成功后" tip="删除的只是源下载器里的种子任务，数据文件永远保留——那正是目标下载器接手做种的数据">
        <v-radio-group v-model="form.deleteSource" inline hide-details>
          <v-radio label="删除源种子" value="1" />
          <v-radio label="保留源种子" value="0" />
        </v-radio-group>
      </FormField>
      <FormField label="状态" tip="新建规则默认停用，确认预览结果符合预期后再启用">
        <v-radio-group v-model="form.enabled" inline hide-details>
          <v-radio label="启用" value="1" />
          <v-radio label="停用" value="0" />
        </v-radio-group>
      </FormField>
      <v-text-field v-model="form.remark" label="备注" class="mb-2" />
    </v-form>
  </FormDialogShell>
</template>

<script setup lang="ts">
import FormDialogShell from '@/components/dialogs/FormDialogShell.vue'
import FormField from '@/components/FormField.vue'
import { usePageState } from '@/composables/pageStateContext'
import { toRuleFns } from '@/composables/formRules'
import type { usePtTransferRule } from '@/composables/usePtTransferRule'

const {
  open, dialogTitle, submitLoading, formRef, form, rules, submitForm,
  downloaderOptions, sourceOptions
} = usePageState<ReturnType<typeof usePtTransferRule>>()
</script>
