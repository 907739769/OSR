<template>
  <div class="rule-card-list">
    <div
      v-for="(row, index) in rules"
      :key="index"
      class="rule-card"
      :class="{ fallback: row.isFallback === '1' }"
    >
      <div class="rule-card-head">
        <v-text-field
          v-model="row.targetDir"
          :placeholder="row.isFallback === '1' ? '兜底目录' : '目录名'"
          density="compact"
          variant="outlined"
          hide-details
          class="target-input"
        />
        <v-chip v-if="row.isFallback === '1'" color="primary" size="small" variant="tonal" class="fallback-badge">兜底</v-chip>
      </div>

      <div class="rule-field">
        <label class="rule-field-label">类型（Genre）</label>
        <v-combobox
          :model-value="toArray(row.genreIds)"
          :items="genreOptions"
          item-title="label"
          item-value="value"
          :return-object="false"
          multiple chips closable-chips
          :disabled="row.isFallback === '1'"
          placeholder="不限"
          density="compact"
          variant="outlined"
          hide-details
          @update:model-value="(v: any[]) => { row.genreIds = toCsv(normalize(v)) }"
        />
      </div>

      <div class="rule-field">
        <label class="rule-field-label">原始语言</label>
        <v-combobox
          :model-value="toArray(row.originalLanguages)"
          :items="LANGUAGE_OPTIONS"
          item-title="label"
          item-value="value"
          :return-object="false"
          multiple chips closable-chips
          :disabled="row.isFallback === '1'"
          placeholder="不限"
          density="compact"
          variant="outlined"
          hide-details
          @update:model-value="(v: any[]) => { row.originalLanguages = toCsv(normalize(v)) }"
        />
      </div>

      <div class="rule-field">
        <label class="rule-field-label">国家/地区</label>
        <v-combobox
          :model-value="toArray(row.originCountries)"
          :items="COUNTRY_OPTIONS"
          item-title="label"
          item-value="value"
          :return-object="false"
          multiple chips closable-chips
          :disabled="row.isFallback === '1'"
          placeholder="不限"
          density="compact"
          variant="outlined"
          hide-details
          @update:model-value="(v: any[]) => { row.originCountries = toCsv(normalize(v)) }"
        />
      </div>

      <div class="rule-card-actions">
        <v-btn variant="text" size="small" icon="mdi-arrow-up" :disabled="row.isFallback === '1'" @click="$emit('move', mediaType, index, -1)" />
        <v-btn variant="text" size="small" icon="mdi-arrow-down" :disabled="row.isFallback === '1'" @click="$emit('move', mediaType, index, 1)" />
        <v-spacer />
        <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" :disabled="row.isFallback === '1'" @click="$emit('remove', mediaType, index)">删除</v-btn>
      </div>
    </div>

    <v-btn variant="outlined" block class="add-btn" prepend-icon="mdi-plus" @click="$emit('add', mediaType)">新增规则</v-btn>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { CategoryRule } from '@/api/openlist/renameConfig'
import { MOVIE_GENRE_OPTIONS, TV_GENRE_OPTIONS, LANGUAGE_OPTIONS, COUNTRY_OPTIONS } from '@/constants/categoryRuleOptions'

const props = defineProps<{
  rules: CategoryRule[]
  mediaType: string
}>()

defineEmits<{
  add: [mediaType: string]
  remove: [mediaType: string, index: number]
  move: [mediaType: string, index: number, direction: -1 | 1]
}>()

/** 电影和剧集的 TMDB genre 编号含义不同，按 mediaType 选对应的可选项列表 */
const genreOptions = computed(() => (props.mediaType === 'tv' ? TV_GENRE_OPTIONS : MOVIE_GENRE_OPTIONS))

/** 数据库存的是逗号分隔字符串，下拉多选组件需要数组，两边转换 */
const toArray = (value?: string) => (value ? value.split(',').map(s => s.trim()).filter(Boolean) : [])
const toCsv = (arr: string[]) => arr.join(',')

/**
 * v-combobox 允许自由创建条目时，model-value 里既可能是选项对象 { label, value }
 * 也可能是用户直接输入的字符串，统一转成字符串值数组再落到 CSV。
 */
const normalize = (arr: any[]): string[] =>
  (arr || []).map(v => (typeof v === 'string' ? v : v?.value ?? String(v)))
</script>

<style scoped lang="scss">
.rule-card-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.rule-card {
  background: var(--osr-surface);
  border-radius: var(--osr-radius-lg);
  padding: 12px;
  box-shadow: var(--osr-shadow-base);
  border: 2px solid transparent;

  &.fallback {
    border-color: var(--osr-primary-muted);
    background: var(--osr-primary-subtle);
  }
}

.rule-card-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;

  .target-input {
    flex: 1;
    min-width: 0;
  }

  .fallback-badge {
    flex-shrink: 0;
  }
}

.rule-field {
  margin-bottom: 10px;

  .rule-field-label {
    display: block;
    margin-bottom: 4px;
    font-size: 12px;
    color: var(--osr-text-secondary);
  }
}

.rule-card-actions {
  display: flex;
  align-items: center;
  margin-top: 4px;
  padding-top: 8px;
  border-top: 1px solid var(--osr-border-light);
}

.add-btn {
  margin-top: 4px;
}
</style>
