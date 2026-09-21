<template>
  <div class="page-container">
    <PageHeader
      icon="replace"
      title="重命名规则设置"
      desc="配置文件名生成模板与分类目录规则，可在下方直接测试解析效果"
    />

    <v-card class="table-card">
      <v-tabs v-model="activeTab" color="primary">
        <v-tab value="template" prepend-icon="file-pen">文件名模板<span v-if="templateDirty" class="dirty-dot" /></v-tab>
        <v-tab value="rules" prepend-icon="folder-cog">分类规则<span v-if="rulesDirty" class="dirty-dot" /></v-tab>
        <v-tab value="test" prepend-icon="flask-conical">重命名测试</v-tab>
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
                  <v-btn v-if="defaultTemplate" variant="outlined" @click="restoreDefaultTemplate">恢复默认</v-btn>
                </div>
                <v-alert v-if="previewError" :text="previewError" type="error" variant="tonal" class="preview-alert" />
                <v-alert v-else type="success" variant="tonal" class="preview-alert">
                  <div class="preview-row">
                    <span class="preview-kind">电影</span>
                    <span class="preview-text">{{ previewSamples.movie || '（空）' }}</span>
                  </div>
                  <div class="preview-row">
                    <span class="preview-kind">剧集</span>
                    <span class="preview-text">{{ previewSamples.tv || '（空）' }}</span>
                  </div>
                </v-alert>
              </div>
              <TemplateVariableChips class="template-variables" :variables="templateVariables" @insert="insertVariable" />
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
                规则从上到下依次匹配，命中即用该目录；列表末尾的"兜底"规则在都未命中时生效，无法删除或调整匹配条件，仅目录名可编辑。
              </v-alert>

              <div class="section-divider">电影<span v-if="movieRulesDirty" class="dirty-tag">未保存</span></div>
              <RuleTable
                :rules="movieRules" media-type="movie"
                @add="addRule" @remove="removeRule" @move="moveRule"
              />
              <div class="rules-actions">
                <v-btn color="primary" :loading="savingRulesType === 'movie'" @click="saveRules('movie')">保存电影分类规则</v-btn>
              </div>

              <div class="section-divider">剧集<span v-if="tvRulesDirty" class="dirty-tag">未保存</span></div>
              <RuleTable
                :rules="tvRules" media-type="tv"
                @add="addRule" @remove="removeRule" @move="moveRule"
              />
              <div class="rules-actions">
                <v-btn color="primary" :loading="savingRulesType === 'tv'" @click="saveRules('tv')">保存剧集分类规则</v-btn>
              </div>
            </div>
          </div>
        </v-window-item>

        <v-window-item value="test">
          <div class="tab-body">
            <div class="test-tab">
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
              <v-btn color="primary" prepend-icon="wand-sparkles" :loading="testLoading" class="mt-3" @click="doTest">
                开始分析
              </v-btn>

              <div v-if="testResult" class="test-result">
                <v-alert type="success" variant="tonal" density="compact" class="mb-3">
                  <template #title>重命名结果预览</template>
                  <div class="result-text">{{ testResult.renamed }}</div>
                </v-alert>

                <div v-if="testPlacement" class="result-placement">
                  <div class="placement-row">
                    <span class="placement-label">判定类型</span>
                    <span class="placement-value">{{ testPlacement.mediaTypeText }}（按解析出的季集号推断）</span>
                  </div>
                  <div class="placement-row">
                    <span class="placement-label">命中规则</span>
                    <span class="placement-value">{{ testPlacement.ruleText }} → {{ testPlacement.category }}</span>
                  </div>
                  <div class="placement-row">
                    <span class="placement-label">目标路径</span>
                    <span class="placement-value placement-path">{{ testPlacement.destPath }}</span>
                  </div>
                </div>
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
          </div>
        </v-window-item>
      </v-window>
    </v-card>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
import RuleTable from './RuleTable.vue'
import TemplateVariableChips from '@/components/TemplateVariableChips.vue'
import { useRenameConfig } from '@/composables/useRenameConfig'

const activeTab = ref('template')
const templateInputRef = ref()

const {
  template, defaultTemplate, templateLoading, templateSaving, previewSamples, previewError, templateVariables,
  doPreview, saveTemplate, restoreDefaultTemplate,
  movieRules, tvRules, rulesLoading, savingRulesType,
  addRule, removeRule, moveRule, saveRules,
  templateDirty, movieRulesDirty, tvRulesDirty, rulesDirty,
  testLoading, testResult, testForm, testPlacement, doTest
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
    width: 260px;
    flex-shrink: 0;
  }
}

.preview-alert {
  margin-top: 12px;

  .preview-text {
    font-family: var(--osr-font-mono);
    word-break: break-all;
    white-space: pre-wrap;
  }

  .preview-row {
    display: flex;
    gap: 10px;
    align-items: baseline;

    & + .preview-row {
      margin-top: 4px;
    }
  }

  .preview-kind {
    flex: 0 0 auto;
    font-size: var(--osr-fs-xs);
    opacity: 0.75;
  }
}

.test-tab {
  max-width: 640px;
}

.test-result {
  margin-top: 12px;

  .result-text {
    font-family: var(--osr-font-mono);
    word-break: break-all;
    white-space: pre-wrap;
  }
}

.result-placement {
  margin-bottom: 12px;
  display: flex;
  flex-direction: column;
  gap: 6px;

  .placement-row {
    display: flex;
    gap: 12px;
    align-items: baseline;
  }

  .placement-label {
    flex: 0 0 auto;
    font-size: var(--osr-fs-xs);
    color: var(--osr-text-secondary);
  }

  .placement-value {
    font-size: var(--osr-fs-sm);
    color: var(--osr-text-primary);
    word-break: break-all;
  }

  .placement-path {
    font-family: var(--osr-font-mono);
  }
}

.result-info-card {
  background: var(--osr-bg-page);
  border-radius: var(--osr-radius-md);
  padding: 12px 14px;

  .result-info-title {
    font-size: 12px;
    font-weight: 600;
    color: var(--osr-text-secondary);
    margin-bottom: 8px;
  }

  .result-info-grid {
    display: grid;
    grid-template-columns: max-content 1fr;
    row-gap: 6px;
    column-gap: 16px;
    max-height: 300px;
    overflow: auto;
  }

  .info-key {
    font-size: 12px;
    color: var(--osr-text-secondary);
    font-family: var(--osr-font-mono);
    white-space: nowrap;
  }

  .info-value {
    font-size: 13px;
    color: var(--osr-text-primary);
    word-break: break-all;
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

.template-actions {
  display: flex;
  gap: 8px;
}

/* 有未保存修改时挂在 tab 标题与分节标题上的提示 */
.dirty-dot {
  display: inline-block;
  width: 6px;
  height: 6px;
  margin-left: 6px;
  border-radius: 50%;
  background: rgb(var(--v-theme-warning));
  vertical-align: middle;
}

.dirty-tag {
  margin-left: 8px;
  font-size: var(--osr-fs-xs);
  font-weight: 400;
  color: rgb(var(--v-theme-warning));
}

.fallback-hint {
  margin-bottom: 4px;
}

@media (max-width: 768px) {
  .tab-body {
    padding: 12px;
  }

  .template-tab {
    flex-direction: column;

    .template-variables {
      width: 100%;
    }
  }
}
</style>
