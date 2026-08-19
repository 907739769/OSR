<template>
  <!-- PC 列表页的搜索区。16 个页面原先各写一遍这层外壳（v-card.search-card >
       v-form > .search-fields > 字段 + .search-actions），移动端反而早就有
       MobileSearchPanel 了。展开状态由 useSearchPanel 提供并按页记住。 -->
  <v-card v-if="visible" class="search-card">
    <v-form ref="formRef" @submit.prevent="emit('search')">
      <div class="search-fields">
        <slot />
        <div class="search-actions">
          <v-btn color="primary" prepend-icon="mdi-magnify" :loading="loading" @click="emit('search')">搜索</v-btn>
          <v-btn variant="outlined" prepend-icon="mdi-refresh" @click="emit('reset')">重置</v-btn>
        </div>
      </div>
    </v-form>
  </v-card>
</template>

<script setup lang="ts">
import { ref } from 'vue'

defineProps<{
  /** 展开状态，来自 useSearchPanel（默认收起，按页记住） */
  visible: boolean
  loading?: boolean
}>()

const emit = defineEmits<{ search: []; reset: [] }>()

const formRef = ref<any>()

// 页面把 ref="queryRef" 挂在本组件上，composable 里的 queryRef.value?.resetValidation?.()
// 要能一路传到里面那个 v-form —— 不透出这一个方法的话，重置时表单的校验态清不掉
defineExpose({
  resetValidation: () => formRef.value?.resetValidation?.(),
  reset: () => formRef.value?.reset?.(),
  validate: () => formRef.value?.validate?.()
})
</script>
