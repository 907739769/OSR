<template>
  <MobileListPage
    :loading="loading"
    :empty="!loading && taskList.length === 0"
    empty-icon="inbox"
    empty-title="暂无下载记录"
  >
    <template #head>
      <!-- 搜索 -->
      <MobileSearchPanel v-model:collapsed="searchCollapsed" :loading="loading" @search="handleQuery" @reset="resetQuery">
        <v-form ref="queryRef">
          <v-text-field
            v-model="queryParams.title"
            label="标题"
            placeholder="请输入种子标题"
            clearable
            density="comfortable"
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
            density="comfortable"
            variant="outlined"
            hide-details
          />
          <v-select
            v-model="queryParams.failReasonCode"
            :items="FAIL_REASON_OPTIONS"
            label="失败原因"
            placeholder="全部"
            clearable
            density="comfortable"
            variant="outlined"
            hide-details
          />
          <v-select
            v-model="queryParams.hrState"
            :items="HR_STATE_OPTIONS"
            label="H&R 保种"
            placeholder="全部"
            clearable
            density="comfortable"
            variant="outlined"
            hide-details
          />
          <v-select
            v-model="queryParams.indexerId"
            :items="indexerOptions"
            label="来源索引器"
            placeholder="全部"
            clearable
            density="comfortable"
            variant="outlined"
            hide-details
          />
          <v-select
            v-model="queryParams.downloaderId"
            :items="downloaderOptions"
            label="下载器"
            placeholder="全部"
            clearable
            density="comfortable"
            variant="outlined"
            hide-details
          />
          <div class="date-range-fields">
            <v-text-field
              v-model="dateStart"
              label="推送开始"
              type="date"
              density="compact"
              variant="outlined"
              hide-details
              class="date-field"
            />
            <span class="date-range-sep">-</span>
            <v-text-field
              v-model="dateEnd"
              label="推送结束"
              type="date"
              density="compact"
              variant="outlined"
              hide-details
              class="date-field"
            />
          </div>
        </v-form>
      </MobileSearchPanel>

      <div v-if="stats" class="record-toolbar">
        <RecordStatusBar v-model="queryParams.state" :options="DOWNLOAD_STATE_OPTIONS" :stats="stats" />
      </div>

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

      <!-- 批量选择 -->
      <div class="list-toolbar">
        <v-btn variant="text" size="small" prepend-icon="trash-2" class="cleanup-btn" @click="openCleanup">清理旧记录</v-btn>
        <v-btn variant="text" size="small" class="batch-toggle-btn" @click="toggleSelectionMode">
          {{ selectionMode ? '退出批量操作' : '批量操作' }}
        </v-btn>
      </div>

      <MobileBatchBar
        :visible="selectionMode"
        :count="selectedIds.length"
        :all-selected="isAllPageSelected"
        @toggle-all="toggleSelectAllPage"
        @cancel="toggleSelectionMode"
      >
        <!-- 重试只对还没被接替的失败记录成立，按钮上直接标出生效条数 -->
        <v-btn
          variant="text"
          color="primary"
          size="small"
          class="batch-retry-btn"
          :disabled="!retryableSelectedIds.length"
          @click="handleBatchRetry"
        >
          批量重试{{ retryableSelectedIds.length ? `（${retryableSelectedIds.length}）` : '' }}
        </v-btn>
        <v-btn variant="text" color="warning" size="small" class="batch-blacklist-guid-btn" :disabled="!selectedIds.length" @click="handleBatchBlacklistGuid">批量拉黑种子</v-btn>
        <v-btn variant="text" color="error" size="small" class="batch-blacklist-group-btn" :disabled="!selectedIds.length" @click="handleBatchBlacklistReleaseGroup">批量拉黑发布组</v-btn>
      </MobileBatchBar>

      <!-- 列表 -->
    </template>

    <v-progress-linear v-if="loading" indeterminate color="primary" class="list-loading" />
    <v-card
      v-for="item in taskList"
      :key="item.id"
      class="task-card"
      :class="{ selected: selectionMode && selectedIds.includes(item.id) }"
      @click="selectionMode && handleCardClick($event, item.id)"
    >
      <div class="card-checkbox" v-if="selectionMode">
        <v-checkbox-btn
          :model-value="selectedIds.includes(item.id)"
          density="compact"
          @click.stop="toggleRecordSelect(item)"
        />
      </div>
      <div class="card-content">
        <div class="card-top">
          <span class="card-title">{{ item.title }}</span>
          <StatusChip :type="stateTagType(item.state)" :text="stateLabel(item.state)" :pulse="item.state === 'DOWNLOADING'" />
        </div>
        <div class="card-sub">
          <router-link
            v-if="item.subTitle && subscriptionPath"
            :to="{ path: subscriptionPath, query: { id: item.subId } }"
            class="card-sub-link"
            @click.stop
          >
            {{ item.subTitle }}
          </router-link>
          <span v-else>{{ item.subTitle || '订阅已删除' }}</span>
          <span v-if="item.episodeLabel">· {{ item.episodeLabel }}</span>
        </div>
        <div v-if="hasProgress(item)" class="card-progress">
          <v-progress-linear
            :model-value="progressPercent(item)"
            :color="item.state === 'COMPLETED' ? 'success' : 'primary'"
            :class="{ 'osr-progress--active': item.state === 'DOWNLOADING' }"
            height="6"
            rounded
          />
          <span class="card-progress-text">{{ progressPercent(item) }}%</span>
        </div>
        <div class="card-detail">
          <div class="detail-row">
            <span class="label">索引器</span>
            <span class="value">{{ item.indexerName || '-' }}</span>
          </div>
          <div class="detail-row">
            <span class="label">下载器</span>
            <span class="value">{{ item.downloaderName || '-' }}</span>
          </div>
          <div class="detail-row">
            <span class="label" :title="SEEDERS_HINT">体积/推送时做种</span>
            <span class="value">{{ formatFileSize(item.size) }} / {{ item.seeders ?? '-' }}</span>
          </div>
          <div class="detail-row">
            <span class="label">推送时间</span>
            <span class="value">{{ item.pushedTime || '-' }}</span>
          </div>
          <div class="detail-row" v-if="item.state === 'COMPLETED'">
            <span class="label">完成时间</span>
            <span class="value">{{ item.completedTime || '-' }}</span>
          </div>
          <div class="detail-row" v-if="item.hrState">
            <span class="label">H&amp;R 保种</span>
            <span class="value card-hr">
              <StatusChip :type="hrTagType(item.hrState)" :text="hrStateLabel(item.hrState)" />
              <span class="card-hr-progress">{{ hrProgress(item) }}</span>
            </span>
          </div>
        </div>
        <div class="card-fail" :class="{ 'card-fail--superseded': item.supersededById }" v-if="item.state === 'FAILED'">
          <v-icon icon="circle-alert" size="16" />
          <StatusChip v-if="item.failReasonCode" :type="failReasonTagType(item.failReasonCode)" :text="failReasonCodeLabel(item.failReasonCode)" />
          <span>{{ item.failReason || '未知原因' }}</span>
        </div>
        <!-- 失败之后同一集已经有了新的推送：该看的是后面那条，这条不再给重试 -->
        <div class="card-superseded" v-if="item.state === 'FAILED' && item.supersededById">
          <v-icon icon="circle-check" size="14" />
          已由后续推送 #{{ item.supersededById }} 接替
        </div>
        <div class="card-actions" @click.stop>
          <v-btn
            v-if="canRetry(item)"
            variant="text"
            color="primary"
            size="small"
            prepend-icon="refresh-cw"
            :loading="retryingIds.has(item.id)"
            @click="handleRetry(item)"
          >
            重试
          </v-btn>
          <v-btn
            class="action-more"
            variant="text"
            color="default"
            size="small"
            icon="ellipsis"
            @click="openSheet(item)"
          />
        </div>
      </div>
    </v-card>

    <template #foot>
      <!-- 操作抽屉 -->
      <MobileActionSheet v-model="sheetOpen" :target="sheetTarget">
        <v-btn
          v-if="sheetTarget.torrentHash"
          block
          prepend-icon="copy"
          @click="run(() => copyTorrentHash(sheetTarget))"
        >
          复制种子 hash
        </v-btn>
        <v-btn
          color="warning"
          block
          prepend-icon="ban"
          :disabled="sheetTarget.guidBlacklisted"
          @click="run(() => handleBlacklistGuid(sheetTarget))"
        >
          {{ sheetTarget.guidBlacklisted ? '种子已拉黑' : '拉黑该种子' }}
        </v-btn>
        <!-- 标题里解析不出发布组时后端也拉黑不了，按钮不给 -->
        <v-btn
          v-if="sheetTarget.releaseGroup"
          color="error"
          block
          prepend-icon="user-x"
          :disabled="sheetTarget.releaseGroupBlacklisted"
          @click="run(() => handleBlacklistReleaseGroup(sheetTarget))"
        >
          {{ sheetTarget.releaseGroupBlacklisted ? `${sheetTarget.releaseGroup} 已拉黑` : `拉黑发布组 ${sheetTarget.releaseGroup}` }}
        </v-btn>
      </MobileActionSheet>

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

      <!-- 分页 -->
      <MobilePager
        v-model:page-size="queryParams.pageSize"
        :page-sizes="[12, 24, 48]"
        :page-num="queryParams.pageNum"
        :total="total"
        :total-pages="totalPages"
        @prev="prevPage"
        @next="nextPage"
        @size-change="handleSizeChange"
      />
    </template>
  </MobileListPage>
