<template>
  <div class="osr-form-field" :class="{ 'osr-form-field--inline': inline }">
    <label v-if="label" class="osr-form-field__label">{{ label }}</label>
    <div class="osr-form-field__control">
      <slot />
    </div>
    <div v-if="$slots.tip || tip" class="osr-form-field__tip">
      <slot name="tip">{{ tip }}</slot>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 表单项容器：外置 label + 控件 + 下方说明文字。
 *
 * 用于两种场景：
 * 1. 控件本身没有内置 label —— DirectoryTreeSelect、v-radio-group、v-switch，
 *    以及「输入框 + 右侧按钮」这类组合控件。
 * 2. 控件有自己的 label，但需要在下方补一段说明 —— 此时不传 label，只传 tip。
 *
 * v-text-field / v-select / v-textarea 若不需要说明文字，请直接用它们自己的
 * `label` prop，不要套这个组件：否则同一个弹窗里会同时出现浮动 label 和贴顶
 * label 两种标签位置（改造前 ptIndexer 弹窗就是这样）。
 *
 * 取代此前散落在 10 个页面里各自复制的 .form-item / .form-label / .field-label
 * —— 那套结构是 Element Plus el-form-item 的手写复刻。
 */
withDefaults(
  defineProps<{
    label?: string
    /** 控件下方的补充说明，如取值范围、行为解释 */
    tip?: string
    /** 行内布局：label 在左、控件在右，用于 v-switch 这类开关 */
    inline?: boolean
  }>(),
  { label: '', tip: '', inline: false }
)
</script>

<style scoped lang="scss">
.osr-form-field {
  margin-bottom: 16px;

  &:last-child {
    margin-bottom: 0;
  }

  .osr-form-field__label {
    display: block;
    margin-bottom: 6px;
    font-size: 13px;
    color: var(--osr-text-secondary);
  }

  /* 组合控件（输入框 + 按钮）横排；单控件时 flex 不影响布局 */
  .osr-form-field__control {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    min-width: 0;

    > :first-child {
      flex: 1;
      min-width: 0;
    }
  }

  .osr-form-field__tip {
    margin-top: 4px;
    font-size: 12px;
    line-height: 1.5;
    color: var(--osr-text-secondary);
  }

  /* 行内：label 在左，控件靠右；说明文字仍占满整行排在下方 */
  &.osr-form-field--inline {
    display: grid;
    grid-template-columns: auto 1fr;
    align-items: center;
    column-gap: 12px;

    .osr-form-field__label {
      margin-bottom: 0;
    }

    .osr-form-field__control {
      justify-content: flex-end;

      > :first-child {
        flex: none;
      }
    }

    .osr-form-field__tip {
      grid-column: 1 / -1;
    }
  }
}
</style>
