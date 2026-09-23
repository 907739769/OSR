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
                规则从上到下依次匹配，命中即用该目录；列表末尾的"兜底"规则在都未命中时生效，无法删除或调整匹配条件，仅目录名可编辑。
              </v-alert>

              <div class="section-divider">电影<span v-if="movieRulesDirty" class="dirty-tag">未保存</span></div>
              <RuleTable
                :rules="movieRules" media-type="movie"
                @add="addRule" @remove="removeRule" @move="moveRule"
              />

              <div class="section-divider">剧集<span v-if="tvRulesDirty" class="dirty-tag">未保存</span></div>
              <RuleTable
                :rules="tvRules" media-type="tv"
                @add="addRule" @remove="removeRule" @move="moveRule"
              />
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
                placeholder="留空则使用已保存的模板"
                rows="4"
                density="compact"
                variant="outlined"
                hint="留空则使用已保存的模板"
                persistent-hint
              />
              <div class="test-actions">
                <v-btn color="primary" prepend-icon="wand-sparkles" :loading="testLoading" @click="doTest">
                  开始分析
                </v-btn>
                <v-btn v-if="templateDirty" variant="outlined" @click="fillTestTemplate">填入编辑中的模板</v-btn>
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
          </div>
        </v-window-item>
      </v-window>
    </v-card>

    <!--
      分类规则的保存条放在卡片**外面**、吸在视口底部。放在 tab 内容里是吸不住的：v-card 与 v-window
      都是 overflow: hidden，sticky 会贴在那个不滚动的祖先上。原先两个保存按钮各跟在一张表后面，
      剧集表默认 9 行，改完电影规则往下滚去看剧集时，电影那个保存按钮已经在屏幕外了。
    -->
    <div v-if="activeTab === 'rules' && !rulesLoading" class="rules-save-bar">
      <span class="save-bar-status" :class="{ 'save-bar-status--dirty': rulesDirty }">
        {{ rulesDirty ? `未保存：${rulesDirtyText}分类规则` : '分类规则已全部保存' }}
      </span>
      <v-btn
        color="primary"
        :variant="movieRulesDirty ? 'flat' : 'outlined'"
        :loading="savingRulesType === 'movie'"
        @click="saveRules('movie')"
      >保存电影分类规则</v-btn>
      <v-btn
        color="primary"
        :variant="tvRulesDirty ? 'flat' : 'outlined'"
        :loading="savingRulesType === 'tv'"
        @click="saveRules('tv')"
      >保存剧集分类规则</v-btn>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, nextTick } from 'vue'
import PageHeader from '@/components/PageHeader.vue'
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

/** 保存条上说清是哪一侧没存 */
const rulesDirtyText = computed(() =>
  [movieRulesDirty.value && '电影', tvRulesDirty.value && '剧集'].filter(Boolean).join('、'))

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

.template-actions {
  margin-top: 12px;
}

.rules-save-bar {
  position: sticky;
  bottom: 12px;
  z-index: 2;
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  border: 1px solid var(--osr-border-light);
  border-radius: var(--osr-radius-md);
  background: var(--osr-surface);
  box-shadow: var(--osr-shadow-md);
}

.save-bar-status {
  flex: 1;
  font-size: var(--osr-fs-sm);
  color: var(--osr-text-secondary);

  &--dirty {
    color: rgb(var(--v-theme-warning));
  }
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
