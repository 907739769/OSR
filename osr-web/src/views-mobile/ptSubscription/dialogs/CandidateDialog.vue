<template>
  <!-- 候选种子手动选择：PC 用表格，移动端改成可点选的卡片列表 -->
  <v-dialog v-model="candidateDialogOpen" width="92%">
    <v-card title="选择候选种子">
      <v-card-text>
        <v-empty-state v-if="candidates.length === 0" icon="inbox" title="未搜索到匹配资源" />
        <div v-else class="candidate-list">
          <div
            v-for="(cand, idx) in candidates"
            :key="idx"
            class="candidate-card"
            :class="{ selected: selectedCandidate === cand }"
            @click="selectedCandidate = cand"
          >
            <div class="candidate-title">{{ cand.title }}</div>
            <div class="candidate-tags">
              <v-chip v-if="cand.parsedEpisode && cand.parsedEpisodeEnd > cand.parsedEpisode" size="x-small" color="warning" variant="tonal">
                第{{ cand.parsedEpisode }}-{{ cand.parsedEpisodeEnd }}集
              </v-chip>
              <v-chip v-else-if="cand.parsedEpisode" size="x-small" color="warning" variant="tonal">第{{ cand.parsedEpisode }}集</v-chip>
              <v-chip v-else size="x-small" color="success" variant="tonal">整季</v-chip>
              <v-chip size="x-small" color="info" variant="tonal">{{ cand.indexerName }}</v-chip>
              <v-chip v-if="cand.free" size="x-small" color="warning" variant="tonal">免费</v-chip>
              <v-chip size="x-small" :color="cand.seeders > 0 ? 'success' : 'error'" variant="tonal">
                {{ cand.seeders }} 做种
              </v-chip>
            </div>
            <div class="candidate-meta">
              {{ cand.resolution || '-' }} · {{ cand.source || '-' }} · {{ formatSize(cand.size) }}
            </div>
          </div>
        </div>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn variant="outlined" @click="candidateDialogOpen = false">取消</v-btn>
        <v-btn
          color="primary"
          variant="flat"
          :loading="pushingSelected"
          :disabled="!selectedCandidate"
          @click="pushSelectedCandidate(selectedCandidate)"
        >
          下载选中版本
        </v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { usePtSubscriptionContext } from '@/composables/ptSubscriptionContext'

const {
  candidateDialogOpen,
  candidates,
  formatSize,
  pushSelectedCandidate,
  pushingSelected
} = usePtSubscriptionContext()

/** 候选种子弹窗里当前选中的那一条。每次打开都清空——上次挑的那条与这次的候选列表无关 */
const selectedCandidate = ref<any>(null)
watch(candidateDialogOpen, (open) => {
  if (open) selectedCandidate.value = null
})
</script>

<style scoped lang="scss">
/* ---- 候选种子选择 ---- */
.candidate-list {
  display: flex;
  flex-direction: column;
  gap: 8px;
  max-height: 50vh;
  overflow-y: auto;
}
.candidate-card {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 10px;
  border-radius: var(--osr-radius-sm);
  border: 2px solid transparent;
  background: var(--osr-bg-page);
  cursor: pointer;

  &.selected {
    border-color: var(--osr-primary-accent);
    background: var(--osr-primary-subtle);
  }

  .candidate-title {
    font-size: 13px;
    font-weight: 600;
    color: var(--osr-text-primary);
    line-height: 1.4;
    word-break: break-all;
  }

  .candidate-tags {
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
  }

  .candidate-meta {
    font-size: 11px;
    color: var(--osr-text-secondary);
  }
}
</style>