</template>

<script setup lang="ts">
import MobileListPage from '@/components/mobile/MobileListPage.vue'
import MobileActionSheet from '@/components/mobile/MobileActionSheet.vue'
import MobileBatchBar from '@/components/mobile/MobileBatchBar.vue'
import MobileSearchPanel from '@/components/mobile/MobileSearchPanel.vue'
import MobilePager from '@/components/mobile/MobilePager.vue'
import StatusChip from '@/components/StatusChip.vue'
import RecordStatusBar from '@/components/RecordStatusBar.vue'
import PtBlacklistDialog from '@/components/dialogs/PtBlacklistDialog.vue'
import PtDownloadRecordCleanupDialog from '@/components/dialogs/PtDownloadRecordCleanupDialog.vue'
import { usePtDownloadRecord } from '@/composables/usePtDownloadRecord'
import {
  DOWNLOAD_STATE_OPTIONS, FAIL_REASON_OPTIONS, HR_STATE_OPTIONS, SEEDERS_HINT,
  stateLabel, stateTagType, failReasonCodeLabel, failReasonTagType, hrStateLabel, hrTagType,
  hrProgress, progressPercent, hasProgress, canRetry
} from '@/composables/ptDownloadRecordLabels'
import { formatFileSize } from '@/composables/useRecordList'
import { useActionSheet } from '@/composables/useActionSheet'
import { getRoutePathForComponent } from '@/router'

