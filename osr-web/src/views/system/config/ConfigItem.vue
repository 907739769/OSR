<template>
  <div
    class="config-item"
    :class="{ 'config-item--editing': editing }"
  >
    <!-- Header row: name + key + actions -->
    <div class="config-item__header">
      <div class="config-item__title">
        <span class="config-item__name">{{ config.configName }}</span>
        <code class="config-item__key" @click="emit('copy', config.configKey)" title="点击复制键名">{{ config.configKey }}</code>
      </div>
      <div class="config-item__actions">
        <!-- Inline switch for boolean configs -->
        <v-switch
          v-if="meta.type === 'switch' && !editing"
          :model-value="config.configValue === '1'"
          :loading="switchSaving"
          color="primary"
          density="compact"
          hide-details
          @update:model-value="(val: any) => emit('toggle', config, val)"
        />
        <!-- 明确的编辑按钮（PC + 移动端均清晰可见） -->
        <v-btn
          v-else-if="!editing"
          color="primary"
          variant="outlined"
          size="small"
          prepend-icon="square-pen"
          @click="emit('edit', config)"
        >
          编辑
        </v-btn>
        <v-chip v-else size="small" color="warning" variant="tonal">编辑中</v-chip>
      </div>
    </div>

    <!-- Hint -->
    <p v-if="meta.hint" class="config-item__hint">{{ meta.hint }}</p>

    <!-- Display value (non-switch, non-editing) -->
    <div
      v-if="!editing && meta.type !== 'switch'"
      class="config-item__value"
    >
      <span class="value-text" :class="{ 'value-text--empty': !config.configValue }">{{ displayValue(config) }}</span>
      <v-tooltip v-if="isSensitive(config.configKey) && config.configValue" :text="config.configValue" location="top" open-delay="300">
        <template #activator="{ props: tooltipProps }">
          <v-icon v-bind="tooltipProps" icon="zoom-in" class="value-expand" size="14" />
        </template>
      </v-tooltip>
    </div>

    <!-- Edit Mode -->
    <template v-if="editing">
      <div class="edit-body">
        <!-- number -->
        <v-text-field
          v-if="meta.type === 'number'"
          v-model.number="numberValue"
          type="number"
          :min="meta.min"
          :max="meta.max"
          step="1"
          density="compact"
          variant="outlined"
          hide-details
          class="edit-number"
        />
        <span v-if="meta.type === 'number' && meta.unit" class="edit-unit">{{ meta.unit }}</span>

        <!-- select -->
        <v-combobox
          v-else-if="meta.type === 'select'"
          v-model="form.configValue"
          :items="meta.options"
          item-title="label"
          item-value="value"
          density="compact"
          variant="outlined"
          hide-details
          class="edit-select"
          placeholder="请选择"
        />

        <!-- password -->
        <v-text-field
          v-else-if="meta.type === 'password'"
          v-model="form.configValue"
          type="password"
          placeholder="请输入参数值"
          density="compact"
          variant="outlined"
          hide-details
          class="edit-input"
          :error="!!error"
        />

        <!-- textarea -->
        <v-textarea
          v-else-if="meta.type === 'textarea'"
          v-model="form.configValue"
          rows="3"
          placeholder="请输入参数值"
          density="compact"
          variant="outlined"
          hide-details
          class="edit-input"
          :error="!!error"
        />

        <!-- text (default) -->
        <v-text-field
          v-else
          v-model="form.configValue"
          placeholder="请输入参数值"
          density="compact"
          variant="outlined"
          hide-details
          class="edit-input"
          :error="!!error"
        />
      </div>
      <p v-if="error" class="edit-error text-caption text-error">{{ error }}</p>
      <div class="edit-actions">
        <v-btn variant="outlined" @click="emit('cancel')">取消</v-btn>
        <v-btn color="primary" variant="flat" :loading="saving" @click="emit('save', config)">保存</v-btn>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { SysConfig } from '@/types/system'
import { metaOf, isSensitive } from './configMeta'

/**
 * 参数设置里的一条配置。展示态 / 开关 / 编辑态（number、select、password、textarea、text）
 * 都在这里，页面只保留「当前编辑的是哪一条」以及保存动作。
 *
 * 纯展示组件：不自己发请求、不自己改数据，编辑值通过 v-model:form / v-model:number 双向绑，
 * 其余动作一律 emit 给页面 —— 同一时刻只可能有一条在编辑，那个状态属于页面。
 */
const props = defineProps<{
  config: SysConfig
  editing: boolean
  saving: boolean
  switchSaving: boolean
  error: string
}>()

const form = defineModel<Partial<SysConfig>>('form', { required: true })
const numberValue = defineModel<number>('number', { required: true })

const emit = defineEmits<{
  edit: [config: SysConfig]
  cancel: []
  save: [config: SysConfig]
  toggle: [config: SysConfig, value: boolean]
  copy: [text: string]
}>()

