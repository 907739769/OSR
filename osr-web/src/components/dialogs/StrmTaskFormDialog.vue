<template>
  <FormDialogShell v-model="open" :title="dialogTitle" :submitting="submitLoading" @submit="handleSubmitClick">
    <v-form ref="formRef">
      <FormField label="STRM目录">
        <DirectoryTreeSelect v-model="form.strmTaskPath" type="openlist" placeholder="请选择STRM目录" />
      </FormField>
      <FormField label="状态">
        <v-radio-group v-model="form.strmTaskStatus" inline hide-details>
          <v-radio label="停用" value="0" />
          <v-radio label="启用" value="1" />
        </v-radio-group>
      </FormField>

      <div class="section-divider"><span>任务级覆盖</span></div>
      <p class="override-tip">只勾选需要覆盖的项，不勾选的沿用全局配置（参数设置页里的 STRM 相关项）。</p>

      <div class="override-row">
        <v-checkbox-btn v-model="overrideForm.outputDir.enabled" label="输出根目录" class="override-toggle" />
        <v-text-field
          v-model="overrideForm.outputDir.value"
          placeholder="如 /data/strm-anime"
          density="compact"
          hide-details
          :disabled="!overrideForm.outputDir.enabled"
          class="override-input"
        />
      </div>

      <div class="override-row">
        <v-checkbox-btn v-model="overrideForm.downloadSub.enabled" label="下载字幕" class="override-toggle" />
        <v-radio-group
          v-model="overrideForm.downloadSub.value"
          inline
          hide-details
          density="compact"
          :disabled="!overrideForm.downloadSub.enabled"
        >
          <v-radio label="否" value="0" />
          <v-radio label="是" value="1" />
        </v-radio-group>
      </div>

      <div class="override-row">
        <v-checkbox-btn v-model="overrideForm.minFileSize.enabled" label="最小文件体积" class="override-toggle" />
        <v-text-field
          v-model.number="overrideForm.minFileSize.value"
          type="number"
          min="0"
          suffix="MB"
          density="compact"
          hide-details
          :disabled="!overrideForm.minFileSize.enabled"
          class="override-input override-input--num"
        />
      </div>
      <p class="override-tip">体积填 0 表示不限。小于该体积的视频不生成 STRM，常用来滤掉花絮和预告。</p>
    </v-form>
  </FormDialogShell>
</template>

<script setup lang="ts">
import FormDialogShell from '@/components/dialogs/FormDialogShell.vue'
import FormField from '@/components/FormField.vue'
import DirectoryTreeSelect from '@/components/DirectoryTreeSelect/index.vue'
import { usePageState } from '@/composables/pageStateContext'
import { message } from '@/composables/useMessage'
import type { useStrmTask } from '@/composables/useStrmTask'

const {
  open, dialogTitle, submitLoading, formRef, form,
  overrideForm, submitFormWithOverride
} = usePageState<ReturnType<typeof useStrmTask>>()

// DirectoryTreeSelect 不支持 v-form 的 :rules 校验，改为提交前手动校验必填项
const handleSubmitClick = () => {
  if (!form.value.strmTaskPath) {
    message.warning('STRM目录不能为空')
    return
  }
  submitFormWithOverride()
}
</script>

<style scoped lang="scss">
.section-divider {
  display: flex;
  align-items: center;
  margin: 20px 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--osr-text-primary);

  span {
    padding-right: 12px;
  }

  &::after {
    content: '';
    flex: 1;
    height: 1px;
    background: var(--osr-border-light);
  }
}

.override-tip {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--osr-text-secondary);
  line-height: 1.5;
}

.override-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

/* 勾选框用 v-checkbox-btn 而不是 v-checkbox：后者是表单字段，内部套一层 VInput，
   放进这种紧凑行里就得写 min-height/label opacity 去压（ptSubscription 的
   .override-checkbox 正是那样），需要覆盖样式本身就是选错组件的信号 */
.override-toggle {
  flex: none;
  /* 130px 是「最小文件体积」这个最长标签不折行的宽度 */
  width: 130px;
}

.override-input {
  flex: 1;
  min-width: 160px;
}

.override-input--num {
  flex: none;
  width: 140px;
  min-width: 0;
}

/* 窄屏一行放不下「勾选框 + 控件」，改成纵向堆叠。
   判据是可用宽度而不是 store 里的 device：手机横屏（926px）下弹窗有 850px 可用，
   横排照样放得下，按设备类型切会把它一起压成竖排 */
@media (max-width: 768px) {
  .override-row {
    flex-direction: column;
    align-items: stretch;
    gap: 4px;
    margin-bottom: 12px;
  }

  .override-toggle,
  .override-input,
  .override-input--num {
    width: auto;
    flex: none;
    min-width: 0;
  }
}
</style>
