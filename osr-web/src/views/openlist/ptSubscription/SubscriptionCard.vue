<template>
  <div
    class="item-card item-card--compact"
    :class="{ 'item-card--selectable': selectionMode }"
    @click="selectionMode && toggleSubSelect(item)"
  >
    <v-checkbox-btn
      v-if="selectionMode"
      class="item-card-checkbox card-checkbox"
      :model-value="isSubSelected(item.id)"
      @click.stop="toggleSubSelect(item)"
    />
    <!-- 海报 + 信息横排。item-card 本身是纵向的，横排是订阅卡特有的，包一层私有容器 -->
    <div class="sub-main">
      <div class="sub-poster">
        <img
          v-if="item.posterPath && !posterErrorIds.has(item.id)"
          :src="posterUrl(item.posterPath)"
          :alt="item.title"
          loading="lazy"
          @error="onPosterError(item.id)"
        />
        <div v-else class="sub-poster-placeholder" :class="item.mediaType === 'MOVIE' ? 'placeholder-movie' : 'placeholder-tv'">
          <v-icon :icon="item.mediaType === 'MOVIE' ? 'film' : 'monitor-play'" size="28" />
          <span class="placeholder-text">{{ item.mediaType === 'MOVIE' ? '电影' : '剧集' }}</span>
        </div>
      </div>
      <div class="sub-info">
        <div class="card-header card-header--top">
          <span class="card-title card-title--clamp2" :title="item.title">
            {{ item.title }}
            <span v-if="item.year" class="sub-year">({{ item.year }})</span>
          </span>
          <StatusChip v-if="item.status === 'ACTIVE'" type="success" text="订阅中" />
          <StatusChip v-else-if="item.status === 'COMPLETED'" type="info" text="已完成" />
          <StatusChip v-else type="warning" text="已暂停" />
        </div>
        <div class="sub-meta">
          <span>{{ item.mediaType === 'MOVIE' ? '电影' : '剧集' }}</span>
          <span v-if="item.mediaType !== 'MOVIE'">S{{ item.season }}</span>
          <span>共 {{ item.totalEpisodes }} 集</span>
          <!-- 被单独配过过滤规则/下载器的订阅要看得出来，否则只能逐条打开弹窗才知道 -->
          <v-chip
            v-if="hasFilterOverride(item)"
            class="sub-flag"
            size="x-small"
            color="info"
            variant="tonal"
            prepend-icon="funnel"
            title="该订阅有自己的过滤规则覆盖，未覆盖的项仍沿用全局配置"
          >过滤覆盖</v-chip>
        </div>
        <!-- 入库进度：列表接口已带进度计数，不必逐条点开进度弹窗才知道还缺几集 -->
        <div v-if="item.inLibraryCount !== undefined && item.inLibraryCount !== null" class="sub-progress">
          <v-progress-linear
            :model-value="progressPercent(item)"
            :color="progressColor(item)"
            height="6"
            rounded
          />
          <span class="sub-progress-text">
            {{ item.inLibraryCount }}/{{ item.totalEpisodes }}
            <span v-if="item.inFlightCount" class="sub-progress-inflight">· 在途 {{ item.inFlightCount }}</span>
          </span>
        </div>
        <div class="card-row">
          <span class="label">上次命中</span>
          <span class="value">{{ item.lastMatchTime || '-' }}</span>
        </div>
        <div class="card-row">
          <span class="label">上次搜索</span>
          <span class="value">{{ item.lastSearchTime || '-' }}</span>
        </div>
        <!-- 两个开关并作一行：各占一行只为两个布尔值，能吃掉卡片近三分之一的高度 -->
        <div class="sub-switches" @click.stop>
          <div class="sub-switch">
            <span class="label">自动补搜</span>
            <v-switch
              :model-value="item.autoSearch"
              true-value="1"
              false-value="0"
              color="primary"
              density="compact"
              hide-details
              @update:model-value="(v: any) => toggleAutoSearch(item, v)"
            />
          </div>
          <div class="sub-switch">
            <span class="label">洗版</span>
            <v-switch
              :model-value="item.upgradeEnabled"
              true-value="1"
              false-value="0"
              color="primary"
              density="compact"
              hide-details
              @update:model-value="(v: any) => toggleUpgrade(item, v)"
            />
          </div>
        </div>
      </div>
    </div>
    <!-- @click.stop：批量模式下卡片本身是可点选的，点操作按钮不该顺手把卡片也选上 -->
    <div class="card-footer" @click.stop>
      <v-btn variant="text" color="primary" size="small" @click="showProgress(item)">进度</v-btn>
      <v-btn variant="text" color="primary" size="small" @click="goDownloadRecords(item)">下载记录</v-btn>
      <v-menu>
        <template #activator="{ props: menuProps }">
          <v-btn v-bind="menuProps" class="more-actions-trigger" variant="text" color="info" size="small" append-icon="chevron-down">更多</v-btn>
        </template>
        <v-list density="compact">
          <v-list-item v-if="item.status !== 'PAUSED'" @click="handleMoreCommand('pause', item)">暂停</v-list-item>
          <v-list-item v-else @click="handleMoreCommand('resume', item)">恢复</v-list-item>
          <v-list-item @click="handleMoreCommand('search', item)">搜索补齐</v-list-item>
          <v-list-item @click="handleMoreCommand('refresh', item)">对账</v-list-item>
          <v-list-item @click="handleMoreCommand('logs', item)">匹配日志</v-list-item>
          <v-list-item @click="handleMoreCommand('filter', item)">过滤规则</v-list-item>
          <v-divider class="my-1" />
          <!-- 删除置底并单独分隔：它会连带删掉集数追踪记录，与「进度」这类高频动作并排太容易误点 -->
          <v-list-item class="more-actions-danger" @click="handleMoreCommand('remove', item)">删除</v-list-item>
        </v-list>
      </v-menu>
    </div>
  </div>