const meta = computed(() => metaOf(props.config))

/** 展示态的取值：下拉显中文标签、数字带单位、敏感值脱敏 */
const displayValue = (config: SysConfig): string => {
  if (!config.configValue) return '未配置'
  // 下拉枚举：显示对应中文标签
  const meta = metaOf(config)
  if (meta.type === 'select' && meta.options) {
    const hit = meta.options.find(o => o.value === config.configValue)
    if (hit) return hit.label
  }
  // 数字：附带单位
  if (meta.type === 'number' && meta.unit) {
    return `${config.configValue} ${meta.unit}`
  }
  // 敏感值脱敏
  if (isSensitive(config.configKey)) {
    const v = config.configValue
    if (v.length <= 6) return v
    return v.slice(0, 4) + '•'.repeat(Math.min(v.length - 4, 12))
  }
  return config.configValue
}
</script>

<style scoped lang="scss">
/* ============================================
    Config Item
    ============================================ */
.config-item {
  background: var(--osr-surface);
  border-radius: var(--osr-radius-lg);
  border: 1px solid var(--osr-border-light);
  padding: 14px 16px;
  transition: all var(--osr-transition-base);
  display: flex;
  flex-direction: column;
  /* grid item 的默认 min-width 是 auto，即"不会窄于内容的 min-content 宽度"。
     配置值/说明里有 GENERAL,SUBSCRIPTION_HIT,DOWNLOAD_COMPLETE,... 这种不含空格的
     长串时，卡片会被顶宽、把右侧的编辑按钮推出屏幕（移动端尤其明显）。 */
  min-width: 0;

  &:hover:not(.config-item--editing) {
    border-color: var(--osr-primary-muted);
    box-shadow: var(--osr-shadow-md);
  }

  &--editing {
    border-color: rgb(var(--v-theme-warning));
    box-shadow: 0 0 0 2px rgba(var(--v-theme-warning), 0.2);
    grid-column: 1 / -1;
  }

  .config-item__header {
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: 8px;

    .config-item__title {
      display: flex;
      flex-direction: column;
      gap: 4px;
      min-width: 0;
      flex: 1;

      .config-item__name {
        font-size: 14px;
        font-weight: 600;
        color: var(--osr-text-primary);
        line-height: 1.3;
      }

      .config-item__key {
        font-family: var(--osr-font-mono);
        font-size: 11px;
        color: var(--osr-text-placeholder);
        background: var(--osr-bg-page);
        padding: 1px 6px;
        border-radius: 5px;
        cursor: pointer;
        align-self: flex-start;
        max-width: 100%;
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
        transition: all var(--osr-transition-fast);

        &:hover {
          color: var(--osr-primary);
          background: var(--osr-primary-subtle);
        }
      }
    }

    .config-item__actions {
      display: flex;
      align-items: center;
      gap: 6px;
      flex-shrink: 0;
      padding-top: 2px;
    }
  }

  .config-item__hint {
    margin: 8px 0 0;
    font-size: 12px;
    line-height: 1.5;
    color: var(--osr-text-secondary);
    /* 说明文字里常有逗号分隔的枚举/URL，整体是一个不含空格的长 token，
       不允许其内部断行的话会把整张卡片顶宽（见 .config-item 的 min-width 注释） */
    overflow-wrap: anywhere;
  }

  .config-item__value {
    display: flex;
    align-items: center;
    gap: 8px;
    margin-top: 10px;
    padding: 8px 10px;
    background: var(--osr-bg-page);
    border-radius: var(--osr-radius-base);

    .value-text {
      flex: 1;
      font-family: var(--osr-font-mono);
      font-size: 13px;
      color: var(--osr-text-regular);
      line-height: 1.6;
      word-break: break-all;
      min-width: 0;

      &--empty {
        color: var(--osr-text-placeholder);
        font-style: italic;
        font-family: inherit;
      }
    }

    .value-expand {
      color: var(--osr-text-placeholder);
      cursor: pointer;
      flex-shrink: 0;
      padding: 2px;
      border-radius: 4px;
      transition: all var(--osr-transition-fast);

      &:hover {
        color: var(--osr-primary);
        background: var(--osr-primary-subtle);
      }
    }
  }
}

/* Edit mode */
.edit-body {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 12px;

  .edit-number { width: 180px; }
  .edit-select { width: 100%; min-width: 0; }
  /* min-width: 0 —— flex item 默认不会窄于 min-content，v-text-field 内部还套着
     label/append 等结构，窄屏下不给这条会把编辑区连同下方按钮一起顶出屏幕 */
  .edit-input { flex: 1; min-width: 0; }
  .edit-unit {
    font-size: 13px;
    color: var(--osr-text-secondary);
    flex-shrink: 0;
  }
}

.edit-error {
  display: block;
  margin-top: 6px;
}

.edit-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
}
</style>
