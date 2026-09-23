<template>
  <div class="page-container">
    <PageHeader
      icon="history"
      title="PT 下载记录"
      desc="每次推送下载器的记录与结果，失败项可重试或拉黑"
    />

    <!-- 搜索 -->
    <SearchPanel ref="queryRef" :visible="showSearch" @search="handleQuery" @reset="resetQuery">
      <v-text-field
        v-model="queryParams.title"
        label="标题"
        placeholder="请输入种子标题"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        @keyup.enter="handleQuery"
      />
      <v-select
        v-model="queryParams.state"
        :items="DOWNLOAD_STATE_OPTIONS"
        label="状态"
        placeholder="全部状态"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="status-select"
      />
      <v-select
        v-model="queryParams.failReasonCode"
        :items="FAIL_REASON_OPTIONS"
        label="失败原因"
        placeholder="全部"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="status-select"
      />
      <v-select
        v-model="queryParams.hrState"
        :items="HR_STATE_OPTIONS"
        label="H&R 保种"
        placeholder="全部"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="status-select"
      />
      <v-select
        v-model="queryParams.indexerId"
        :items="indexerOptions"
        label="来源索引器"
        placeholder="全部"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="status-select"
      />
      <v-select
        v-model="queryParams.downloaderId"
        :items="downloaderOptions"
        label="下载器"
        placeholder="全部"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="status-select"
      />
      <!-- 日期区间落在哪一列：从统计仪表盘趋势图点进来时按那条线自己的日期筛 -->
      <v-select
        v-model="queryParams.dateField"
        :items="DATE_FIELD_OPTIONS"
        label="日期按"
        placeholder="推送时间"
        clearable
        density="compact"
        variant="outlined"
        hide-details
        class="status-select"
      />
      <div class="date-range-fields">
        <v-text-field
          v-model="dateStart"
          :label="`${dateFieldLabel}·开始`"
          type="date"
          density="compact"
          variant="outlined"
          hide-details
          class="date-field"
        />
        <span class="date-range-sep">-</span>
        <v-text-field
          v-model="dateEnd"
          :label="`${dateFieldLabel}·结束`"
          type="date"
          density="compact"
          variant="outlined"
          hide-details
          class="date-field"
        />
      </div>
      <!-- 重试或补搜成功时是新建一条记录，失败那条原样留着；打开后只看还没着落的失败 -->
      <v-switch
        v-model="queryParams.hideSuperseded"
        label="隐藏已被接替的失败"
        color="primary"
        density="compact"
        inset
        hide-details
        class="hide-superseded-switch"
      />
    </SearchPanel>

    <!-- 列表 -->
    <v-card class="table-card">
      <div class="action-bar">
        <div class="action-left">
          <RecordStatusBar v-model="queryParams.state" :options="DOWNLOAD_STATE_OPTIONS" :stats="stats" />
          <!-- 从订阅页跳过来时带的订阅筛选：搜索区没有这个控件，不摆出来的话用户看不见也关不掉 -->
          <v-chip
            v-if="subFilterLabel"
            size="small"
            color="primary"
            variant="tonal"
            closable
            class="sub-filter-chip"
            @click:close="clearSubFilter"
          >
            订阅：{{ subFilterLabel }}
          </v-chip>
        </div>
        <div class="action-right">
          <v-btn variant="text" size="small" class="selection-mode-btn" @click="toggleSelectionMode">
            {{ selectionMode ? '退出批量操作' : '批量操作' }}
          </v-btn>
          <v-btn variant="text" size="small" prepend-icon="trash-2" class="cleanup-btn" @click="openCleanup">
            清理旧记录
          </v-btn>
          <v-btn variant="text" size="small" prepend-icon="funnel" @click="showSearch = !showSearch">
            {{ showSearch ? '隐藏搜索' : '显示搜索' }}
          </v-btn>
        </div>
      </div>

      <div class="batch-toolbar" v-if="selectionMode">
        已选 {{ selectedIds.length }} 项
        <!-- 重试只对还没被接替的失败记录成立，按钮上直接标出生效条数，免得点完才发现大半被跳过 -->
        <v-btn
          variant="text"
          color="primary"
          size="small"
          class="batch-retry-btn"
          :disabled="!retryableSelectedIds.length"
          @click="handleBatchRetry"
        >
          批量重试{{ retryableSelectedIds.length ? `（${retryableSelectedIds.length} 条失败）` : '' }}
        </v-btn>
        <v-btn variant="text" color="warning" size="small" class="batch-blacklist-guid-btn" :disabled="!selectedIds.length" @click="handleBatchBlacklistGuid">批量拉黑种子</v-btn>
        <v-btn variant="text" color="error" size="small" class="batch-blacklist-group-btn" :disabled="!selectedIds.length" @click="handleBatchBlacklistReleaseGroup">批量拉黑发布组</v-btn>
        <v-btn variant="text" size="small" class="batch-select-all-btn" @click="toggleSelectAllPage(!isAllPageSelected)">
          {{ isAllPageSelected ? '取消全选' : '全选' }}
        </v-btn>
        <v-btn variant="text" size="small" class="batch-cancel-btn" @click="toggleSelectionMode">取消</v-btn>
      </div>

      <!-- 两个分支都要挂 gridRef：每页条数按网格实际列数取整到整行，骨架屏阶段就得量得到 -->
      <div class="card-grid" ref="gridRef" v-if="loading && taskList.length === 0">
        <SkeletonCardGrid :count="skeletonCount" variant="record" :rows="3" :actions="3" />
      </div>
      <div class="card-grid" ref="gridRef" v-else>
        <v-progress-linear v-if="loading" indeterminate color="primary" />
        <div
          v-for="item in taskList"
          :key="item.id"
          class="item-card item-card--compact"
          :class="{
            'item-card--failed': item.state === 'FAILED' && !item.supersededById,
            'item-card--selectable': selectionMode
          }"
          @click="selectionMode && handleCardClick($event, item.id)"
        >
          <v-checkbox
            v-if="selectionMode"
            class="item-card-checkbox card-checkbox"
            :model-value="selectedIds.includes(item.id)"
            density="compact"
            hide-details
            @click.stop="toggleRecordSelect(item)"
          />
          <div class="card-header card-header--top">
            <span class="card-title card-title--clamp2" :title="item.title">{{ item.title }}</span>
            <StatusChip :type="stateTagType(item.state)" :text="stateLabel(item.state)" :pulse="item.state === 'DOWNLOADING'" />
          </div>
          <div class="record-sub">
            <router-link
              v-if="item.subTitle && subscriptionPath"
              :to="{ path: subscriptionPath, query: { id: item.subId } }"
              class="record-sub-link"
            >
              {{ item.subTitle }}
            </router-link>
            <span v-else>{{ item.subTitle || '订阅已删除' }}</span>
            <span v-if="item.episodeLabel" class="record-episode">· {{ item.episodeLabel }}</span>
          </div>
          <div v-if="hasProgress(item)" class="record-progress">
            <v-progress-linear
              :model-value="progressPercent(item)"
              :color="item.state === 'COMPLETED' ? 'success' : 'primary'"
              :class="{ 'osr-progress--active': item.state === 'DOWNLOADING' }"
              height="6"
              rounded
            />
            <span class="record-progress-text">{{ progressPercent(item) }}%</span>
          </div>
          <!-- 「已推送」是稳态标签，推送后十分钟与十小时长得一样，后者多半是下载器没接住 -->
          <div v-if="stalePushedHint(item)" class="record-stale">
            <v-icon icon="clock" size="14" />
            {{ stalePushedHint(item) }}
          </div>
          <div class="card-row">
            <span class="label">来源索引器</span>
            <span class="value">{{ item.indexerName || '-' }}</span>
          </div>
          <div class="card-row">
            <span class="label">下载器</span>
            <span class="value">{{ item.downloaderName || '-' }}</span>
          </div>
          <div class="card-row">
            <span class="label" :title="SEEDERS_HINT">体积 / 推送时做种</span>
            <span class="value">{{ formatFileSize(item.size) }} / {{ item.seeders ?? '-' }}</span>
          </div>
          <div class="card-row">
            <span class="label">推送时间</span>
            <span class="value">{{ item.pushedTime || '-' }}</span>
          </div>
          <div class="card-row" v-if="item.state === 'COMPLETED'">
            <span class="label">完成时间</span>
            <span class="value">{{ item.completedTime || '-' }}</span>
          </div>
          <div class="card-row" v-if="item.torrentHash">
            <span class="label">种子 hash</span>
            <span class="value hash-value">
              <span class="hash-text" :title="item.torrentHash">{{ item.torrentHash.slice(0, 12) }}…</span>
              <v-btn
                variant="text"
                size="x-small"
                icon="copy"
                class="hash-copy-btn"
                title="复制种子 hash"
                @click.stop="copyTorrentHash(item)"
              />
            </span>
          </div>
          <div class="card-row" v-if="item.hrState">
            <span class="label">H&amp;R 保种</span>
            <span class="value hr-value">
              <StatusChip :type="hrTagType(item.hrState)" :text="hrStateLabel(item.hrState)" />
              <span class="hr-progress">{{ hrProgress(item) }}</span>
            </span>
          </div>
          <div class="record-fail" :class="{ 'record-fail--superseded': item.supersededById }" v-if="item.state === 'FAILED'">
            <v-icon icon="circle-alert" size="16" />
            <StatusChip v-if="item.failReasonCode" :type="failReasonTagType(item.failReasonCode)" :text="failReasonCodeLabel(item.failReasonCode)" />
            <span>{{ item.failReason || '未知原因' }}</span>
          </div>
          <!-- 失败之后同一集已经有了新的推送：该看的是后面那条，这条不再给重试 -->
          <div class="record-superseded" v-if="item.state === 'FAILED' && item.supersededById">
            <v-icon icon="circle-check" size="14" />
            已由后续推送 #{{ item.supersededById }} 接替
          </div>
          <div class="card-footer">
            <v-btn
              v-if="canRetry(item)"
              variant="text"
              color="primary"
              size="small"
              :loading="retryingIds.has(item.id)"
              @click="handleRetry(item)"
            >
              立即重试
            </v-btn>
            <v-btn
              variant="text"
              color="warning"
              size="small"
              class="blacklist-guid-btn"
              :disabled="item.guidBlacklisted"
              @click="handleBlacklistGuid(item)"
            >
              {{ item.guidBlacklisted ? '种子已拉黑' : '拉黑该种子' }}
            </v-btn>
            <!-- 标题里解析不出发布组时后端也拉黑不了，按钮不给 -->
            <v-btn
              v-if="item.releaseGroup"
              variant="text"
              color="error"
              size="small"
              class="blacklist-group-btn"
              :disabled="item.releaseGroupBlacklisted"
              @click="handleBlacklistReleaseGroup(item)"
            >
              {{ item.releaseGroupBlacklisted ? `${item.releaseGroup} 已拉黑` : `拉黑发布组 ${item.releaseGroup}` }}
            </v-btn>
          </div>
        </div>
        <v-empty-state v-if="!loading && taskList.length === 0" icon="inbox" title="暂无下载记录" />
      </div>

      <div class="pagination-wrapper">
        <span class="total-text">共 {{ total }} 条</span>
        <v-select
          :model-value="queryParams.pageSize"
          :items="pageSizeOptions"
          density="compact"
          variant="outlined"
          hide-details
          class="page-size-select"
          @update:model-value="setPageSize"
        />
        <v-pagination
          v-model="queryParams.pageNum"
          :length="Math.ceil(total / queryParams.pageSize) || 1"
          density="comfortable"
          @update:model-value="getList"
        />
      </div>
    </v-card>

    <PtBlacklistDialog
      v-model="blacklistDialog.visible"
      v-model:reason="blacklistDialog.reason"
      :title="blacklistDialog.title"
      :message="blacklistDialog.message"
      :submitting="blacklistDialog.submitting"
      @submit="submitBlacklist"
    />
    <PtDownloadRecordCleanupDialog
      v-model="cleanupDialog.visible"
      v-model:days="cleanupDialog.days"
      :day-options="cleanupDayOptions"
      :count="cleanupDialog.count"
      :loading="cleanupDialog.loading"
      :submitting="cleanupDialog.submitting"
      @submit="submitCleanup"
    />
  </div>
