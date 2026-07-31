<template>
  <div class="page-container">
    <v-card :loading="loading" class="table-card">
      <v-card-text>
        <v-form ref="formRef" class="filter-form">
          <div class="section-divider"><span>硬性过滤（不满足即淘汰）</span></div>

          <div class="form-item">
            <v-text-field
              v-model.number="form.minSeeders"
              label="最低做种数"
              type="number"
              min="0"
              density="comfortable"
              variant="outlined"
              class="field-w200"
              :rules="minSeedersRules"
            />
            <span class="form-tip">做种数低于此值的种子直接淘汰</span>
          </div>

          <div class="form-item">
            <v-text-field
              v-model.number="form.minSize"
              label="体积下限"
              type="number"
              min="0"
              max="999"
              density="comfortable"
              variant="outlined"
              class="field-w160"
            />
            <span class="form-tip">GB，0 表示不限</span>
          </div>

          <div class="form-item">
            <v-text-field
              v-model.number="form.maxSize"
              label="体积上限"
              type="number"
              min="0"
              max="999"
              density="comfortable"
              variant="outlined"
              class="field-w160"
            />
            <span class="form-tip">GB，0 表示不限</span>
          </div>

          <div class="form-item">
            <label class="field-label">仅要免费种</label>
            <v-radio-group v-model="form.freeOnly" inline hide-details density="comfortable">
              <v-radio label="否" value="0" />
              <v-radio label="是" value="1" />
            </v-radio-group>
            <span class="form-tip">开启后 50% 促销种也会被淘汰，只留完全免费的</span>
          </div>

          <div class="form-item">
            <label class="field-label">外语电影需中字</label>
            <v-radio-group v-model="form.requireChineseSubtitle" inline hide-details density="comfortable">
              <v-radio label="否" value="0" />
              <v-radio label="是" value="1" />
            </v-radio-group>
            <span class="form-tip">
              外语电影（TMDb 原始语言非中文）的种子标题或描述中未检测到中文字幕标识（CHS/CHT/中字等）时直接淘汰。中文电影自动跳过此规则
            </span>
          </div>

          <div class="form-item">
            <v-text-field
              v-model="form.resolutionWhitelist"
              label="分辨率白名单"
              placeholder="如 2160p,1080p；留空表示不限"
              density="comfortable"
              variant="outlined"
            />
            <span class="form-tip">
              <strong>硬性过滤</strong>：不在白名单内的分辨率直接淘汰。解析不出分辨率的种子在白名单非空时也会被淘汰
            </span>
          </div>

          <div class="form-item">
            <v-text-field
              v-model="form.includeKeywords"
              label="标题包含词"
              placeholder="逗号分隔，命中其一即可；留空表示不限"
              density="comfortable"
              variant="outlined"
            />
          </div>

          <div class="form-item">
            <v-text-field
              v-model="form.excludeKeywords"
              label="标题排除词"
              placeholder="逗号分隔，命中任一即淘汰"
              density="comfortable"
              variant="outlined"
            />
          </div>

          <div class="section-divider"><span>择优排序（从存活的候选里挑一个）</span></div>

          <div class="form-item">
            <v-text-field
              v-model="form.resolutionPriority"
              label="分辨率优先级"
              placeholder="如 2160p,1080p,720p"
              density="comfortable"
              variant="outlined"
            />
            <span class="form-tip">
              <strong>只影响排序</strong>，不做过滤——不在此列表内的分辨率只是排在最后，仍可能被下载。要过滤请用上面的白名单
            </span>
          </div>

          <div class="form-item">
            <v-text-field
              v-model.number="form.preferredSize"
              label="偏好体积"
              type="number"
              min="0"
              max="999"
              density="comfortable"
              variant="outlined"
              class="field-w160"
            />
            <span class="form-tip">GB，0 表示体积不参与择优比较</span>
          </div>

          <div class="form-item">
            <label class="field-label">维度优先顺序</label>
            <div class="dimension-list">
              <div v-for="(dimension, index) in sortOrder" :key="dimension" class="dimension-row">
                <span class="dimension-index">{{ index + 1 }}</span>
                <span class="dimension-label">{{ labelOf(dimension) }}</span>
                <v-btn variant="text" size="small" :disabled="index === 0" @click="moveUp(index)">上移</v-btn>
                <v-btn variant="text" size="small" :disabled="index === sortOrder.length - 1" @click="moveDown(index)">下移</v-btn>
              </div>
            </div>
            <span class="form-tip">
              排在前面的维度先比较。例如把「促销优先」放到「分辨率优先级」之前，就表示宁可要免费的 1080p，也不要收费的 4K
            </span>
          </div>

          <div class="form-item form-actions">
            <v-btn color="primary" :loading="saving" @click="save">保存</v-btn>
            <v-btn variant="outlined" @click="load">重置</v-btn>
          </div>
        </v-form>
      </v-card-text>
    </v-card>
  </div>
</template>

<script setup lang="ts">
import { usePtFilterConfig } from '@/composables/usePtFilterConfig'

const { loading, saving, formRef, form, rules, sortOrder, labelOf, moveUp, moveDown, load, save } =
  usePtFilterConfig()

// Element Plus 表单规则是 { required, message, trigger } 对象格式，
// Vuetify 的 v-text-field :rules 需要函数格式，这里就地转换，不改动 composable
const minSeedersRules = (rules.minSeeders || []).map((rule: any) => {
  return (value: any) => {
    if (rule.required && (value === null || value === undefined || value === '')) {
      return rule.message || '不能为空'
    }
    return true
  }
})
</script>

<style scoped>
.section-divider {
  display: flex;
  align-items: center;
  margin: 20px 0 16px;
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

.section-divider:first-child {
  margin-top: 4px;
}

.form-item {
  margin-bottom: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.field-label {
  font-size: 13px;
  color: var(--osr-text-primary);
  margin-bottom: 2px;
}

.field-w200 {
  max-width: 200px;
}

.field-w160 {
  max-width: 160px;
}

.form-actions {
  flex-direction: row;
  gap: 12px;
  margin-top: 8px;
}

.dimension-list {
  width: 100%;
}

.dimension-row {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}

.dimension-index {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--osr-bg-page);
  font-size: 12px;
}

.dimension-label {
  min-width: 100px;
  flex: 1;
}

.form-tip {
  font-size: 12px;
  color: var(--osr-text-secondary);
  line-height: 1.5;
}

@media (max-width: 768px) {
  .page-container {
    padding: 0;
  }

  .filter-form {
    width: 100%;
  }

  .field-w200,
  .field-w160 {
    max-width: 100%;
  }

  .dimension-row {
    flex-wrap: wrap;
    gap: 6px;
  }

  .dimension-label {
    min-width: 0;
    width: auto;
    flex: 1;
  }
}
</style>
