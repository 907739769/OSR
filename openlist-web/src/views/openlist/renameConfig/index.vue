<template>
  <div class="page-container">
    <v-card class="table-card">
      <v-tabs v-model="activeTab" color="primary">
        <v-tab value="template">文件名模板</v-tab>
        <v-tab value="rules">分类规则</v-tab>
      </v-tabs>

      <v-window v-model="activeTab">
        <v-window-item value="template">
          <div class="tab-body">
            <div v-if="templateLoading" class="tab-loading">
              <v-progress-circular indeterminate color="primary" size="32" />
            </div>
            <div v-else class="template-tab">
              <div class="template-editor">
                <v-textarea
                  ref="templateInputRef"
                  v-model="template"
                  rows="6"
                  variant="outlined"
                  placeholder="Pebble 语法，例如 {{ title }} ({{ year }}).{{ extension }}"
                  @update:model-value="doPreview"
                />
                <div class="template-actions">
                  <v-btn color="primary" :loading="templateSaving" @click="saveTemplate">保存模板</v-btn>
                </div>
                <v-alert v-if="previewError" :text="previewError" type="error" variant="tonal" class="preview-alert" />
                <v-alert v-else type="success" variant="tonal" class="preview-alert">
                  <div class="preview-text">{{ previewResult || '（预览为空）' }}</div>
                </v-alert>
              </div>
              <div class="template-variables">
                <div class="variables-title">可用变量（点击插入）</div>
                <v-chip
                  v-for="v in TEMPLATE_VARIABLES"
                  :key="v"
                  class="variable-tag"
                  size="small"
                  @click="insertVariable(v)"
                >{{ v }}</v-chip>
              </div>
            </div>
          </div>
        </v-window-item>

        <v-window-item value="rules">
          <div class="tab-body">
            <div v-if="rulesLoading" class="tab-loading">
              <v-progress-circular indeterminate color="primary" size="32" />
            </div>
            <div v-else>
              <div class="section-divider">电影</div>
              <RuleTable
                :rules="movieRules" media-type="movie"
                @add="addRule" @remove="removeRule" @move="moveRule"
              />
              <div class="rules-actions">
                <v-btn color="primary" :loading="rulesSaving" @click="saveRules('movie')">保存电影分类规则</v-btn>
              </div>

              <div class="section-divider">剧集</div>
              <RuleTable
                :rules="tvRules" media-type="tv"
                @add="addRule" @remove="removeRule" @move="moveRule"
              />
              <div class="rules-actions">
                <v-btn color="primary" :loading="rulesSaving" @click="saveRules('tv')">保存剧集分类规则</v-btn>
              </div>
            </div>
          </div>
        </v-window-item>
      </v-window>
    </v-card>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick } from 'vue'
import RuleTable from './RuleTable.vue'
import { useRenameConfig, TEMPLATE_VARIABLES } from '@/composables/useRenameConfig'

const activeTab = ref('template')
const templateInputRef = ref()

const {
  template, templateLoading, templateSaving, previewResult, previewError,
  doPreview, saveTemplate,
  movieRules, tvRules, rulesLoading, rulesSaving,
  addRule, removeRule, moveRule, saveRules
} = useRenameConfig()

/**
 * 插入到光标位置而不是简单追加到末尾：VTextarea 把底层 <textarea> DOM
 * 挂在组件根元素内，通过 $el 查询获取，取不到时（理论上不会发生）退化为追加到末尾。
 */
const insertVariable = (varName: string) => {
  const snippet = `{{ ${varName} }}`
  const textarea: HTMLTextAreaElement | undefined = templateInputRef.value?.$el?.querySelector('textarea')
  if (!textarea) {
    template.value += snippet
    doPreview()
    return
  }
  const start = textarea.selectionStart ?? template.value.length
  const end = textarea.selectionEnd ?? template.value.length
  template.value = template.value.slice(0, start) + snippet + template.value.slice(end)
  doPreview()
  nextTick(() => {
    const cursor = start + snippet.length
    textarea.focus()
    textarea.setSelectionRange(cursor, cursor)
  })
}
</script>

<style scoped lang="scss">
.page-container {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.table-card {
  border: none;
  border-radius: var(--osr-radius-lg);
  box-shadow: var(--osr-shadow-base);
}

.tab-body {
  padding: 16px;
}

.tab-loading {
  display: flex;
  justify-content: center;
  padding: 60px 0;
}

.template-tab {
  display: flex;
  gap: 20px;

  .template-editor {
    flex: 1;
    min-width: 0;
  }

  .template-variables {
    width: 220px;
    flex-shrink: 0;

    .variables-title {
      font-size: 13px;
      color: var(--osr-text-secondary);
      margin-bottom: 8px;
    }

    .variable-tag {
      margin: 0 6px 6px 0;
      cursor: pointer;
    }
  }
}

.preview-alert {
  margin-top: 12px;

  .preview-text {
    font-family: Consolas, monospace;
    word-break: break-all;
    white-space: pre-wrap;
  }
}

.section-divider {
  position: relative;
  margin: 20px 0 12px;
  padding-left: 10px;
  font-size: 14px;
  font-weight: 600;
  color: var(--osr-text-primary);
  border-left: 3px solid var(--osr-primary);
}

.template-actions,
.rules-actions {
  margin-top: 12px;
}

@media (max-width: 768px) {
  .tab-body {
    padding: 12px;
  }

  .template-tab {
    flex-direction: column;
  }

  .template-variables {
    width: 100% !important;
  }
}
</style>
