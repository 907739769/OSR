<template>
  <v-select
    :model-value="selected"
    :items="items"
    :label="label"
    :placeholder="placeholder"
    multiple
    chips
    closable-chips
    clearable
    density="comfortable"
    variant="outlined"
    :hint="unknownHint"
    :persistent-hint="!!unknownHint"
    @update:model-value="onUpdate"
  />
</template>

<script setup lang="ts">
/**
 * 多选下拉，值存成逗号分隔串（后端配置的形态）。
 *
 * 用于分辨率 / 来源 / 质量标签这类**后端按全等比对**的字段：原先是自由输入的文本框，
 * 手打的 `WEB-DL`、`4K`、`HDR 10` 永远命不中，而且不报错。刻意用 `v-select` 而不是
 * `v-combobox`（后者允许自由输入，等于没改）。
 *
 * 库里已有的、不在可选值里的历史值照样保留并显示，同时在下方提示它命不中——
 * 不能悄悄吃掉，也不能让它继续看起来像是生效的。
 */
import { splitCsv, joinCsv } from '@/composables/orderList'

const props = defineProps<{
  modelValue?: string | null
  options: string[]
  label?: string
  placeholder?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const selected = computed(() => splitCsv(props.modelValue))

const isKnown = (value: string) => props.options.some((o) => o.toLowerCase() === value.toLowerCase())

/** 可选值 ∪ 已选中的历史值，后者不加进来的话 v-select 会把它显示成空白 chip */
const items = computed(() => [...props.options, ...selected.value.filter((v) => !isKnown(v))])

const unknownHint = computed(() => {
  const unknown = selected.value.filter((v) => !isKnown(v))
  return unknown.length ? `${unknown.join('、')} 不是解析器会产出的值，永远命不中，建议移除` : ''
})

const onUpdate = (value: string[] | null) => emit('update:modelValue', joinCsv(value || []))
</script>
