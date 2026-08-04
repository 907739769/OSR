<template>
  <div class="page-container">
    <PageHeader
      icon="mdi-filter-cog-outline"
      title="PT 过滤规则"
      desc="全局的种子硬性过滤与择优排序规则，可被单条订阅覆盖"
    />

    <v-card :loading="loading" class="table-card">
      <v-card-text>
        <v-form ref="formRef" class="filter-form">
          <div class="section-divider"><span>硬性过滤（不满足即淘汰）</span></div>

          <FormField>
            <v-text-field
              v-model.number="form.minSeeders"
              label="最低做种数"
              type="number"
              min="0"
              density="comfortable"
              variant="outlined"
              class="field-num-lg"
              :rules="minSeedersRules"
            />
            <template #tip>
              做种数低于此值的种子直接淘汰
            </template>
          </FormField>

          <FormField>
            <v-text-field
              v-model.number="form.minSize"
              label="体积下限"
              type="number"
              min="0"
              max="999"
              density="comfortable"
              variant="outlined"
              class="field-num"
            />
            <template #tip>
              GB，0 表示不限
            </template>
          </FormField>

          <FormField>
            <v-text-field
              v-model.number="form.maxSize"
              label="体积上限"
              type="number"
              min="0"
              max="999"
              density="comfortable"
              variant="outlined"
              class="field-num"
            />
            <template #tip>
              GB，0 表示不限
            </template>
          </FormField>

          <FormField label="仅要免费种">
            <v-radio-group v-model="form.freeOnly" inline hide-details density="comfortable">
              <v-radio label="否" value="0" />
              <v-radio label="是" value="1" />
            </v-radio-group>
            <template #tip>
              开启后 50% 促销种也会被淘汰，只留完全免费的
            </template>
          </FormField>

          <FormField label="外语电影需中字">
            <v-radio-group v-model="form.requireChineseSubtitle" inline hide-details density="comfortable">
              <v-radio label="否" value="0" />
              <v-radio label="是" value="1" />
            </v-radio-group>
            <template #tip>
              外语电影（TMDb 原始语言非中文）的种子标题或描述中未检测到中文字幕标识（CHS/CHT/中字等）时直接淘汰。中文电影自动跳过此规则
            </template>
          </FormField>

          <FormField>
            <v-text-field
              v-model="form.resolutionWhitelist"
              label="分辨率白名单"
              placeholder="如 2160p,1080p；留空表示不限"
              density="comfortable"
              variant="outlined"
            />
            <template #tip>
              <strong>硬性过滤</strong>：不在白名单内的分辨率直接淘汰。解析不出分辨率的种子在白名单非空时也会被淘汰
            </template>
          </FormField>

          <FormField label="规避 H&R 站点">
            <v-radio-group v-model="form.avoidHitAndRun" inline hide-details density="comfortable">
              <v-radio label="否" value="0" />
              <v-radio label="是" value="1" />
            </v-radio-group>
            <template #tip>
              开启后<strong>直接淘汰</strong>来自有 H&amp;R 考核站点的所有种子。H&amp;R 站点往往正是资源质量最好的站点，
              多数情况你要的其实是「同等条件下优先用没有考核的」——那个请把下方的「H&amp;R 规避」排序维度往前调，
              而不是打开这个开关。站点是否有 H&amp;R 在索引器管理页配置
            </template>
          </FormField>

          <FormField>
            <v-text-field
              v-model="form.sourceWhitelist"
              label="媒介来源白名单"
              placeholder="如 REMUX,BluRay,WEBDL；留空表示不限"
              density="comfortable"
              variant="outlined"
            />
            <template #tip>
              <strong>硬性过滤</strong>：不在白名单内的来源直接淘汰。与分辨率白名单同理，解析不出来源的种子在白名单非空时也会被淘汰。可用值为解析后的归一化形式：REMUX、BluRay、WEBDL、WEBRip、HDTV、BDRip、DVDRip
            </template>
          </FormField>

          <FormField>
            <v-text-field
              v-model="form.requiredTags"
              label="必需质量标签"
              placeholder="如 HDR10,Atmos；留空表示不限"
              density="comfortable"
              variant="outlined"
            />
            <template #tip>
              逗号分隔，种子须<strong>全部具备</strong>（AND 语义）才放行。整词匹配，配 HDR 不会命中 HDR10。可用值：HDR10、HDR10+、HDR、Dolby Vision、Atmos、TrueHD、DTS-HD、10bit、60fps、IMAX、H265、H264 等。要表达「任选其一」请改用下面的标题包含词
            </template>
          </FormField>

          <FormField>
            <v-text-field
              v-model="form.excludeTags"
              label="排除质量标签"
              placeholder="逗号分隔，命中任一即淘汰"
              density="comfortable"
              variant="outlined"
            />
          </FormField>

          <FormField>
            <v-text-field
              v-model="form.includeKeywords"
              label="标题包含词"
              placeholder="逗号分隔，命中其一即可；留空表示不限"
              density="comfortable"
              variant="outlined"
            />
          </FormField>

          <FormField>
            <v-text-field
              v-model="form.excludeKeywords"
              label="标题排除词"
              placeholder="逗号分隔，命中任一即淘汰"
              density="comfortable"
              variant="outlined"
            />
          </FormField>

          <div class="section-divider"><span>择优排序（从存活的候选里挑一个）</span></div>

          <FormField>
            <v-text-field
              v-model="form.resolutionPriority"
              label="分辨率优先级"
              placeholder="如 2160p,1080p,720p"
              density="comfortable"
              variant="outlined"
            />
            <template #tip>
              <strong>只影响排序</strong>，不做过滤——不在此列表内的分辨率只是排在最后，仍可能被下载。要过滤请用上面的白名单
            </template>
          </FormField>

          <FormField>
            <v-text-field
              v-model="form.sourcePriority"
              label="媒介来源优先级"
              placeholder="如 REMUX,BluRay,WEBDL,HDTV"
              density="comfortable"
              variant="outlined"
            />
            <template #tip>
              <strong>只影响排序</strong>，不做过滤。同分辨率下 Remux 与 HDTV 的观感差距远大于做种数差距，通常应把下方的「媒介来源优先级」维度排在「做种数」之前
            </template>
          </FormField>

          <FormField>
            <v-text-field
              v-model="form.releaseGroupPriority"
              label="发布组优先级"
              placeholder="如 CHDBits,FRDS,CMCT"
              density="comfortable"
              variant="outlined"
            />
            <template #tip>
              <strong>只影响排序</strong>：不在列表内的发布组只是排最后，仍可能被下载。要彻底排除某个发布组请用「种子黑名单」
            </template>
          </FormField>

          <FormField>
            <v-text-field
              v-model.number="form.preferredSize"
              label="偏好体积"
              type="number"
              min="0"
              max="999"
              density="comfortable"
              variant="outlined"
              class="field-num"
            />
            <template #tip>
              GB，0 表示体积不参与择优比较
            </template>
          </FormField>

          <FormField label="维度优先顺序">
            <div class="dimension-list">
              <div v-for="(dimension, index) in sortOrder" :key="dimension" class="dimension-row">
                <span class="dimension-index">{{ index + 1 }}</span>
                <span class="dimension-label">{{ labelOf(dimension) }}</span>
                <v-btn variant="text" size="small" :disabled="index === 0" @click="moveUp(index)">上移</v-btn>
                <v-btn variant="text" size="small" :disabled="index === sortOrder.length - 1" @click="moveDown(index)">下移</v-btn>
              </div>
            </div>
            <template #tip>
              排在前面的维度先比较。例如把「促销优先」放到「分辨率优先级」之前，就表示宁可要免费的 1080p，也不要收费的 4K
            </template>
          </FormField>

          <div class="form-actions">
            <v-btn color="primary" :loading="saving" @click="save">保存</v-btn>
            <v-btn variant="outlined" @click="load">重置</v-btn>
          </div>
        </v-form>
      </v-card-text>
    </v-card>
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import FormField from '@/components/FormField.vue'
import { usePtFilterConfig } from '@/composables/usePtFilterConfig'

const { loading, saving, formRef, form, rules, sortOrder, labelOf, moveUp, moveDown, load, save } =
  usePtFilterConfig()

// 表单规则是 { required, message, trigger } 对象格式（composable 返回），
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

/* 数字输入框限宽，避免「最低做种数」这类两三位数的框拉满整行 */
.field-num-lg {
  max-width: 200px;
}

.field-num {
  max-width: 160px;
}

.form-actions {
  display: flex;
  gap: 12px;
  margin-top: 16px;
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

  .field-num-lg,
  .field-num {
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
