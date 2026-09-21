<template>
  <div class="template-variable-chips">
    <div class="variables-title">可用变量（点击插入）</div>
    <v-tooltip
      v-for="v in visible"
      :key="v.name"
      location="top"
      :disabled="!v.label && !v.sample"
    >
      <template #activator="{ props: tip }">
        <v-chip
          v-bind="tip"
          class="variable-tag"
          size="small"
          @click="emit('insert', v.name)"
        >{{ v.name }}</v-chip>
      </template>
      <div v-if="v.label">{{ v.label }}</div>
      <div v-if="v.sample" class="variable-sample">样例：{{ v.sample }}</div>
    </v-tooltip>
    <v-btn
      v-if="others.length"
      variant="text"
      size="small"
      class="variables-toggle"
      @click="expanded = !expanded"
    >{{ expanded ? '收起' : `更多（${others.length}）` }}</v-btn>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import type { TemplateVariable } from '@/api/openlist/renameConfig'

/**
 * 重命名模板的变量清单，PC 与移动端共用。
 * 清单来自后端（由 MediaInfo 的渲染上下文生成），这里只负责展示：常用的先摆出来，
 * 其余收进「更多」——全摊开有近三十个，而日常只用到十来个。
 */
const props = defineProps<{ variables: TemplateVariable[] }>()
const emit = defineEmits<{ insert: [name: string] }>()

const expanded = ref(false)
const common = computed(() => props.variables.filter(v => v.common))
const others = computed(() => props.variables.filter(v => !v.common))
const visible = computed(() => (expanded.value ? [...common.value, ...others.value] : common.value))
</script>

<style scoped lang="scss">
.variables-title {
  font-size: var(--osr-fs-sm);
  color: var(--osr-text-secondary);
  margin-bottom: 8px;
}

.variable-tag {
  margin: 0 6px 6px 0;
  cursor: pointer;
}

.variables-toggle {
  margin-bottom: 6px;
}

.variable-sample {
  font-family: var(--osr-font-mono);
  opacity: 0.85;
}
</style>