</template>

<script setup lang="ts">
import PageHeader from '@/components/PageHeader.vue'
import StatusChip from '@/components/StatusChip.vue'
import RecordStatusBar from '@/components/RecordStatusBar.vue'
import PtBlacklistDialog from '@/components/dialogs/PtBlacklistDialog.vue'
import PtDownloadRecordCleanupDialog from '@/components/dialogs/PtDownloadRecordCleanupDialog.vue'
import { computed, watch } from 'vue'
import { usePtDownloadRecord } from '@/composables/usePtDownloadRecord'
import {
  DOWNLOAD_STATE_OPTIONS, FAIL_REASON_OPTIONS, HR_STATE_OPTIONS, DATE_FIELD_OPTIONS, SEEDERS_HINT,
  stateLabel, stateTagType, failReasonCodeLabel, failReasonTagType, hrStateLabel, hrTagType,
  hrProgress, progressPercent, hasProgress, canRetry, stalePushedHint
} from '@/composables/ptDownloadRecordLabels'
import { formatFileSize } from '@/composables/useRecordList'
import { useGridPageSize } from '@/composables/useGridPageSize'
import { useSearchPanel } from '@/composables/useSearchPanel'
import SearchPanel from '@/components/SearchPanel.vue'
import SkeletonCardGrid from '@/components/skeleton/SkeletonCardGrid.vue'
import { getRoutePathForComponent } from '@/router'

