<template>
  <div class="rule-table-wrap">
    <v-table density="comfortable" class="rule-table">
      <thead>
        <tr>
          <th class="col-target">目标目录名</th>
          <th class="col-genre">类型（Genre）</th>
          <th class="col-lang">原始语言</th>
          <th class="col-country">国家/地区</th>
          <th class="col-actions">操作</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(row, index) in rules" :key="index">
          <td class="col-target">
            <v-text-field
              v-model="row.targetDir"
              :placeholder="row.isFallback === '1' ? '兜底目录' : '目录名'"
              density="compact"
              variant="outlined"
              hide-details
            />
          </td>
          <td class="col-genre">
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
          </td>
          <td class="col-lang">
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
          </td>
          <td class="col-country">
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
          </td>
          <td class="col-actions">
            <v-btn variant="text" size="small" :disabled="row.isFallback === '1'" @click="$emit('move', mediaType, index, -1)">上移</v-btn>
            <v-btn variant="text" size="small" :disabled="row.isFallback === '1'" @click="$emit('move', mediaType, index, 1)">下移</v-btn>
            <v-btn variant="text" color="error" size="small" :disabled="row.isFallback === '1'" @click="$emit('remove', mediaType, index)">删除</v-btn>
          </td>
        </tr>
      </tbody>
    </v-table>
    <div class="rule-table-actions">
      <v-btn variant="outlined" @click="$emit('add', mediaType)">+ 新增规则</v-btn>
    </div>
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
.rule-table-wrap {
  width: 100%;
}

.rule-table {
  width: 100%;

  th {
    font-size: 12px;
    color: var(--osr-text-secondary);
    white-space: nowrap;
  }

  td {
    padding-top: 8px;
    padding-bottom: 8px;
    vertical-align: middle;
  }

  .col-target {
    min-width: 140px;
  }

  .col-genre,
  .col-lang,
  .col-country {
    min-width: 200px;
  }

  .col-actions {
    width: 160px;
    text-align: center;
    white-space: nowrap;
  }
}

.rule-table-actions {
  margin-top: 8px;
}
</style>
