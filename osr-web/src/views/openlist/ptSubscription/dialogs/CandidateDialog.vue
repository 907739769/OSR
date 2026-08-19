<template>
  <!-- 候选种子手动选择 -->
  <v-dialog v-model="candidateDialogOpen" max-width="900">
    <v-card title="选择候选种子">
      <v-card-text>
        <div v-if="candidates.length === 0" class="empty-tip">
          未搜索到匹配资源
        </div>
        <v-data-table
          v-else
          :items="candidates"
          :headers="candidateHeaders"
          height="420"
          fixed-header
          items-per-page="-1"
          hide-default-footer
          density="compact"
          class="modern-table"
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
import { ref } from 'vue'
import { usePtSubscriptionContext } from '@/composables/ptSubscriptionContext'

const {
  candidateDialogOpen,
  candidates,
  formatSize,
  pushSelectedCandidate,
  pushingSelected
} = usePtSubscriptionContext()

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

/** 候选种子表格中当前高亮的行 */
const selectedCandidate = ref<any>(null)
</script>

<style scoped lang="scss">
.empty-tip {
  text-align: center;
  padding: 40px;
  color: var(--osr-text-secondary);
}
</style>
