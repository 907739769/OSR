<template>
  <v-navigation-drawer
    :model-value="modelValue"
    temporary
    location="right"
    width="480"
    class="record-detail-drawer"
    @update:model-value="emit('update:modelValue', $event)"
  >
    <div class="record-detail-header">
      <span class="record-detail-title">{{ title }}</span>
      <v-btn icon="x" variant="text" size="small" aria-label="关闭" @click="emit('update:modelValue', false)" />
    </div>
    <v-divider />
    <div class="record-detail-body">
      <slot name="top" />
      <div v-for="field in visibleFields" :key="field.label" class="record-detail-field">
        <div class="record-detail-label">{{ field.label }}</div>
        <div class="record-detail-value" :class="{ 'osr-mono': field.mono, 'record-detail-value--error': field.error }">
          <a v-if="field.href" :href="field.href" target="_blank" rel="noopener noreferrer">{{ field.value }}</a>
          <template v-else>{{ field.value }}</template>
          <v-btn
            v-if="field.copyable"
            icon="copy"
            variant="text"
            size="x-small"
            class="record-detail-copy"
            :aria-label="`复制${field.label}`"
            @click="copy(field)"
          />
        </div>
      </div>
    </div>
  </v-navigation-drawer>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { message } from '@/composables/useMessage'

/**
 * 记录详情抽屉（PC）。表格格子里路径必然截断，只能靠悬停 title 一截一截看，也没法复制；
 * 这里把一条记录的全部字段完整摊开，路径类字段带复制按钮。
 *
 * 移动端不需要这一层：卡片本来就逐行展示全部字段，点任一行就弹全文（FullTextDialog）。
 */
export interface RecordDetailField {
  label: string
  value: string | number | null | undefined
  /** 路径、任务 ID 这类机器产物用等宽字体 */
  mono?: boolean
  copyable?: boolean
  /** 失败原因这类需要引起注意的值 */
  error?: boolean
  href?: string
}

const props = defineProps<{
  modelValue: boolean
  title: string
  fields: RecordDetailField[]
}>()

const emit = defineEmits<{ 'update:modelValue': [value: boolean] }>()

// 空值字段整行不显示：一屏「-」只会把有内容的字段挤下去
const visibleFields = computed(() =>
  props.fields.filter(f => f.value !== null && f.value !== undefined && f.value !== ''))

const copy = async (field: RecordDetailField) => {
  try {
    await navigator.clipboard.writeText(String(field.value))
    message.success(`已复制${field.label}`)
  } catch {
    // 局域网 http:// 部署时 clipboard API 不可用（只在安全上下文里开放），明说而不是静默失败
    message.warning('当前环境不支持自动复制，请手动选中复制')
  }
}
</script>

<style scoped lang="scss">
.record-detail-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
}

.record-detail-title {
  font-size: var(--osr-fs-md);
  font-weight: 600;
  color: var(--osr-text-primary);
}

.record-detail-body {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 16px;
}

.record-detail-label {
  font-size: 12px;
  color: var(--osr-text-secondary);
  margin-bottom: 2px;
}

.record-detail-value {
  font-size: 13px;
  color: var(--osr-text-primary);
  word-break: break-all;
  line-height: 1.6;
}

.record-detail-value--error {
  color: var(--osr-error);
}

.record-detail-copy {
  margin-left: 2px;
  vertical-align: middle;
}
</style>
