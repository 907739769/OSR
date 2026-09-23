<template>
  <div class="mobile-page">
    <v-tabs v-model="activeTab" color="primary" density="compact" grow>
      <v-tab value="template" prepend-icon="file-pen">模板<span v-if="templateDirty" class="dirty-dot" /></v-tab>
      <v-tab value="rules" prepend-icon="folder-cog">分类规则<span v-if="rulesDirty" class="dirty-dot" /></v-tab>
      <v-tab value="test" prepend-icon="flask-conical">测试</v-tab>
    </v-tabs>

    <v-window v-model="activeTab">
      <v-window-item value="template">
        <div class="tab-body">
          <!-- 骨架照着「模板输入框 + 按钮 + 预览」的形状画 -->
          <div v-if="templateLoading" class="tab-skeleton osr-skeleton osr-sheen" role="status" aria-busy="true" aria-label="加载中">
            <span class="osr-bone osr-bone--block" style="height: 150px" />
            <div class="tab-skeleton__row">
              <span class="osr-bone tab-skeleton__btn" />
              <span class="osr-bone tab-skeleton__btn" />
            </div>
            <span class="osr-bone osr-bone--block" style="height: 64px" />
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
            <TemplateVariableChips class="template-variables" :variables="templateVariables" @insert="insertVariable" />
            <v-btn color="primary" block :loading="templateSaving" class="mt-2" @click="saveTemplate">保存模板</v-btn>
            <v-btn v-if="defaultTemplate" variant="outlined" block class="mt-2" @click="restoreDefaultTemplate">恢复默认</v-btn>
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
        </div>
      </v-window-item>

      <v-window-item value="rules">
        <div class="tab-body">
          <!-- 骨架照着「提示条 + 一行一条规则」的形状画 -->
          <div v-if="rulesLoading" class="tab-skeleton osr-skeleton osr-sheen" role="status" aria-busy="true" aria-label="加载中">
            <span class="osr-bone osr-bone--block" style="height: 40px" />
            <div v-for="i in 5" :key="i" class="tab-skeleton__row">
              <span class="osr-bone" :style="{ width: ['96px', '72px', '120px', '84px', '64px'][i - 1] }" />
              <span class="osr-bone osr-bone--input" style="flex: 1" />
            </div>
          </div>
          <div v-else>
            <v-alert type="info" variant="tonal" density="compact" class="fallback-hint">
              规则从上到下依次匹配，命中即用该目录；末尾的"兜底"规则在都未命中时生效，无法删除或调整匹配条件。
            </v-alert>

            <div class="section-divider">电影<span v-if="movieRulesDirty" class="dirty-tag">未保存</span></div>
            <RuleTable
              :rules="movieRules" media-type="movie"
              @add="addRule" @remove="removeRule" @move="moveRule"
            />
            <v-btn color="primary" block :loading="savingRulesType === 'movie'" class="rules-save-btn" @click="saveRules('movie')">保存电影分类规则</v-btn>

            <div class="section-divider">剧集<span v-if="tvRulesDirty" class="dirty-tag">未保存</span></div>
            <RuleTable
              :rules="tvRules" media-type="tv"
              @add="addRule" @remove="removeRule" @move="moveRule"
            />
            <v-btn color="primary" block :loading="savingRulesType === 'tv'" class="rules-save-btn" @click="saveRules('tv')">保存剧集分类规则</v-btn>
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
            placeholder="留空则使用已保存的模板"
            rows="4"
            density="compact"
            variant="outlined"
            hint="留空则使用已保存的模板"
            persistent-hint
          />
          <div class="test-actions">
            <v-btn color="primary" block prepend-icon="wand-sparkles" :loading="testLoading" @click="doTest">
              开始分析
            </v-btn>
            <v-btn v-if="templateDirty" variant="outlined" block @click="fillTestTemplate">填入编辑中的模板</v-btn>
          </div>
          <div class="test-cost-hint">会实际请求 TMDb 识别；识别不出时还会调用 AI 补全（如已配置），均消耗对应配额。</div>

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
              <div class="result-info-title">识别参数详情（空值不列）</div>
              <div class="result-info-grid">
                <template v-for="row in testInfoRows" :key="row.key">
                  <div class="info-key">{{ row.label || row.key }}<span v-if="row.label" class="info-name">{{ row.key }}</span></div>
                  <div class="info-value">{{ row.value }}</div>
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
import TemplateVariableChips from '@/components/TemplateVariableChips.vue'
import { useRenameConfig } from '@/composables/useRenameConfig'
import { useTabQuery } from '@/composables/useTabQuery'

const activeTab = useTabQuery(['template', 'rules', 'test'] as const, 'template')
const templateInputRef = ref()

const {
  template, defaultTemplate, templateLoading, templateSaving, previewSamples, previewError, templateVariables,
  doPreview, saveTemplate, restoreDefaultTemplate,
  movieRules, tvRules, rulesLoading, savingRulesType,
  addRule, removeRule, moveRule, saveRules,
  templateDirty, movieRulesDirty, tvRulesDirty, rulesDirty,
  testLoading, testResult, testForm, testPlacement, testInfoRows, fillTestTemplate, doTest
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

.tab-skeleton {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 4px 0;

  &__row {
    display: flex;
    align-items: center;
    gap: 12px;
  }

  &__btn {
    width: 88px;
    height: 36px;
  }
}

.template-tab {
  display: flex;
  flex-direction: column;
}

.template-variables {
  margin-top: 12px;
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

.test-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}

.test-cost-hint {
  margin-top: 6px;
  font-size: var(--osr-fs-xs);
  color: var(--osr-text-secondary);
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
  }

  .info-name {
    margin-left: 6px;
    font-family: var(--osr-font-mono);
    opacity: 0.7;
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

.rules-save-btn {
  margin-top: 10px;
}
</style>