</template>

<script setup lang="ts">
import { reactive, watch } from 'vue'
import StatusChip from '@/components/StatusChip.vue'
import { getRoutePathForComponent } from '@/router'
import { useRouter } from 'vue-router'
import { usePtSubscriptionContext } from '@/composables/ptSubscriptionContext'

/** 订阅列表里的一张卡。状态全部来自页面 provide 的同一个实例（见 ptSubscriptionContext） */
defineProps<{ item: any }>()

const {
  handlePause,
  handleRefresh,
  handleRemove,
  handleResume,
  isSubSelected,
  openFilterOverride,
  openSeasonSearch,
  posterUrl,
  selectionMode,
  showProgress,
  showSearchLogs,
  toggleAutoSearch,
  toggleSubSelect,
  toggleUpgrade
,
  taskList
} = usePtSubscriptionContext()

const router = useRouter()

/** 海报加载失败的订阅 id 集合，命中则展示占位图标而非裂图；数据刷新后清除以便重试 */
const posterErrorIds = reactive(new Set<number>())
const onPosterError = (id: number) => { posterErrorIds.add(id) }

/** 该订阅是否配了自己的过滤规则覆盖。空字符串同样算「没有覆盖」，与后端一致 */
const hasFilterOverride = (row: any) => {
  if (!row?.filterOverride) return false
  try {
    return Object.keys(JSON.parse(row.filterOverride) || {}).length > 0
  } catch {
    // 脏数据不该让整个列表炸掉，也不该谎报成「有覆盖」
    return false
  }
}

const progressPercent = (row: any) => {
  const total = Number(row?.totalEpisodes) || 0
  if (!total) return 0
  return Math.round(((Number(row.inLibraryCount) || 0) / total) * 100)
}

/** 满了用成功色，其余用主色——进度条本身不是告警，缺集是常态 */
const progressColor = (row: any) => (progressPercent(row) >= 100 ? 'success' : 'primary')

const goDownloadRecords = (row: any) => {
  const path = getRoutePathForComponent('openlist/ptDownloadRecord/index')
  if (path) router.push({ path, query: { subId: row.id } })
}

/** "更多"下拉菜单 command → 现有函数的分发，纯路由不新增业务逻辑 */
const handleMoreCommand = (cmd: string, row: any) => {
  switch (cmd) {
    case 'refresh': handleRefresh(row); break
    case 'logs': showSearchLogs(row); break
    case 'filter': openFilterOverride(row); break
    case 'search': openSeasonSearch(row); break
    case 'pause': handlePause(row); break
    case 'resume': handleResume(row); break
    case 'remove': handleRemove(row); break
  }
}

// 列表数据变化（刷新/翻页/重新查询）后清除海报失败集合，海报恢复或 TMDb 修复后能重新加载
watch(taskList, () => posterErrorIds.clear())
</script>

<style scoped lang="scss">
/* 卡片外壳（边框/圆角/hover/可点选/紧凑内距）全部来自 styles/list.scss 的 .item-card，
   这里只写订阅卡特有的：海报横排、进度条、开关行。 */

.item-card-checkbox {
  position: absolute;
  top: 4px;
  left: 4px;
  z-index: 1;
}

/* 海报 + 信息横排 */
.sub-main {
  display: flex;
  gap: 12px;
  min-width: 0;
}

.sub-poster {
  flex-shrink: 0;
  width: 72px;
  height: 108px;
  border-radius: var(--osr-radius-sm);
  overflow: hidden;
  background: var(--osr-bg-page);

  img {
    width: 100%;
    height: 100%;
    object-fit: cover;
    display: block;
  }

  .sub-poster-placeholder {
    width: 100%;
    height: 100%;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 4px;
    color: var(--osr-text-disabled);
    font-size: 22px;

    /* 下面两组渐变是刻意的装饰色：海报占位在明暗主题下都保持深底白字，
       与主题色无关，因此不走 --osr-* 令牌 */
    &.placeholder-movie {
      background:
        radial-gradient(circle at 30% 20%, rgba(255, 255, 255, 0.14), transparent 45%),
        linear-gradient(135deg, #1e3a5f 0%, #2d5a87 50%, #1e3a5f 100%);
      color: rgba(255, 255, 255, 0.7);
    }

    &.placeholder-tv {
      background:
        radial-gradient(circle at 30% 20%, rgba(255, 255, 255, 0.14), transparent 45%),
        linear-gradient(135deg, #3b1f47 0%, #6b3a7a 50%, #3b1f47 100%);
      color: rgba(255, 255, 255, 0.7);
    }

    .placeholder-text {
      font-size: 11px;
      font-weight: 500;
      letter-spacing: 1px;
    }
  }
}

.sub-info {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;

  /* 卡片窄，「上次命中」这类标签用不着 list.scss 里给表格卡准备的 72px */
  .card-row .label {
    width: 58px;
  }
}

.sub-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12px;
  color: var(--osr-text-secondary);
}

.sub-flag {
  /* chip 自带的高度在这一行里偏大，压到与旁边的文字同高 */
  height: 18px;
}

.sub-progress {
  display: flex;
  align-items: center;
  gap: 8px;
}

.sub-progress-text {
  flex-shrink: 0;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--osr-text-secondary);
}

.sub-progress-inflight {
  color: var(--osr-primary);
}

/* 两个开关并作一行 */
.sub-switches {
  display: flex;
  gap: 16px;
  flex-wrap: wrap;
}

.sub-switch {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;

  .label {
    color: var(--osr-text-secondary);
    white-space: nowrap;
  }
}

.sub-year {
  color: var(--osr-text-secondary);
  font-size: 12px;
}
</style>
