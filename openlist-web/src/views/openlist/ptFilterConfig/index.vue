<template>
  <div class="page-container">
    <el-card v-loading="loading" class="table-card">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="140px" label-position="top" class="filter-form">
        <el-divider content-position="left">硬性过滤（不满足即淘汰）</el-divider>

        <el-form-item label="最低做种数" prop="minSeeders">
          <el-input-number v-model="form.minSeeders" :min="0" :style="{ width: '200px' }" />
          <span class="form-tip">做种数低于此值的种子直接淘汰</span>
        </el-form-item>

        <el-form-item label="体积下限">
          <el-input-number v-model="form.minSize" :min="0" :max="999" :style="{ width: '160px' }" />
          <span class="form-tip">GB，0 表示不限</span>
        </el-form-item>

        <el-form-item label="体积上限">
          <el-input-number v-model="form.maxSize" :min="0" :max="999" :style="{ width: '160px' }" />
          <span class="form-tip">GB，0 表示不限</span>
        </el-form-item>

        <el-form-item label="仅要免费种">
          <el-radio-group v-model="form.freeOnly">
            <el-radio value="0">否</el-radio>
            <el-radio value="1">是</el-radio>
          </el-radio-group>
          <span class="form-tip">开启后 50% 促销种也会被淘汰，只留完全免费的</span>
        </el-form-item>

        <el-form-item label="分辨率白名单">
          <el-input v-model="form.resolutionWhitelist" placeholder="如 2160p,1080p；留空表示不限" />
          <span class="form-tip">
            <strong>硬性过滤</strong>：不在白名单内的分辨率直接淘汰。解析不出分辨率的种子在白名单非空时也会被淘汰
          </span>
        </el-form-item>

        <el-form-item label="标题包含词">
          <el-input v-model="form.includeKeywords" placeholder="逗号分隔，命中其一即可；留空表示不限" />
        </el-form-item>

        <el-form-item label="标题排除词">
          <el-input v-model="form.excludeKeywords" placeholder="逗号分隔，命中任一即淘汰" />
        </el-form-item>

        <el-divider content-position="left">择优排序（从存活的候选里挑一个）</el-divider>

        <el-form-item label="分辨率优先级">
          <el-input v-model="form.resolutionPriority" placeholder="如 2160p,1080p,720p" />
          <span class="form-tip">
            <strong>只影响排序</strong>，不做过滤——不在此列表内的分辨率只是排在最后，仍可能被下载。要过滤请用上面的白名单
          </span>
        </el-form-item>

        <el-form-item label="偏好体积">
          <el-input-number v-model="form.preferredSize" :min="0" :max="999" :style="{ width: '160px' }" />
          <span class="form-tip">GB，0 表示体积不参与择优比较</span>
        </el-form-item>

        <el-form-item label="维度优先顺序">
          <div class="dimension-list">
            <div v-for="(dimension, index) in sortOrder" :key="dimension" class="dimension-row">
              <span class="dimension-index">{{ index + 1 }}</span>
              <span class="dimension-label">{{ labelOf(dimension) }}</span>
              <el-button link :disabled="index === 0" @click="moveUp(index)">上移</el-button>
              <el-button link :disabled="index === sortOrder.length - 1" @click="moveDown(index)">下移</el-button>
            </div>
          </div>
          <span class="form-tip">
            排在前面的维度先比较。例如把「促销优先」放到「分辨率优先级」之前，就表示宁可要免费的 1080p，也不要收费的 4K
          </span>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="save">保存</el-button>
          <el-button @click="load">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { usePtFilterConfig } from '@/composables/usePtFilterConfig'

const { loading, saving, formRef, form, rules, sortOrder, labelOf, moveUp, moveDown, load, save } =
  usePtFilterConfig()
</script>

<style scoped>
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
  background: var(--el-fill-color);
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

  .table-card :deep(.el-card__body) {
    padding: 12px;
  }

  .filter-form {
    width: 100%;
  }

  .filter-form :deep(.el-form-item) {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    margin-bottom: 14px;
  }

  .filter-form :deep(.el-form-item__label) {
    padding-bottom: 4px;
    height: auto;
    line-height: 1.4;
  }

  .filter-form :deep(.el-form-item__content) {
    flex-wrap: wrap;
    margin-left: 0 !important;
    width: 100%;
  }

  .filter-form :deep(.el-input),
  .filter-form :deep(.el-input-number),
  .filter-form :deep(.el-select) {
    width: 100% !important;
    max-width: 100%;
  }

  .filter-form :deep(.el-radio-group) {
    width: 100%;
    display: flex;
    gap: 12px;
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
