<template>
  <!-- 候选种子手动选择 -->
  <v-dialog v-model="candidateDialogOpen" max-width="900">
    <v-card title="选择候选种子">
      <v-card-text>
        <div v-if="candidates.length === 0" class="empty-tip">
          未搜索到匹配资源
        </div>
        <template v-else>
          <!-- 只有一种取值的维度不给下拉：唯一的选项勾不勾结果都一样，只会挤占标题框的宽度 -->
          <div class="candidate-filters">
            <v-text-field
              v-model="filter.keyword"
              class="filter-keyword"
              density="compact"
              prepend-inner-icon="search"
              placeholder="标题关键词，空格分隔；- 开头排除，如 -HDR"
              hide-details
              clearable
            />
            <v-select
              v-if="facets.indexers.length > 1"
              v-model="filter.indexerIds"
              :items="facets.indexers"
              class="filter-select"
              label="站点"
              density="compact"
              multiple
              hide-details
              clearable
            >
              <template #selection="{ index }">
                <span v-if="index === 0" class="selection-summary">已选 {{ filter.indexerIds.length }}</span>
              </template>
            </v-select>
            <v-select
              v-if="facets.targets.length > 1"
              v-model="filter.targets"
              :items="facets.targets"
              class="filter-select"
              label="目标"
              density="compact"
              multiple
              hide-details
              clearable
            >
              <template #selection="{ index }">
                <span v-if="index === 0" class="selection-summary">已选 {{ filter.targets.length }}</span>
              </template>
            </v-select>
            <v-select
              v-if="facets.resolutions.length > 1"
              v-model="filter.resolutions"
              :items="facets.resolutions"
              class="filter-select"
              label="分辨率"
              density="compact"
              multiple
              hide-details
              clearable
            >
              <template #selection="{ index }">
                <span v-if="index === 0" class="selection-summary">已选 {{ filter.resolutions.length }}</span>
              </template>
            </v-select>
            <v-select
              v-if="facets.sources.length > 1"
              v-model="filter.sources"
              :items="facets.sources"
              class="filter-select"
              label="片源"
              density="compact"
              multiple
              hide-details
              clearable
            >
              <template #selection="{ index }">
                <span v-if="index === 0" class="selection-summary">已选 {{ filter.sources.length }}</span>
              </template>
            </v-select>
          </div>
          <div class="candidate-filter-bar">
            <v-checkbox-btn v-model="filter.freeOnly" label="仅免费" density="compact" />
            <v-checkbox-btn v-model="filter.seededOnly" label="仅有做种" density="compact" />
            <v-spacer />
            <span class="filter-count">显示 {{ filteredCandidates.length }} / {{ candidates.length }}</span>
            <v-btn v-if="filterCount" variant="text" size="small" @click="resetFilter">清空筛选</v-btn>
          </div>
          <v-data-table
            :items="filteredCandidates"
            :headers="candidateHeaders"
            height="380"
            fixed-header
            items-per-page="-1"
            hide-default-footer
            density="compact"
            class="modern-table"
            no-data-text="没有符合筛选条件的候选"
            :row-props="(row: any) => ({ class: selectedCandidate === row.item ? 'row-selected' : '', style: 'cursor:pointer', onClick: () => (selectedCandidate = row.item) })"
          >
            <template #item.index="{ index }">{{ index + 1 }}</template>
            <template #item.target="{ item }">
              <v-chip v-if="item.parsedEpisode && item.parsedEpisodeEnd > item.parsedEpisode" size="small" color="warning" variant="tonal">
                第{{ item.parsedEpisode }}-{{ item.parsedEpisodeEnd }}集
              </v-chip>
              <v-chip v-else-if="item.parsedEpisode" size="small" color="warning" variant="tonal">第{{ item.parsedEpisode }}集</v-chip>
              <v-chip v-else size="small" color="success" variant="tonal">整季</v-chip>
            </template>
            <template #item.indexerName="{ item }">
              <v-chip size="small" color="info" variant="tonal">{{ item.indexerName }}</v-chip>
            </template>
            <template #item.resolution="{ item }">{{ item.resolution || '-' }}</template>
            <template #item.source="{ item }">{{ item.source || '-' }}</template>
            <template #item.size="{ item }">{{ formatSize(item.size) }}</template>
            <template #item.seeders="{ item }">
              <v-chip :color="item.seeders > 0 ? 'success' : 'error'" size="small" variant="tonal">{{ item.seeders }}</v-chip>
            </template>
            <template #item.free="{ item }">
              <v-chip v-if="item.free" color="warning" size="small" variant="tonal">免费</v-chip>
              <span v-else>-</span>
            </template>
          </v-data-table>
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

// 「站点」与「片源」原先都叫「来源」：一列是哪个索引器、一列是 BluRay/WEB-DL，
// 而这张表就是给用户挑种子用的，两列同名等于没有表头
const candidateHeaders = [
  { title: '#', key: 'index', sortable: false, width: 48, align: 'center' as const },
  { title: '目标', key: 'target', sortable: false, width: 70, align: 'center' as const },
  { title: '站点', key: 'indexerName', sortable: false, width: 100 },
  { title: '标题', key: 'title', sortable: false, minWidth: '280' },
  { title: '分辨率', key: 'resolution', sortable: false, width: 80, align: 'center' as const },
  { title: '片源', key: 'source', sortable: false, width: 80, align: 'center' as const },
  { title: '体积', key: 'size', sortable: false, width: 100, align: 'end' as const },
  { title: '做种', key: 'seeders', sortable: false, width: 70, align: 'center' as const },
  { title: '免费', key: 'free', sortable: false, width: 60, align: 'center' as const }
]

/**
 * 候选种子表格中当前高亮的行。每次打开都清空：上一次搜索挑中的那条不在这次的列表里，
 * 留着的话「下载选中版本」按钮是亮的，点下去推的是一个用户已经看不见的种子。
 */
const selectedCandidate = ref<any>(null)
watch(candidateDialogOpen, (open) => {
  if (open) selectedCandidate.value = null
})
// 选中的那条被筛选藏起来时一并取消选中，理由同上：不能推一个屏幕上看不见的种子
watch(filteredCandidates, (list) => {
  if (selectedCandidate.value && !list.includes(selectedCandidate.value)) selectedCandidate.value = null
})
</script>

<style scoped lang="scss">
.empty-tip {
  text-align: center;
  padding: 40px;
  color: var(--osr-text-secondary);
}

.candidate-filters {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;

  .filter-keyword {
    flex: 2 1 260px;
  }

  .filter-select {
    flex: 1 1 120px;
    max-width: 160px;
  }
}

.selection-summary {
  font-size: 13px;
  white-space: nowrap;
}

.candidate-filter-bar {
  display: flex;
  align-items: center;
  gap: 4px;
  margin: 4px 0 8px;

  .filter-count {
    font-size: 12px;
    color: var(--osr-text-secondary);
  }
}
</style>
