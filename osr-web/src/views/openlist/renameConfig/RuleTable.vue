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
        <tr v-for="(row, index) in rules" :key="index" :class="{ 'rule-row--fallback': row.isFallback === '1' }">
          <td class="col-target">
            <div class="target-cell">
              <v-text-field
                v-model="row.targetDir"
                :placeholder="row.isFallback === '1' ? '兜底目录' : '目录名'"
                :rules="targetDirRules"
                :maxlength="TARGET_DIR_MAX"
                density="compact"
                variant="outlined"
                hide-details="auto"
              />
              <v-chip v-if="row.isFallback === '1'" color="primary" size="small" variant="tonal" class="fallback-badge">兜底</v-chip>
            </div>
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
            <template v-if="row.isFallback !== '1'">
              <v-tooltip text="置顶" location="top">
                <template #activator="{ props: tip }">
                  <v-btn v-bind="tip" variant="text" size="small" icon="arrow-up-to-line" :disabled="isFirst(index)" @click="$emit('move', mediaType, index, 'top')" />
                </template>
              </v-tooltip>
              <v-tooltip text="上移" location="top">
                <template #activator="{ props: tip }">
                  <v-btn v-bind="tip" variant="text" size="small" icon="arrow-up" :disabled="isFirst(index)" @click="$emit('move', mediaType, index, -1)" />
                </template>
              </v-tooltip>
              <v-tooltip text="下移" location="top">
                <template #activator="{ props: tip }">
                  <v-btn v-bind="tip" variant="text" size="small" icon="arrow-down" :disabled="isLastMovable(index)" @click="$emit('move', mediaType, index, 1)" />
                </template>
              </v-tooltip>
              <v-tooltip text="置底（兜底规则之前）" location="top">
                <template #activator="{ props: tip }">
                  <v-btn v-bind="tip" variant="text" size="small" icon="arrow-down-to-line" :disabled="isLastMovable(index)" @click="$emit('move', mediaType, index, 'bottom')" />
                </template>
              </v-tooltip>
              <v-tooltip text="删除" location="top">
                <template #activator="{ props: tip }">
                  <v-btn v-bind="tip" variant="text" color="error" size="small" icon="trash-2" @click="$emit('remove', mediaType, index)" />
                </template>
              </v-tooltip>
            </template>
            <span v-else class="fallback-note">固定在最后</span>
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
    width: 180px;
    text-align: center;
    white-space: nowrap;
  }

  /* 兜底行与普通行长得一模一样的话，只能靠三个禁用的下拉去猜它特殊（移动端早有这层标记） */
  .rule-row--fallback {
    background: var(--osr-primary-subtle);
  }

  .target-cell {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .fallback-badge {
    flex-shrink: 0;
  }

  .fallback-note {
    font-size: var(--osr-fs-xs);
    color: var(--osr-text-secondary);
  }
}

.rule-table-actions {
  margin-top: 8px;
}
</style>
