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
          </td>
          <td class="col-lang">
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
          </td>
          <td class="col-country">
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
          </td>
          <td class="col-actions">
            <v-tooltip text="上移" location="top">
              <template #activator="{ props: tip }">
                <v-btn v-bind="tip" variant="text" size="small" icon="arrow-up" :disabled="row.isFallback === '1'" @click="$emit('move', mediaType, index, -1)" />
              </template>
            </v-tooltip>
            <v-tooltip text="下移" location="top">
              <template #activator="{ props: tip }">
                <v-btn v-bind="tip" variant="text" size="small" icon="arrow-down" :disabled="row.isFallback === '1'" @click="$emit('move', mediaType, index, 1)" />
              </template>
            </v-tooltip>
            <v-tooltip text="删除" location="top">
              <template #activator="{ props: tip }">
                <v-btn v-bind="tip" variant="text" color="error" size="small" icon="trash-2" :disabled="row.isFallback === '1'" @click="$emit('remove', mediaType, index)" />
              </template>
            </v-tooltip>
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
    width: 120px;
    text-align: center;
    white-space: nowrap;
  }
}

.rule-table-actions {
  margin-top: 8px;
}
</style>
