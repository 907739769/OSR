<template>
  <!-- 候选种子手动选择：PC 用表格，移动端改成可点选的卡片列表 -->
  <v-dialog v-model="candidateDialogOpen" width="92%">
    <v-card title="选择候选种子">
      <v-card-text>
        <v-empty-state v-if="candidates.length === 0" icon="inbox" title="未搜索到匹配资源" />
        <template v-else>
          <!-- 窄屏上四个下拉常驻会把候选挤到一屏以下，收进「筛选」面板；关键词框常驻，它是最常用的那个 -->
          <div class="filter-head">
            <v-text-field
              v-model="filter.keyword"
              density="compact"
              prepend-inner-icon="search"
              placeholder="标题关键词，- 开头排除"
              hide-details
              clearable
            />
            <v-btn
              variant="outlined"
              size="small"
              prepend-icon="funnel"
              :color="filterCount ? 'primary' : undefined"
              @click="filterPanelOpen = !filterPanelOpen"
            >
              筛选{{ filterCount ? ` ${filterCount}` : '' }}
            </v-btn>
          </div>
          <div v-if="filterPanelOpen" class="filter-panel">
            <v-select
              v-if="facets.indexers.length > 1"
              v-model="filter.indexerIds"
              :items="facets.indexers"
              label="站点"
              density="compact"
              multiple
              chips
              closable-chips
              hide-details
            />
            <v-select
              v-if="facets.targets.length > 1"
              v-model="filter.targets"
              :items="facets.targets"
              label="目标"
              density="compact"
              multiple
              chips
              closable-chips
              hide-details
            />
            <v-select
              v-if="facets.resolutions.length > 1"
              v-model="filter.resolutions"
              :items="facets.resolutions"
              label="分辨率"
              density="compact"
              multiple
              chips
              closable-chips
              hide-details
            />
            <v-select
              v-if="facets.sources.length > 1"
              v-model="filter.sources"
              :items="facets.sources"
              label="片源"
              density="compact"
              multiple
              chips
              closable-chips
              hide-details
            />
            <div class="filter-toggles">
              <v-checkbox-btn v-model="filter.freeOnly" label="仅免费" density="compact" />
              <v-checkbox-btn v-model="filter.seededOnly" label="仅有做种" density="compact" />
            </div>
          </div>
          <div class="filter-summary">
            <span>显示 {{ filteredCandidates.length }} / {{ candidates.length }}</span>
            <v-btn v-if="filterCount" variant="text" size="small" @click="resetFilter">清空筛选</v-btn>
          </div>
          <div v-if="filteredCandidates.length === 0" class="filter-empty">没有符合筛选条件的候选</div>
          <div v-else class="candidate-list">
            <div
              v-for="(cand, idx) in filteredCandidates"
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
        </template>
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
import { useCandidateFilter } from '@/composables/useCandidateFilter'

const {
  candidateDialogOpen,
  candidates,
  formatSize,
  pushSelectedCandidate,
  pushingSelected
} = usePtSubscriptionContext()

const { filter, facets, filteredCandidates, filterCount, resetFilter } = useCandidateFilter(candidates)
const filterPanelOpen = ref(false)

/** 候选种子弹窗里当前选中的那一条。每次打开都清空——上次挑的那条与这次的候选列表无关 */
const selectedCandidate = ref<any>(null)
watch(candidateDialogOpen, (open) => {
  if (open) selectedCandidate.value = null
})
// 选中的那条被筛选藏起来时一并取消选中：不能推一个屏幕上看不见的种子
watch(filteredCandidates, (list) => {
  if (selectedCandidate.value && !list.includes(selectedCandidate.value)) selectedCandidate.value = null
})
</script>

<style scoped lang="scss">
/* ---- 候选筛选 ---- */
.filter-head {
  display: flex;
  align-items: center;
  gap: 8px;
}
.filter-panel {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-top: 8px;
}
.filter-toggles {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.filter-summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  min-height: 32px;
  margin: 4px 0;
  font-size: 12px;
  color: var(--osr-text-secondary);
}
.filter-empty {
  text-align: center;
  padding: 32px 0;
  font-size: 13px;
  color: var(--osr-text-secondary);
}

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
