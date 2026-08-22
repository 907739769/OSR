<template>
  <div class="page-container">
    <PageHeader
      icon="square-arrow-up"
      title="PT 洗版规则"
      desc="已入库的集在质量未达目标时，自动搜索并下载更好的版本"
    />

    <v-card :loading="loading" class="table-card">
      <v-card-text>
        <v-alert type="warning" variant="tonal" density="comfortable" class="notice">
          <strong>洗版不会删除旧版本。</strong>
          OSR 从不删除种子和文件，新版本下载完成后新旧两个版本会同时存在，
          媒体库里会出现同一集的两个版本，需要你自行清理旧的。
          自动清理会在后续版本提供，届时会先检查旧种子的 H&amp;R 是否已达标再动手。
        </v-alert>

        <v-form ref="formRef" class="filter-form">
          <div class="section-divider"><span>总开关</span></div>

          <FormField label="启用洗版">
            <v-radio-group v-model="form.enabled" inline hide-details density="comfortable">
              <v-radio label="否" value="0" />
              <v-radio label="是" value="1" />
            </v-radio-group>
            <template #tip>
              默认关闭。开启前请先在下方确认目标质量——没有目标质量的话，每一集都会永远搜下去，
              把索引器配额烧干
            </template>
          </FormField>

          <div class="section-divider"><span>目标质量（达到即停止洗版）</span></div>

          <v-alert
            v-if="form.enabled === '1' && !hasTarget()"
            type="error"
            variant="tonal"
            density="compact"
            class="notice"
          >
            已开启洗版但没有配置任何目标质量，不会有任何集被判定为需要升级。请至少填写一项。
          </v-alert>

          <FormField>
            <v-text-field
              v-model="form.targetResolution"
              label="目标分辨率"
              placeholder="如 2160p；留空表示不约束"
              density="comfortable"
              variant="outlined"
            />
            <template #tip>
              按「分辨率优先级」中的名次比较，因此目标填 1080p 时，已经是 2160p 的集同样算达标
            </template>
          </FormField>

          <FormField>
            <v-text-field
              v-model="form.targetSources"
              label="目标媒介来源"
              placeholder="如 REMUX,BluRay；留空表示不约束"
              density="comfortable"
              variant="outlined"
            />
            <template #tip>
              逗号分隔，<strong>命中其一</strong>即满足该项
            </template>
          </FormField>

          <FormField>
            <v-text-field
              v-model="form.targetTags"
              label="目标质量标签"
              placeholder="如 HDR10,Atmos；留空表示不约束"
              density="comfortable"
              variant="outlined"
            />
            <template #tip>
              逗号分隔，须<strong>全部具备</strong>才算达标。三项目标之间是「与」的关系，填了的项都要满足
            </template>
          </FormField>

          <div class="section-divider"><span>怎么算「更好」</span></div>

          <FormField label="维度优先顺序">
            <div class="dimension-list">
              <div v-for="(dimension, index) in dimensionOrder" :key="dimension" class="dimension-row">
                <span class="dimension-index">{{ index + 1 }}</span>
                <span class="dimension-label">{{ labelOf(dimension) }}</span>
                <v-btn variant="text" size="small" :disabled="index === 0" @click="moveUp(index)">上移</v-btn>
                <v-btn variant="text" size="small" :disabled="index === dimensionOrder.length - 1" @click="moveDown(index)">下移</v-btn>
              </div>
            </div>
            <template #tip>
              按此顺序逐维度比较，第一个名次不同的维度说了算；全部并列则<strong>不换</strong>。
              各维度内部谁比谁好，沿用「PT 过滤规则」页里的分辨率/来源/发布组优先级，此处不重复配置。
              <br />
              这里刻意没有做种数、体积、促销——那些不是画质，把它们放进升级判定会导致同一集被反复替换。
            </template>
          </FormField>

          <div class="section-divider"><span>节流</span></div>

          <FormField>
            <v-text-field
              v-model.number="form.maxConcurrent"
              label="同时在途洗版数"
              type="number"
              min="1"
              max="20"
              density="comfortable"
              variant="outlined"
              class="field-num"
            />
            <template #tip>
              独立于补缺集的名额。缺集是刚需、洗版是锦上添花，这个值不宜设大，否则新剧的更新会被堵在门外
            </template>
          </FormField>

          <FormField>
            <v-text-field
              v-model.number="form.scanIntervalHours"
              label="扫描周期"
              type="number"
              min="1"
              max="168"
              density="comfortable"
              variant="outlined"
              class="field-num"
              suffix="小时"
            />
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
import { usePtUpgradeConfig } from '@/composables/usePtUpgradeConfig'

const {
  loading, saving, formRef, form, dimensionOrder,
  labelOf, hasTarget, moveUp, moveDown, load, save
} = usePtUpgradeConfig()
</script>

<style scoped>
.notice {
  margin-bottom: 16px;
}

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

@media (max-width: 768px) {
  .page-container {
    padding: 0;
  }

  .filter-form {
    width: 100%;
  }

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
