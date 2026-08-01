<template>
  <div class="mobile-page">
    <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
      <v-form ref="queryRef">
        <v-text-field
          v-model="queryParams.title"
          label="影视名称"
          placeholder="请输入影视名称"
          clearable
          density="compact"
          variant="outlined"
          @keyup.enter="handleQuery"
        />
        <v-select
          v-model="queryParams.reason"
          label="原因"
          :items="[{ title: '本地文件丢失', value: 'local_missing' }, { title: '网盘源丢失', value: 'source_missing' }]"
          placeholder="全部原因"
          clearable
          density="compact"
          variant="outlined"
        />
        <v-select
          v-model="queryParams.status"
          label="状态"
          :items="[{ title: '待处理', value: '0' }, { title: '已清理', value: '1' }, { title: '已忽略', value: '2' }]"
          placeholder="全部状态"
          clearable
          density="compact"
          variant="outlined"
        />
      </v-form>
    </MobileSearchPanel>

    <div class="scan-bar">
      <v-btn color="primary" size="small" prepend-icon="mdi-refresh" :loading="scanning" @click="handleScanNow">
        立即扫描
      </v-btn>
    </div>

    <!-- Batch Actions -->
    <div class="batch-bar" v-if="selectedIds.length > 0">
      <span class="selected-count">已选 {{ selectedIds.length }} 项</span>
      <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleBatchClean()">
        清理
      </v-btn>
      <v-btn variant="text" color="warning" size="small" prepend-icon="mdi-alert-outline" @click="handleBatchIgnore()">
        忽略
      </v-btn>
      <v-btn variant="text" size="small" @click="clearSelection">
        取消
      </v-btn>
    </div>

    <!-- Record List -->
    <div class="task-list">
      <v-progress-linear v-if="loading" indeterminate color="primary" />
      <v-card
        v-for="item in recordList"
        :key="item.id"
        class="task-card"
        :class="{ selected: selectedIds.includes(item.id) }"
        @click="handleCardClick($event, item.id)"
      >
        <div class="card-checkbox">
          <v-checkbox
            :model-value="selectedIds.includes(item.id)"
            density="compact"
            hide-details
            @click.stop="toggleSelect(item.id)"
          />
        </div>
        <div class="card-content">
          <div class="card-top">
            <div class="card-title-row">
              <v-icon class="card-title-icon" icon="mdi-movie-search-outline" size="18" />
              <span class="card-title">
                {{ item.title || '未知' }}<span v-if="item.year" class="orphan-year">（{{ item.year }}）</span>
              </span>
            </div>
            <StatusChip v-if="item.status === '0'" type="info" text="待处理" />
            <StatusChip v-else-if="item.status === '1'" type="success" text="已清理" />
            <StatusChip v-else type="info" text="已忽略" />
          </div>
          <div class="card-path card-path--link" @click.stop="showFullText(`${item.newPath}/${item.newName}`, '重命名后路径')">
            <v-icon class="card-path-icon" icon="mdi-map-marker-outline" size="14" />
            <span class="card-path-text">{{ item.newPath }}/{{ item.newName }}</span>
          </div>
          <div class="card-detail">
            <div class="detail-row">
              <span class="label">原因</span>
              <span class="value">
                <StatusChip v-if="item.reason === 'local_missing'" type="warning" text="本地文件丢失" />
                <StatusChip v-else type="error" text="网盘源丢失" />
              </span>
            </div>
          </div>
          <div class="card-time">
            <v-icon icon="mdi-clock-outline" size="14" />
            {{ item.foundTime }}
          </div>
          <div class="card-actions" v-if="item.status === '0'" @click.stop>
            <v-btn variant="text" color="error" size="small" prepend-icon="mdi-delete-outline" @click="handleCleanOne(item)">
              清理
            </v-btn>
            <v-btn variant="text" color="warning" size="small" prepend-icon="mdi-alert-outline" @click="handleIgnoreOne(item)">
              忽略
            </v-btn>
          </div>
        </div>
      </v-card>

      <v-empty-state v-if="!loading && !recordList.length" icon="mdi-inbox-outline" title="暂无数据" />
    </div>

    <!-- 分页 -->
    <MobilePager
      v-model:page-size="queryParams.pageSize"
      :page-num="queryParams.pageNum"
      :total="total"
      :total-pages="totalPages"
      @prev="prevPage"
      @next="nextPage"
      @size-change="handleSizeChange"
    />

    <!-- 全文查看 -->
    <FullTextDialog ref="fullTextRef" />
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import FullTextDialog from '@/components/mobile/FullTextDialog.vue'
import StatusChip from '@/components/StatusChip.vue'
import { useRenameOrphanList } from '@/composables/useRenameOrphanList'

const searchCollapsed = ref(true)

const {
  recordList, loading, total, queryParams, totalPages,
  getList, prevPage, nextPage, handleSizeChange,
  queryRef, handleQuery, resetQuery,
  selectedIds, toggleSelect, handleCardClick, clearSelection,
  handleCleanOne, handleBatchClean,
  scanning, handleScanNow,
  handleIgnoreOne, handleBatchIgnore
} = useRenameOrphanList()

const fullTextRef = ref<InstanceType<typeof FullTextDialog>>()
const showFullText = (content: string, title: string) => fullTextRef.value?.show(content, title)

getList()
</script>

<style scoped lang="scss">
.scan-bar {
  display: flex;
  justify-content: flex-end;
}

.orphan-year {
  color: var(--osr-text-secondary);
  font-size: 12px;
}
</style>
