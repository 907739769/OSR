<template>
  <!-- 搜索补集确认 -->
  <v-dialog v-model="searchDialogOpen" max-width="480">
    <v-card title="搜索补集">
      <v-card-text>
        <v-text-field
          v-model="searchDialogKeyword"
          label="关键词"
          placeholder="搜索关键词，可编辑后再搜"
          class="mb-2"
        />
        <v-checkbox-btn v-model="searchManualSelect" label="手动选择结果" />
        <p class="field-hint">
          {{ searchManualSelect
            ? '搜完列出全部候选种子，由你挑一个推送下载。'
            : '搜完按过滤规则与优先级自动挑一个推送下载，不再询问。' }}
        </p>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="outlined" @click="searchDialogOpen = false">取消</v-btn>
        <v-btn color="primary" variant="flat" :loading="searchDialogLoading" @click="confirmSearch">搜索</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { usePtSubscriptionContext } from '@/composables/ptSubscriptionContext'

const {
  confirmSearch,
  searchDialogKeyword,
  searchDialogLoading,
  searchDialogOpen,
  searchManualSelect
} = usePtSubscriptionContext()
</script>

<style scoped lang="scss">
.field-hint {
  margin: 4px 0 0;
  font-size: 12px;
  color: var(--osr-text-secondary);
}
</style>