const { showSearch } = useSearchPanel()

// 订阅页的路由 path 按组件反查，不写死：菜单 path 历史上有 /openlist 与 /openliststrm 两种前缀
const subscriptionPath = getRoutePathForComponent('openlist/ptSubscription/index')

// 首次加载交给 useGridPageSize（autoLoad: false）：每页条数要按网格实际列数取整到整行，
// 列数得挂载后才量得准，先按兜底值发一次再改口是白跑一趟请求
const {
  taskList, loading, total, queryParams, stats, getList, handleQuery, resetQuery, queryRef,
  dateStart, dateEnd, indexerOptions, downloaderOptions,
  subFilterLabel, clearSubFilter, routeFilterTick,
  retryingIds, handleRetry,
  selectionMode, toggleSelectionMode, selectedIds, toggleRecordSelect, handleCardClick,
  isAllPageSelected, toggleSelectAllPage,
  retryableSelectedIds, handleBatchRetry,
  handleBatchBlacklistGuid, handleBatchBlacklistReleaseGroup,
  handleBlacklistGuid, handleBlacklistReleaseGroup, blacklistDialog, submitBlacklist,
  copyTorrentHash,
  cleanupDialog, cleanupDayOptions, openCleanup, submitCleanup
} = usePtDownloadRecord({ autoLoad: false })

