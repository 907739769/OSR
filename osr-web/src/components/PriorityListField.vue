<template>
  <div class="priority-field">
    <OrderedList
      :model-value="list"
      removable
      :empty-text="emptyText"
      @update:model-value="emitList"
    />
    <div class="priority-add">
      <v-select
        v-if="!allowCustom"
        :model-value="null"
        :items="remaining"
        :label="`添加${label}`"
        :disabled="!remaining.length"
        density="compact"
        variant="outlined"
        hide-details
        class="priority-add-input"
        @update:model-value="add"
      />
      <template v-else>
        <v-text-field
          v-model="draft"
          :label="`添加${label}`"
          density="compact"
          variant="outlined"
          hide-details
          class="priority-add-input"
          @keydown.enter.prevent="add(draft)"
        />
        <v-btn variant="outlined" :disabled="!draft.trim()" @click="add(draft)">添加</v-btn>
      </template>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 优先级列表：有序、可增删、可调整先后，值存成逗号分隔串。
 *
 * 分辨率与来源从固定的可选值里挑（后端全等比对，不许手打），
 * 发布组名是开放的，`allowCustom` 时改成输入框。
 */
import OrderedList from '@/components/OrderedList.vue'
import { splitCsv, joinCsv } from '@/composables/orderList'

const props = defineProps<{
  modelValue?: string | null
  /** 可选值；allowCustom 时不用 */
  options?: string[]
  allowCustom?: boolean
  /** 用在「添加 xxx」与空态文案里 */
  label: string
  emptyText?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: string] }>()

const list = computed(() => splitCsv(props.modelValue))
const remaining = computed(() =>
  (props.options || []).filter((o) => !list.value.some((v) => v.toLowerCase() === o.toLowerCase()))
)
const draft = ref('')

const emitList = (value: string[]) => emit('update:modelValue', joinCsv(value))

const add = (value: string | null) => {
  const v = (value || '').trim()
  // 逗号是分隔符，混进值里会被拆成两项
  if (!v || v.includes(',') || list.value.some((x) => x.toLowerCase() === v.toLowerCase())) {
    draft.value = ''
    return
  }
  emitList([...list.value, v])
  draft.value = ''
}
</script>

<style scoped>
.priority-field {
  width: 100%;
}

.priority-add {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 6px;
}

.priority-add-input {
  max-width: 260px;
}

@media (max-width: 768px) {
  .priority-add-input {
    max-width: none;
  }
}
</style>
