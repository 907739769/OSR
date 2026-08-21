<template>
  <div class="mobile-page">
    <v-tabs v-model="activeTab" color="primary" density="compact" grow>
      <v-tab value="template" prepend-icon="mdi-file-document-edit-outline">模板</v-tab>
      <v-tab value="rules" prepend-icon="mdi-folder-cog-outline">分类规则</v-tab>
      <v-tab value="test" prepend-icon="mdi-flask-outline">测试</v-tab>
    </v-tabs>

    <v-window v-model="activeTab">
      <v-window-item value="template">
        <div class="tab-body">
          <div v-if="templateLoading" class="tab-loading">
            <v-progress-circular indeterminate color="primary" size="32" />
          </div>
          <div v-else class="template-tab">
            <v-textarea
              ref="templateInputRef"
              v-model="template"
              rows="6"
              variant="outlined"
              placeholder="Pebble 语法，例如 {{ title }} ({{ year }}).{{ extension }}"
              @update:model-value="doPreview"
            />
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
            <v-btn color="primary" block :loading="templateSaving" class="mt-2" @click="saveTemplate">保存模板</v-btn>
            <v-alert v-if="previewError" :text="previewError" type="error" variant="tonal" class="preview-alert" />
            <v-alert v-else type="success" variant="tonal" class="preview-alert">
              <div class="preview-text">{{ previewResult || '（预览为空）' }}</div>
            </v-alert>
          </div>
        </div>
      </v-window-item>

      <v-window-item value="rules">
        <div class="tab-body">
          <div v-if="rulesLoading" class="tab-loading">
            <v-progress-circular indeterminate color="primary" size="32" />
          </div>
          <div v-else>
            <v-alert type="info" variant="tonal" density="compact" class="fallback-hint">
              规则从上到下依次匹配，命中即用该目录；末尾的"兜底"规则在都未命中时生效，无法删除或调整匹配条件。
            </v-alert>

            <div class="section-divider">电影</div>
            <RuleTable
              :rules="movieRules" media-type="movie"
              @add="addRule" @remove="removeRule" @move="moveRule"
            />
            <v-btn color="primary" block :loading="rulesSaving" class="rules-save-btn" @click="saveRules('movie')">保存电影分类规则</v-btn>

            <div class="section-divider">剧集</div>
            <RuleTable
              :rules="tvRules" media-type="tv"
              @add="addRule" @remove="removeRule" @move="moveRule"
            />
            <v-btn color="primary" block :loading="rulesSaving" class="rules-save-btn" @click="saveRules('tv')">保存剧集分类规则</v-btn>
          </div>
        </div>
      </v-window-item>

      <v-window-item value="test">
        <div class="tab-body">
          <v-textarea
            v-model="testForm.filename"
            label="原文件名"
            placeholder="例如: The.Movie.2024.1080p.mkv"
            rows="3"
            density="compact"
            variant="outlined"
          />
          <v-textarea
            v-model="testForm.template"
            label="重命名模板"
            placeholder="留空则使用默认配置"
            rows="4"
            density="compact"
            variant="outlined"
            hint="留空则使用默认配置"
            persistent-hint
          />
          <v-btn color="primary" block prepend-icon="mdi-auto-fix" :loading="testLoading" class="mt-3" @click="doTest">
            开始分析
          </v-btn>

          <div v-if="testResult" class="test-result">
            <v-alert type="success" variant="tonal" density="compact" class="mb-3">
              <template #title>重命名结果预览</template>
              <div class="result-text">{{ testResult.renamed }}</div>
            </v-alert>
            <div class="result-info-card">
              <div class="result-info-title">识别参数详情</div>
              <div class="result-info-grid">
                <template v-for="(value, key) in testResult.info" :key="key">
                  <div class="info-key">{{ key }}</div>
                  <div class="info-value">{{ value ?? '—' }}</div>
                </template>
              </div>
            </div>
          </div>
        </div>
      </v-window-item>
    </v-window>
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
  addRule, removeRule, moveRule, saveRules,
  testLoading, testResult, testForm, doTest
} = useRenameConfig()

/** 插入到光标位置而不是简单追加到末尾，取不到 DOM 时退化为追加到末尾 */
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
.tab-body {
  padding: 12px 2px;
}

/* v-tabs 的 grow 属性会给 .v-tabs 根元素本身也加上 flex-grow:1；
   在 .mobile-page 这种 flex column + min-height 容器里，tabs 会把 min-height
   撑出来的整段空白吃掉、垂直拉伸占满，把下面的 tab 内容挤到底部，
   显式锁死为不参与主轴伸缩 */
:deep(.v-tabs) {
  flex: 0 0 auto;
}

.tab-loading {
  display: flex;
  justify-content: center;
  padding: 60px 0;
}

.template-tab {
  display: flex;
  flex-direction: column;
}

.template-variables {
  margin-top: 12px;

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

.preview-alert {
  margin-top: 12px;

  .preview-text {
    font-family: var(--osr-font-mono);
    word-break: break-all;
    white-space: pre-wrap;
  }
}

.test-result {
  margin-top: 12px;

  .result-text {
    font-family: var(--osr-font-mono);
    word-break: break-all;
    white-space: pre-wrap;
  }
}

.result-info-card {
  background: var(--osr-bg-page);
  border-radius: var(--osr-radius-md);
  padding: 10px 12px;

  .result-info-title {
    font-size: 12px;
    font-weight: 600;
    color: var(--osr-text-secondary);
    margin-bottom: 6px;
  }

  .result-info-grid {
    display: flex;
    flex-direction: column;
    gap: 4px;
    max-height: 300px;
    overflow: auto;
  }

  .info-key {
    font-size: 11px;
    color: var(--osr-text-secondary);
    font-family: var(--osr-font-mono);
  }

  .info-value {
    font-size: 13px;
    color: var(--osr-text-primary);
    word-break: break-all;
    margin-bottom: 2px;
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

.fallback-hint {
  margin-bottom: 4px;
}

.rules-save-btn {
  margin-top: 10px;
}
</style>