// 订阅页的路由 path 按组件反查，不写死：菜单 path 历史上有 /openlist 与 /openliststrm 两种前缀
const subscriptionPath = getRoutePathForComponent('openlist/ptSubscription/index')

const {
  taskList, loading, total, queryParams, stats, queryRef,
  handleQuery, resetQuery, dateStart, dateEnd, indexerOptions, downloaderOptions,
  subFilterLabel, clearSubFilter,
  retryingIds, handleRetry,
  selectionMode, toggleSelectionMode, selectedIds, toggleRecordSelect, handleCardClick,
  isAllPageSelected, toggleSelectAllPage,
  retryableSelectedIds, handleBatchRetry,
  handleBatchBlacklistGuid, handleBatchBlacklistReleaseGroup,
  totalPages, prevPage, nextPage, handleSizeChange,
  searchCollapsed,
  handleBlacklistGuid, handleBlacklistReleaseGroup, blacklistDialog, submitBlacklist,
  copyTorrentHash,
  cleanupDialog, cleanupDayOptions, openCleanup, submitCleanup
} = usePtDownloadRecord()

/** 卡片「更多」动作面板：开关状态与「执行完自动关闭」都在 useActionSheet 里 */
const { sheetOpen, sheetTarget, openSheet, run } = useActionSheet()
</script>

<style scoped lang="scss">
.list-toolbar {
  display: flex;
  justify-content: flex-end;
}

.sub-filter-chip {
  margin-bottom: 8px;
}

.list-loading {
  border-radius: var(--osr-radius-md);
}

.task-card {

  .card-sub {
    font-size: 12px;
    color: var(--osr-text-secondary);

    .card-sub-link {
      color: var(--osr-primary);
      text-decoration: none;
    }
  }

  /* 进度条右侧带百分比：光看一根条读不出是 40% 还是 60% */
  .card-progress {
    display: flex;
    align-items: center;
    gap: 8px;
  }

  .card-progress-text {
    min-width: 36px;
    text-align: right;
    font-size: 11px;
    color: var(--osr-text-secondary);
  }

  .card-hr {
    display: flex;
    align-items: center;
    justify-content: flex-end;
    flex-wrap: wrap;
    gap: 6px;
  }

  .card-hr-progress {
    font-size: 11px;
    color: var(--osr-text-secondary);
  }

  .card-fail {
    display: flex;
    align-items: flex-start;
    gap: 6px;
    padding: 6px 8px;
    border-radius: var(--osr-radius-sm);
    background: var(--osr-error-light);
    color: var(--osr-error);
    font-size: 11px;
    line-height: 1.5;
  }

  /* 已被接替的失败记录退成次要信息，不再用红底抢眼 */
  .card-fail--superseded {
    background: rgba(var(--v-theme-on-surface), 0.05);
    color: var(--osr-text-secondary);
  }

  .card-superseded {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 11px;
    color: rgb(var(--v-theme-success));
  }

}
</style>
