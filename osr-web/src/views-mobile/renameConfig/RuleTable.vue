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
          :rules="targetDirRules"
          :maxlength="TARGET_DIR_MAX"
          density="compact"
          variant="outlined"
          hide-details="auto"
          class="target-input"
        />
        <v-chip v-if="row.isFallback === '1'" color="primary" size="small" variant="tonal" class="fallback-badge">兜底</v-chip>
      </div>

      <FormField label="类型（Genre）">
        <v-select
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
          @update:model-value="(v: string[]) => { row.genreIds = toCsv(v) }"
        />
      </FormField>

      <FormField label="原始语言">
        <v-select
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
          @update:model-value="(v: string[]) => { row.originalLanguages = toCsv(v) }"
        />
      </FormField>

      <FormField label="国家/地区">
        <v-select
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
          @update:model-value="(v: string[]) => { row.originCountries = toCsv(v) }"
        />
      </FormField>

      <div class="rule-card-actions">
        <v-btn variant="text" size="small" icon="arrow-up-to-line" aria-label="置顶" :disabled="row.isFallback === '1' || isFirst(index)" @click="$emit('move', mediaType, index, 'top')" />
        <v-btn variant="text" size="small" icon="arrow-up" aria-label="上移" :disabled="row.isFallback === '1' || isFirst(index)" @click="$emit('move', mediaType, index, -1)" />
        <v-btn variant="text" size="small" icon="arrow-down" aria-label="下移" :disabled="row.isFallback === '1' || isLastMovable(index)" @click="$emit('move', mediaType, index, 1)" />
        <v-btn variant="text" size="small" icon="arrow-down-to-line" aria-label="置底" :disabled="row.isFallback === '1' || isLastMovable(index)" @click="$emit('move', mediaType, index, 'bottom')" />
        <v-spacer />
        <v-btn variant="text" color="error" size="small" prepend-icon="trash-2" :disabled="row.isFallback === '1'" @click="$emit('remove', mediaType, index)">删除</v-btn>
      </div>
    </div>

    <v-btn variant="outlined" block class="add-btn" prepend-icon="plus" @click="$emit('add', mediaType)">新增规则</v-btn>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import FormField from '@/components/FormField.vue'
import type { CategoryRule } from '@/api/openlist/renameConfig'
import { targetDirRules, TARGET_DIR_MAX, type RuleMoveDirection } from '@/composables/useRenameConfig'
import { MOVIE_GENRE_OPTIONS, TV_GENRE_OPTIONS, LANGUAGE_OPTIONS, COUNTRY_OPTIONS } from '@/constants/categoryRuleOptions'

const props = defineProps<{
  rules: CategoryRule[]
  mediaType: string
}>()

defineEmits<{
  add: [mediaType: string]
  remove: [mediaType: string, index: number]
  move: [mediaType: string, index: number, direction: RuleMoveDirection]
}>()

/** 第一条不能再往上挪 */
const isFirst = (index: number) => index === 0
/** 兜底行之前的最后一条不能再往下挪（兜底行永远在最后） */
const isLastMovable = (index: number) => props.rules[index + 1]?.isFallback === '1' || index === props.rules.length - 1

/** 电影和剧集的 TMDB genre 编号含义不同，按 mediaType 选对应的可选项列表 */
const genreOptions = computed(() => (props.mediaType === 'tv' ? TV_GENRE_OPTIONS : MOVIE_GENRE_OPTIONS))

/**
 * 数据库存的是逗号分隔字符串，下拉多选组件需要数组，两边转换。
 *
 * 这三个下拉**必须是 v-select 而不是 v-combobox**：combobox 允许自由输入，用户手打一个
 * 「动画」或「cn-CN」会被原样存进 CSV，而 CategoryRule 是按 TMDb genre id / ISO 语言码
 * 全等比对的，这种值永远命不中——界面上不报错、不告警，只表现为「这条规则好像没生效」。
 * 库里已有的、不在选项表里的历史值 v-select 照样保留并原样显示，不会被吃掉。
 */
const toArray = (value?: string) => (value ? value.split(',').map(s => s.trim()).filter(Boolean) : [])
const toCsv = (arr: string[]) => arr.join(',')
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