// 每页条数按网格实际列数取整到整行，窗口宽度变了跟着重算
const { gridRef, columns, pageSizeOptions, setPageSize } = useGridPageSize((size) => {
  queryParams.pageSize = size
  queryParams.pageNum = 1
  getList()
})

// 骨架屏铺两行（封顶 12 张，宽屏 8 列时两行就是 16 张，远超一屏）：列数直接用 useGridPageSize 量出来的，
// 不在这里按窗口宽度再复刻一遍卡片宽度与间距
const skeletonCount = computed(() => Math.max(3, Math.min(12, columns.value * 2)))

const dateFieldLabel = computed(() =>
  DATE_FIELD_OPTIONS.find(o => o.value === queryParams.dateField)?.title ?? '推送时间'
)

// 从统计仪表盘带筛选跳进来：那些条件都在搜索区里，搜索区收着的话用户看不见、也关不掉
watch(routeFilterTick, (tick) => {
  if (tick > 0) showSearch.value = true
}, { immediate: true })
</script>

<style scoped lang="scss">
.item-card-checkbox {
  position: absolute;
  top: 4px;
  left: 4px;
  z-index: 1;
}

/* 按钮上带着发布组名，三个按钮一行放不下时换行，不要把卡片撑宽 */
.card-footer {
  flex-wrap: wrap;
}

.hide-superseded-switch {
  flex: none;
}

/* 已推送迟迟不开始 */
.record-stale {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  font-size: 12px;
  line-height: 1.4;
  color: var(--osr-warning);
}

/* 所属订阅 */
.record-sub {
  font-size: 12px;
  color: var(--osr-text-secondary);

  .record-episode {
    margin-left: 2px;
  }
}

.record-sub-link {
  color: var(--osr-primary);
  text-decoration: none;

  &:hover {
    text-decoration: underline;
  }
}

/* 进度条右侧带百分比：光看一根条读不出是 40% 还是 60% */
.record-progress {
  display: flex;
  align-items: center;
  gap: 8px;
}

.record-progress-text {
  min-width: 36px;
  text-align: right;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.hash-value {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 2px;
}

.hash-text {
  font-family: var(--osr-font-mono);
  font-size: 12px;
}

/* 保种状态徽章与进度文字同行，窄屏时进度换到下一行而不是把徽章挤变形 */
.hr-value {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.hr-progress {
  font-size: 12px;
  color: var(--osr-text-secondary);
}

/* 失败原因块 */
.record-fail {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: var(--osr-radius-sm);
  background: rgba(var(--v-theme-error), 0.1);
  color: rgb(var(--v-theme-error));
  font-size: 12px;
  font-weight: 500;
  line-height: 1.5;
}

/* 已被接替的失败记录退成次要信息，不再用红底抢眼 */
.record-fail--superseded {
  background: rgba(var(--v-theme-on-surface), 0.05);
  color: var(--osr-text-secondary);
}

.record-superseded {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: rgb(var(--v-theme-success));
}

@media (max-width: 768px) {
  .item-card-checkbox {
    left: 0;
    top: 0;
  }
}
</style>
